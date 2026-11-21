# StrideSync — CLAUDE.md

## What this is
StrideSync is an Android app for Parkinson's gait training using rhythmic auditory cueing. The phone's accelerometer
(in a pocket or on a belt clip) detects heel strikes. A generated metronome/beat locks onto the walker's cadence and
phase. Then it slowly raises the tempo to lengthen the stride. Session results sync to Firebase, and a therapist web
portal shows cadence, consistency and trends.

**Context: 24-hour solo hackathon prototype.** It will not be used with real patients. So:
- Optimize for a working end-to-end demo over polish, completeness or architecture purity.
- Ignore HIPAA, PHI handling, regulatory and medical-device concerns. Firestore test-mode rules are fine.
- Show one line in the app's UI: "Prototype — not a medical device."
- Don't add features beyond the milestones below without asking.

## Stack
- **Android:** Kotlin, Jetpack Compose, coroutines/Flow, `SensorManager`, `AudioTrack`. Target device is a Samsung
  Galaxy S21 (One UI). minSdk 29, target/compile the latest stable SDK installed.
- **Backend:** Firebase: Anonymous Auth, Firestore (offline persistence on), Hosting.
- **Portal:** Static site on Firebase Hosting: Vite + vanilla TypeScript + Chart.js + Firebase JS SDK.
  (Claude picked this for speed. Swap it if you prefer React.)
- **No:** ExoPlayer/Media3, Rubber Band, SoundTouch, time-stretching, streaming services, Hilt/Dagger (wire things
  by hand), or Room (unless needed for a session queue).

The audio is a **synthesized metronome/beat**, not music. That means tempo and phase are fully controllable,
there are no stretch artifacts, and beats can be placed sample-accurately. Ignore the time-stretching and
song-switching parts of any older design notes.

## Repo layout
```
android/                     Android Studio project
  app/src/main/java/.../stridesync/
    sensor/    SensorSource interface; LiveAccelSource, ReplaySource (CSV), SimulatedWalker
    gait/      StepDetector, CadenceEstimator, GaitStats        ← pure Kotlin, no android.* imports
    cue/       PhaseLockedLoop, RampController, CueState        ← pure Kotlin, no android.* imports
    audio/     MetronomeEngine (AudioTrack), ClickSynth
    session/   SessionController (wires everything), SessionRecorder, SessionSummary
    service/   CueService (foreground service)
    data/      FirestoreRepo
    ui/        Compose screens: Home, LiveSession, Summary, Debug
    Tuning.kt  every tunable constant lives here, documented
  app/src/test/   JVM unit tests for gait/ and cue/
  app/src/main/assets/replays/   recorded/synthetic accelerometer CSVs
portal/                      therapist web portal
firebase/                    firebase.json, firestore.rules, firestore.indexes.json
tools/                       seed_portal.mjs (fake patient history), gen_gait.py (optional)
```

## Commands
```bash
# Android (from android/)
./gradlew assembleDebug
./gradlew installDebug
./gradlew testDebugUnitTest
adb logcat -s StrideSync:*            # all app logs use tag prefix "StrideSync"
adb pull /sdcard/Android/data/<pkg>/files/recordings/ ./recordings/

# Portal (from portal/)
npm install && npm run dev
npm run build

# Firebase (from repo root)
firebase deploy --only hosting,firestore
node tools/seed_portal.mjs            # writes demo patients + ~3 weeks of sessions
```

## Pipeline (data flow)
```
SensorSource ──samples──▶ StepDetector ──step times──▶ CadenceEstimator ──spm, CV──┐
                                    │                                               ▼
                                    └──step times──▶ PhaseLockedLoop ◀── RampController (target tempo)
                                                           │
                                                           ▼ beat period + phase
                                                    MetronomeEngine (AudioTrack)
SessionRecorder taps every stage → SessionSummary → FirestoreRepo (uploaded at session end)
```
`SessionController` owns the pipeline. `CueService` hosts it so it survives screen-off. The UI observes a
`StateFlow<LiveState>`.

### 1. Sensor input — `sensor/`
- `SensorSource` emits `AccelSample(tNanos, x, y, z)` as a Flow. All three sources go through the same
  pipeline, which is what makes replay and simulation trustworthy.
- `LiveAccelSource`: `TYPE_ACCELEROMETER` at `samplingPeriodUs = 10_000` (~100 Hz; under the Android 12+ 200 Hz cap
  so no extra permission). Use `event.timestamp`, never arrival time. Delivery is irregular and batched.
