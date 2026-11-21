# StrideSync — CLAUDE.md

## What this is
StrideSync is an Android app for Parkinson's gait training using rhythmic auditory cueing. The phone's accelerometer
(ideally at the lower back) detects steps. A generated metronome/beat locks onto the walker's cadence and phase. Then
it slowly raises the tempo to lengthen the stride. A therapist web portal shows cadence, consistency and trends. It
runs locally on **mock data**; the app doesn't upload (milestone 6 was skipped).

**Context: 24-hour solo hackathon prototype.** It will not be used with real patients. So:
- Optimize for a working end-to-end demo over polish, completeness or architecture purity.
- Ignore HIPAA, PHI handling, regulatory and medical-device concerns.
- Show one line in the app's UI: "Prototype — not a medical device." (The portal shows it too.)
- Don't add features beyond the milestones below without asking.

## Status (2026-11-21)
- Milestones 1–5 are done, and verified on the test phone (an S21+, SM-G996U1, Android 15).
- Milestone 6 (Firestore upload) was **skipped** by the user's decision.
- Milestone 7 (portal) is done, but runs locally on mock data, with no Firebase Hosting.
- Milestone 8 is partly done: the big Start/Stop and the state label exist, but **latency calibration is still open**.
- Added at the user's request after live walk tests: **Follow mode**, **Demo timing** (about a 1-minute cycle), and a
  tracking rework driven by real recordings (see the Pipeline section and the Gotchas).
- **Music cue:** a user-provided song as the cue, its tempo locked to the beat clock (see Audio). Follow + Music
  was retested live after the smooth music profile went in; the user reported it "worked great".