- `ReplaySource`: plays a CSV (`t_ns,ax,ay,az`) from assets in real time, or faster in tests.
- `SimulatedWalker`: synthesizes a gait signal (configurable cadence, amplitude, noise, drift, and freeze
  episodes). It **partially entrains to the cue**: its cadence moves toward the current beat tempo with a time
  constant of ~10 s and some phase jitter, so the full lock→ramp behavior can be demoed at a desk.
- The Debug screen has a "Record" toggle that writes live samples to CSV for later replay.

### 2. Step detection — `gait/StepDetector`
- Use the magnitude √(x²+y²+z²) to reduce orientation dependence. Remove gravity with a moving mean or
  high-pass, then low-pass at ~4 Hz (biquad).
- Peak detection with an adaptive threshold (a fraction of the recent peak amplitude) and a refractory period.
- **Parkinson's gait is slow, shuffling and low-amplitude.** Thresholds must handle weak heel strikes, and
  cadence may be well under typical walking values. Don't hard-code healthy-gait assumptions. Test with
  low-amplitude synthetic signals.
- The detected peak lags the true heel strike by a roughly constant amount. Absorb that in the latency offset
  (see Gotchas) rather than trying to model it.

### 3. Cadence — `gait/CadenceEstimator`
- spm = 60 / median(last N inter-step intervals), N ≈ 8. Reject intervals outside a plausible range.
- Consistency = coefficient of variation (CV) of the intervals. Report it. It's a key therapist metric.
- FFT cross-checking is optional and out of scope unless step detection proves unreliable.

### 4. Phase lock — `cue/PhaseLockedLoop`
- For each step, phase error = step time − nearest beat time, wrapped to ±period/2.
- PI-style correction: shift upcoming beats by `kP·error` and nudge the period by `kI·error`. Clamp both per beat
  (e.g., ≤5% of the period) so the beat never lurches.
- **Coupling strength depends on state.** Use strong coupling while locking, because the beat follows the
  walker. Use weak coupling while ramping, because the beat must *lead* the walker. If it keeps following, it
  can't pull cadence up.
- Sync metrics: mean absolute asynchrony (ms), and the % of steps within ±`Tuning.SYNC_WINDOW_MS`.

### 5. Ramp — `cue/RampController` (automatic, with hard caps)
State machine. Log every transition, and show it in the UI and the session summary.
- `CALIBRATING`: no cue (or a soft cue). Measure baseline cadence over the first ~20 steps.
- `LOCKING`: cue at baseline, strong phase coupling. Go to RAMPING after a sustained period in sync.
- `RAMPING`: raise the tempo by `RAMP_STEP_PCT` per step, holding each step for `RAMP_HOLD_SEC`. Only advance
  if still in sync.
- `HOLDING`: at the cap, or while sync is marginal.
- `STEPPING_BACK`: sync lost → drop one ramp step and return to HOLDING.
- `PAUSED`: no steps for ~3 s (a possible freeze or stop) → freeze the tempo and **never** increase it. Keep
  the beat playing steadily, because cues can help a walker restart. Resume HOLDING when steps return.
- **Hard caps, enforced in one place and unit-tested:** max tempo = baseline × (1 + `MAX_RAMP_PCT`), plus an
  absolute ceiling. Ramp increments can never exceed `RAMP_STEP_PCT`. A sudden cadence drop (>~15%) also
  triggers STEPPING_BACK.

### 6. Audio — `audio/MetronomeEngine`
- `AudioTrack`, 48 kHz mono 16-bit PCM, `MODE_STREAM`, `USAGE_MEDIA`, with a dedicated writer thread.
- Beats are placed at exact sample frames. `ClickSynth` precomputes 2–3 sounds (a click, a woodblock, a soft
  kick) as short enveloped bursts.
- Keep write-ahead small (~100 ms) so tempo and phase changes apply quickly. Changes only affect beats not yet
  written.
- Expose `beatTimes` (predicted and actual, in the shared timebase) so the PLL can compare them against steps.

### 7. Session + reporting
- `SessionSummary`: start, duration, baseline/final/peak spm, mean CV, sync %, mean asynchrony, ramp-step count,
  state-transition events, and a downsampled series (one point per 5 s: `t, spm, cueBpm, asyncMs, cv, state`).
- Upload once at session end. Firestore offline persistence handles bad connectivity.

## Firestore schema
```
patients/{patientId}                    { displayName, createdAt, latestBaselineSpm }
patients/{patientId}/sessions/{id}      { startedAt, durationSec, baselineSpm, finalSpm, peakSpm,
                                          meanCv, syncPct, meanAsyncMs, rampSteps,
                                          events: [{t, from, to, reason}],
                                          series: [{t, spm, cueBpm, asyncMs, cv, state}] }
```
Keep session docs well under the 1 MB limit (the 5 s downsampling handles this). The app picks the patient
from a dropdown. There's no real therapist auth: the portal reads everything.

## Portal
- Patient list → patient page with trend charts across sessions (baseline vs final cadence, CV, sync %).
- Session detail: cadence and cue tempo over time on one chart, with state bands shaded.
- `tools/seed_portal.mjs` generates believable history: ~15 sessions over 3 weeks with gradual improvement,
  noise, and a bad day or two.

## Demo plan (both modes must work)
1. **Replay/sim mode (primary):** the Home screen has a source picker (Live / Replay file / Simulated walker).
   The simulated walker shows lock → ramp → step-back live, and the audio and UI behave exactly as in live mode.
2. **Live:** walk with the phone in a pocket. Use the phone speaker or wired earbuds, not Bluetooth.
3. Finish on the portal, showing the session just uploaded alongside the seeded history.

## Milestones (build in this order; each must run before starting the next)
1. `SimulatedWalker` + `StepDetector` + `CadenceEstimator`, with unit tests and spm on screen.
2. `MetronomeEngine` at a fixed BPM, then following measured cadence.
3. `PhaseLockedLoop` and sync metrics.
4. `RampController` with caps and tests. Entraining simulated walker.
5. `CueService` foreground service + live sensor + record-to-CSV. Test on the S21 with the screen off.
6. `SessionSummary` → Firestore.
7. Portal + seed script, deployed to Hosting.
8. Demo polish: big Start/Stop, clear state label, latency calibration.

**Cut list if behind (cut in this order):** latency-calibration UI (hard-code it instead) → multiple click
sounds → portal session-detail page → screen-off support. Never cut: replay/sim mode or ramp caps.

## Coding conventions
- `gait/` and `cue/` stay free of Android imports. They take timestamps as `Long` nanos, which makes them
  fully JVM-testable and deterministic.
- All tunables live in `Tuning.kt` with a comment giving units and rationale. They're starting guesses meant to
  be tuned, so don't scatter magic numbers. Starting values: `RAMP_STEP_PCT = 0.02`, `RAMP_HOLD_SEC = 45`,
  `MAX_RAMP_PCT = 0.10`, `SYNC_WINDOW_MS = 60`, `SYNC_REQUIRED_PCT = 0.8`.
- Tests: JVM unit tests for detector accuracy (step times within ±20 ms on synthetic data), cadence, PLL
  convergence, and every RampController transition and cap. No instrumentation tests.
- Prefer small, working increments. After each milestone, run `testDebugUnitTest` and `assembleDebug`.
- Log state transitions and per-step phase error at debug level under the `StrideSync` tag prefix.

## Gotchas
- **Two clocks.** `SensorEvent.timestamp` uses the elapsedRealtime (boot-time) base. `AudioTrack.getTimestamp()`
  returns `nanoTime` (monotonic). Convert everything to elapsedRealtime nanos with an offset sampled once at
  session start: `SystemClock.elapsedRealtimeNanos() - System.nanoTime()`. Mixing the two breaks phase lock.
- **Output latency.** Beats are heard after they're written. Compute played-frame time from `getTimestamp()`,
  then add `Tuning.OUTPUT_LATENCY_MS`, which also covers the peak-vs-heel-strike lag. Bluetooth adds roughly
  150–300 ms and varies, so use the speaker or wired audio for the demo. Optional: a "tap along" calibration.
- **Foreground service:** type `mediaPlayback` with an ongoing notification. Needs `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, and `POST_NOTIFICATIONS` (runtime request, Android 13+). Start sensors
  inside the service, not the Activity.
- **Samsung battery management:** set the app to Unrestricted battery use and keep it out of Sleeping/Deep
  sleeping apps (Settings → Battery → Background usage limits). Otherwise sensors stop at screen-off.
- `ACTIVITY_RECOGNITION` isn't needed for raw `TYPE_ACCELEROMETER`. Only request it if you use
  `TYPE_STEP_DETECTOR` as a cross-check.
- Phone placement changes the signal a lot. Test both pocket and belt clip, and record a replay CSV of each.