## Stack
- **Android:** Kotlin, Jetpack Compose, coroutines/Flow, `SensorManager`, `AudioTrack`.
  - Package `com.tidalbyte.stridesync`. minSdk 29, targetSdk 37.
  - compileSdk 37.2, because Compose BOM 2026.11.01 needs ≥37.1.
  - AGP 9.5.2 (built-in Kotlin: don't apply `kotlin-android`), Kotlin 2.4.21, Gradle 9.9.2.
- **Backend:** none. Firebase isn't used.
- **Portal:** Vite 8 + vanilla TypeScript 7 + Chart.js 4. Static and local; it reads `portal/src/data/mock-data.json`.
- **No:** ExoPlayer/Media3, Rubber Band, SoundTouch, time-stretching, streaming services, Hilt/Dagger (wire things
  by hand), or Room.

The cue is a **synthesized click or woodblock**, or **Music**: a user-provided song played varispeed (sped up or
slowed down) between its own beats, so each song beat lands on the beat clock's frame. Either way the beat clock
fully controls tempo and phase, and beats are sample-accurate. Pitch follows tempo for Music; there's no time-stretch
library.

## Repo layout (as built)
```
android/app/src/main/java/com/tidalbyte/stridesync/
  sensor/   SensorSource + AccelSample; LiveAccelSource; ReplaySource (+ReplayRef); AccelCsv;
            SimulatedWalker (pure Kotlin); SimulatedWalkerSource (real-time wrapper)
  gait/     StepDetector, CadenceEstimator, GaitStats, Biquad                     ← pure Kotlin
  cue/      PhaseLockedLoop (+Coupling), RampController (+CueState, RampTiming), SyncTracker, CueOutput  ← pure Kotlin
  audio/    MetronomeEngine (AudioTrack; implements CueOutput), BeatClock (pure Kotlin scheduler/mixer), ClickSynth
            (+CueSound), SongTrack + SongRenderer (pure Kotlin: beat-locked varispeed song), SongLoader (assets)
  session/  GaitCuePipeline (pure Kotlin: detector → cadence → PLL → ramp → cue), SessionController (Android
            wiring, LiveState), SessionHub (the process-wide controller + SourceSpec), SampleRecorder (CSV)
  service/  CueService (foreground service, type mediaPlayback)
  ui/       HomeScreen (the only screen), Theme
  Tuning.kt every tunable constant, documented with units and rationale
  Log.kt    SLog: one tag "StrideSync", messages start with "[component]"
android/app/src/test/      JVM tests: gait/, cue/, audio/, sensor/, session/ (closed-loop)
android/app/src/main/assets/replays/   real recorded walks (front pocket, lower back): Replay demos + test fixtures
portal/                    Vite app: src/{main,charts,data,dom}.ts, src/views/*, src/data/mock-data.json
tools/seed_portal.mjs      generates portal/src/data/mock-data.json
tools/prepare_song.py      converts a song + maps its beats → android/app/src/main/assets/music/song.{wav,json}
music/                     GITIGNORED: the user's song files (input). The current song: Stayin' Alive (Instrumental)
android/app/src/main/assets/music/   GITIGNORED: the prepared song. A fresh clone has none; the Music chip is disabled
```
Not built: `data/FirestoreRepo`, `SessionRecorder`/`SessionSummary`, `firebase/`, and separate
LiveSession/Summary/Debug screens. The Record toggle lives on Home.

## Commands
```bash
# Android (from android/). The JDK and SDK are user-space installs, not on PATH:
export JAVA_HOME=~/.local/jdks/current ANDROID_HOME=~/Android/Sdk PATH=$PATH:$HOME/Android/Sdk/platform-tools
./gradlew testDebugUnitTest assembleDebug installDebug
adb logcat -s StrideSync:*      # components: step, pll, ramp, cue, audio, sensor, service, session, record, replay
adb pull /sdcard/Android/data/com.tidalbyte.stridesync/files/recordings/ ./recordings/

# Portal (from portal/)
npm install && npm run dev      # http://localhost:5173
npm run build && npm run preview
npm run seed                    # = node ../tools/seed_portal.mjs. Rerun on demo day so "today" is current.

# Music cue (from repo root; needs ffmpeg + numpy). Then rebuild/install the app.
python3 tools/prepare_song.py "music/<file>" --title "Name" --preview   # also --start/--end, --shift-half
```

## Pipeline (data flow)
```
SensorSource ─samples─▶ StepDetector ─steps─▶ CadenceEstimator ─spm (2-step intervals), stride CV─┐
                              │                                                                    │
                              └─steps─▶ PhaseLockedLoop ◀── pair-averaged error − learned offset   │
                                             │ phase shift                                         ▼
                                             ▼                        RampController: tempo = FLL from cadence
                                   CueOutput = MetronomeEngine        while the beat follows; ramp ladder while
                                   (BeatClock + AudioTrack)           it leads; hard caps in capBpm()
```
`GaitCuePipeline` (pure Kotlin) contains all of this and runs identically on the device and in JVM closed-loop tests,
which drive a virtual BeatClock. `SessionController` hosts it on a coroutine. `SessionHub` owns the single controller.
`CueService` runs it in the foreground, so it survives screen-off. The UI observes `StateFlow<LiveState>`.

### 1. Sensor input — `sensor/`
- All sources emit `AccelSample(tNanos, x, y, z)` (elapsedRealtime nanos) through the same pipeline.
- `LiveAccelSource`: `TYPE_ACCELEROMETER` at `SENSOR_PERIOD_US = 5_000` (200 Hz, the cap without the extra
  permission; the S21's LSM6DSO delivers ~200 Hz). Callbacks run on a HandlerThread. It uses `event.timestamp`.
- `ReplaySource`: plays `t_ns,ax,ay,az` CSVs from `assets/replays/` or the phone's recordings, rebased to now. A
  recording can't react to the cue, so Replay shows detection and locking, not entrainment.
- `SimulatedWalker`:
  - **Signal:** a deterministic synthetic gait signal with ground-truth strike times. Options include freezes with
    tremor and `farStepDelaySec` (the front-pocket artifact).
  - **Entrainment:** it **partially entrains** to *audible* beats: tempo pull (τ 10 s, strength 0.9), per-step
    phase correction (α 0.3), reduced drift while cued, and a hard ability ceiling (natural +6%), so an overreach is
    real. Deliberate pace (the slider) changes within a few steps.
  - **Follow mode:** the simulated walker uses strength 0.3 / α 0.05 so it leads.
- **Recording:** a "Record raw samples" switch on Home (Live only). The Pocket / Lower back / Belt clip chip goes
  in the file name.

### 2. Step detection — `gait/StepDetector`
- The chain:
  1. magnitude;
  2. a uniform 200 Hz resample;
  3. a gravity EMA (2 s);
  4. a 4 Hz biquad low-pass;
  5. local maxima with parabolic sub-sample timing.
- **Threshold:** 0.3 × the median of the last 6 peak heights, decaying after the last step down to a 0.35 floor.
  At 0.4, real pocket walks locked into one step per stride (weak far-foot peaks dropped out → half cadence).
- **Refractory:** a candidate is held as *pending*, and a higher peak within the refractory replaces it. The
  refractory is clamp(0.45 × the 75th percentile of recent intervals, 250–500 ms); the percentile is what blocks
  push-off double counts.
- Parkinson's gait is slow, shuffling and low-amplitude. Don't hard-code healthy-gait assumptions.
- The detected peak lags the heel strike by a near-constant ~63 ms (`DETECTOR_LAG_MS`).

### 3. Cadence — `gait/CadenceEstimator`
- **Two-step intervals** (t[k] − t[k−2], one stride). A long + short pair cancels left/right alternation exactly.
- spm = 120 / median(last 6). `fastSpm` = the median of the last 5. With 3, one glitch step (it corrupts 2
  overlapping intervals) caused spikes on real walks.
- CV = **stride-time CV** over the last 10 (the clinical variability measure).

### 4. Phase lock and tempo following — `cue/PhaseLockedLoop`, `session/GaitCuePipeline`
- **Error:** step − nearest beat. Listed beats are ground truth; the grid is extrapolated past the last one.
- **Pair averaging:** each error is averaged with the previous step's error, re-expressed after the shift that
  followed it.
- **Learned offset:** an EMA over 8 steps, learned only from steps within ±120 ms of it, clamped to ±100 ms. It's
  learned **only while the beat follows** and frozen while it leads. It's subtracted before correction and before
  the sync judgment.
- **Phase correction:** kP × (the error beyond a ±25 ms dead band), clamped per beat (5% of the period; 10% in
  Follow). **kI = 0.**
- **Tempo while the beat follows** (LOCKING, Follow) comes from measured cadence, an FLL closing 50% of the gap per
  step. Follow jumps to `fastSpm` on a >4% disagreement sustained for 2 steps. While leading, the ramp sets the tempo.
- **Couplings:**
  - ACQUIRE: muted, unclamped, kP 0.7.
  - LOCK: kP 0.5.
  - FOLLOW: kP 0.5, 10% clamp, and a one-off **re-sync jump** when the error stays over 25% of a period for 2 steps.
  - LEAD: kP 0.1.
  - **With Music (any mode):** MUSIC_FOLLOW / MUSIC_LEAD.
- **Why not an integrating PI loop:** the walker follows the beat while the beat follows the walker, so any constant
  bias (a wrong latency, the placement artifact) makes them chase each other and the tempo drifts without bound.
- **The smooth profile, whenever Music plays** (`GaitCuePipeline.music`):
  - Per-beat caps: tempo 3%, phase 2.5%.
  - Phase kP 0.15. No re-sync jumps.
  - Tempo gain 0.3 per step. Follow's fast switch needs a >6% disagreement for 3 steps.
  - Why: with a song, every per-beat timing change is an audible tempo/pitch wobble. Click-tuned settings made the
    song jump >4% on 1 beat in 3 (up to 50%). The walker entrains to music themselves, so smooth tempo beats snapping
    the phase.
- **Sync metrics:**
  - % of steps within ±max(60 ms, 8% of the period). The window scales with tempo, as chosen by the user.
  - mean |asynchrony|.
  - Rolling window: 16 steps (8 in demo timing).

### 5. Ramp — `cue/RampController`
- **States:**
  - CALIBRATING: silent; 20 steps; baseline from two-step intervals.
  - LOCKING: muted acquisition until 2 in-window steps, then audible; ramping needs `lockMinSec` plus a full in-sync
    window.
  - RAMPING: +2% per hold while sync ≥ 80%.
  - HOLDING: marginal sync, at the cap, or after a settle.
  - STEPPING_BACK: sync < 50% or a cadence drop >15% → one level down, 20 s settle.
  - PAUSED: no steps for 3 s → tempo frozen, beat continues.
- **Deviations from the original spec, all intentional:**
  - The settle is 20 s, not 10 s. At 10 s the walker's re-adaptation was judged "lost" and the ramp cascaded down.
  - The rolling sync window restarts after any tempo command, at the end of a settle, and on resume.
  - Lost sync at the base tempo → re-LOCK.
  - PAUSED during LOCKING resumes LOCKING.
- **Hard caps, only in `capBpm()`/`rampUpFrom()`:** ≤ baseline × 1.10 and ≤ 130 bpm absolute (Follow: 130 only);
  each increase ≤ +2%; only RAMPING raises the tempo. Fuzz-tested.
- **`RampTiming.DEMO`:** 8-step calibration, 8 s lock minimum, 10 s holds, 6 s settle, 8-step window. The full cycle
  takes about a minute. On by default via the "Demo timing" switch.
- **Follow mode** (`followOnly`): 8-step calibration (4 gave erratic first tempos), then it stays in LOCKING (shown
  as "FOLLOWING") and never ramps.

### 6. Audio — `audio/`
- **BeatClock** (pure Kotlin):
  - Places beats on exact sample frames, with no drift.
  - Tempo and phase changes affect only unwritten beats and are slew-limited per beat. The limits are lifted while
    muted and for a one-off re-sync.
- **MetronomeEngine:**
  - 48 kHz mono 16-bit `MODE_STREAM` `USAGE_MEDIA`, with an URGENT_AUDIO writer thread.
  - 240-frame chunks and ~100 ms of write-ahead.
  - Every beat is flagged audible or muted.
  - Sounds: click, woodblock, music.
- **Music** (`CueSound.MUSIC` → `BeatClock.music = SongRenderer`):
  - The renderer fills the audio between beats with the matching song segment, at
    rate = (target song-beat position − position) / (frames to the cue beat). It re-targets each chunk, so tempo
    changes, phase nudges and muting just bend the rate.
  - Each cue beat maps to ½, 1 or 2 song beats, chosen once to keep the rate nearest 1.
  - Cubic interpolation. The loop is whole bars with a 6 ms crossfade.
  - While muted, the position still advances; unmuting fades in over 300 ms (already in phase).
  - Tests: song beats land on clock beats to 0 frames through tempo changes, shifts and loop wraps.
- **`tools/prepare_song.py`:**
  - ffmpeg → 48 kHz mono, peak-normalized.
  - Beats: a spectral-flux onset envelope, autocorrelation tempo (prior ~110 bpm), comb phase, and per-beat tracking
    (±40 ms) for drift.
  - **Kick check:** it folds kick-band (40–150 Hz) onsets by beat phase and shifts the grid half a beat if the kicks
    fall between the beats. Trackers lock onto off-beat snares and hi-hats; it happened with Stayin' Alive.
  - Reports bpm and grid residual, and warns if the tempo isn't steady. `--preview` writes a clicks-on-beats WAV for
    an ear check.
  - Stayin' Alive (Instrumental): 103.7 bpm, 469 beats, residual median 4 ms. The asset is 26 MB, making the APK
    ~63 MB.

### 7. Session and reporting
- `SessionSummary` and the upload aren't built (milestone 6 was skipped).
- The session end logs one `[session]` line: state, baseline, ramp steps, sync %, mean asynchrony.

## Data schema (mock data = the planned Firestore documents)
```
patients[]: { id, displayName, createdAt, latestBaselineSpm, sessions[] }
session:    { id, startedAt, durationSec, baselineSpm, finalSpm, peakSpm, meanCv, syncPct, meanAsyncMs, rampSteps,
              events: [{t, from, to, reason}],
              series: [{t, spm, cueBpm, asyncMs, cv, state}] }      // one point per 5 s
```

## Portal (as built)
- **Patients list → patient page → session page:**
  - Patient page: KPI tiles; baseline vs with-cue cadence; % in sync; stride CV; a sessions table.
  - Session page: cadence + cue tempo on one axis (same unit) with labeled state bands; asynchrony with the
    ±60 ms band; the event log.
- **Every chart:** a Chart/Table toggle and a crosshair tooltip. Light/dark follows `prefers-color-scheme`. Colors
  are the dataviz reference palette, validated.
- **`tools/seed_portal.mjs`:**
  - Runs a simplified ramp state machine per session.
  - 4 **fictional** patients, about 3 weeks, gradual improvement, bad days.
  - Eleanor V. has a session dated today for the demo finish.
  - Deterministic for a given day (`SEED_TODAY=YYYY-MM-DD`).

## Demo plan
1. **Simulated walker:**
   - Ramp + Demo timing: calibrate → lock → ramp → overreach → step-back in about 1 minute.
   - Follow mode + the cadence slider shows tracking.
   - Pick the **Music** cue sound to hear the song's tempo follow (on the phone it glided 92 → 114 bpm over about
     8 s when the walker sped up).
2. **Replay:** a bundled real walk (a lower-back one is cleanest).
3. **Live:**
   - Put the phone **pressed flat or tucked at the lower back**. That gave 70–82% sync in tests; pockets gave
     50–68%.
   - Use the phone speaker or wired earbuds (not Bluetooth), media volume up, battery set to Unrestricted.
4. **Portal:** `npm run dev`, then finish on Eleanor V.'s session from today. It's mock data; the app doesn't upload.

## Milestones
1. ✅ `SimulatedWalker` + `StepDetector` + `CadenceEstimator`, with unit tests and spm on screen.
2. ✅ `MetronomeEngine` at a fixed BPM, then following measured cadence.
3. ✅ `PhaseLockedLoop` and sync metrics.
4. ✅ `RampController` with caps and tests. Entraining simulated walker.
5. ✅ `CueService` foreground service + live sensor + record-to-CSV. Tested on the phone with the screen off.
6. ⏭ `SessionSummary` → Firestore. **Skipped.**
7. ✅ Portal + seed script. Local and mock data, no Hosting.
8. ◐ Demo polish: big Start/Stop ✅, clear state label ✅, latency calibration ✗.

**Open:** latency. `OUTPUT_LATENCY_MS` = `SPEAKER_LATENCY_MS` (12, a guess) + `DETECTOR_LAG_MS` (63). Sync % is
measured around a learned offset, so only an ear test ("does the click land on the heel strike?") or a tap-along
calibration can validate the absolute timing. If time is short, hard-code a value from an ear test.

## Coding conventions
- `gait/` and `cue/` stay free of Android imports, and so do `BeatClock`, `SimulatedWalker` and `GaitCuePipeline`.
  Timestamps are `Long` nanos.
- All tunables live in `Tuning.kt`, with units and rationale (including why a value changed). Don't scatter magic
  numbers.
- **Tests (JVM only, ~84):**
  - detector accuracy on synthetic gait (±20 ms, including weak, slow, freeze, irregular and pocket-artifact cases);
  - cadence/CV;
  - PLL;
  - every RampController transition and cap, plus a fuzz test;
  - closed loop (walker → pipeline → virtual BeatClock): demo cycle, Follow tracking, latency mismatch (no drift),
    pocket artifact, no step-back cascade, never above the cap, Music glides (p95 beat-to-beat change ≤3.5%) and
    never re-syncs;
  - SongRenderer beat landing and continuity;
  - real recordings vs autocorrelation.
- **Tuning against real data:** replay the recordings through `GaitCuePipeline` offline (seconds, not minutes on the
  device), and compare with the non-causal "oracle" sync bound before changing the algorithm. The current pipeline
  matches the oracle on every recording, so the remaining loss is the data.
- After a change, run `testDebugUnitTest` and `assembleDebug`, then install and check on the device.
- Log state transitions and per-step phase error at debug level.

## Gotchas
- **Two clocks.** `SensorEvent.timestamp` uses the elapsedRealtime base. `AudioTrack.getTimestamp()` uses `nanoTime`.
  Convert with an offset sampled once at start: `SystemClock.elapsedRealtimeNanos() - System.nanoTime()`.
- **Output latency.** Beat time = `getTimestamp()` presentation time + `OUTPUT_LATENCY_MS`. That includes the
  detector lag, because the PLL compares against *detected* steps. Bluetooth adds 150–300 ms, so don't use it.
- **Placement dominates accuracy.** A front-pocket thigh sees one leg strongly: detected intervals alternate
  ~750/375 ms around a true ~560. Two-step cadence and pair-averaged errors handle it, but per-step timing scatter
  still caps sync near 60%. The lower back is clearly best.
- **Mutual chase.** A beat that follows a walker who follows the beat drifts under any constant bias. That's why the
  tempo comes from cadence (FLL), plus the dead band and the learned offset (frozen while leading).
- **Foreground service:** type `mediaPlayback`, an ongoing silent notification (IMPORTANCE_LOW), a partial wake lock.
  Sensors register inside the service. Request `POST_NOTIFICATIONS` at the first Start. Verified working with the
  screen off.
- **Samsung battery:** set the app to Unrestricted and keep it out of Sleeping apps, or sensors stop at screen-off.
- `ACTIVITY_RECOGNITION` isn't needed for raw `TYPE_ACCELEROMETER`.
- **Mock data:** the portal's patients are fictional. Keep the "Demo data / fictional" labels.
- **Music is copyrighted:** songs live only in gitignored folders and the locally built APK. Never commit them, and
  don't share that APK publicly. The repo is public.
- **Music makes timing changes audible:** any change to per-beat corrections needs checking with Music, not just
  the click. Measure the beat-to-beat tempo change of `cue.audibleBeatFrameTimes` (see GaitCuePipelineTest).
