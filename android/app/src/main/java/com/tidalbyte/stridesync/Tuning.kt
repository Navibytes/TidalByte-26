package com.tidalbyte.stridesync

/**
 * Every tunable constant in the app lives here. Treat these values as starting guesses to tune on the
 * device. Each comment gives the units and the reason for the value.
 *
 * Pure Kotlin, so `gait/` and `cue/` can use it from JVM unit tests.
 */
object Tuning {

    // ───────────────────────── Sensor ─────────────────────────

    /** Requested accelerometer sampling period, µs. 5 ms = 200 Hz, the most allowed without the
     *  HIGH_SAMPLING_RATE_SENSORS permission on Android 12+. The S21's LSM6DSO reports minDelay 5000 µs.
     *  This gives finer peak timing, but the timing error on real walks came from where the phone sits
     *  (see the cadence section), not from the rate. */
    const val SENSOR_PERIOD_US = 5_000

    /** SimulatedWalker emits its samples in batches this often, ms. This mimics the batched delivery of
     *  real sensors, so the pipeline never depends on arrival time. */
    const val SIM_BATCH_MS = 40L

    // ───────────────────────── Step detection (gait/StepDetector) ─────────────────────────

    /** Rate of the detector's internal uniform resampling grid, Hz. Delivery of real samples is irregular,
     *  but the biquad coefficients assume a fixed rate. */
    const val DETECTOR_FS_HZ = 200.0

    /** Time constant of the moving mean that tracks gravity, s. It must be much longer than one slow
     *  Parkinson's step (up to ~1.5 s at 40 spm), or it starts eating the step signal. */
    const val GRAVITY_TAU_SEC = 2.0

    /** Low-pass cutoff, Hz. Keeps the energy at step rate (0.5–3 Hz) and the broad heel-strike bump.
     *  Removes jiggle and most rest/freeze tremor (4–8 Hz). */
    const val STEP_LOWPASS_HZ = 4.0

    /** A candidate peak must exceed this fraction of the recent peak envelope. Kept low because
     *  Parkinson's strikes are weak and asymmetric. In real front-pocket walks the far foot's peak was ~40%
     *  of the near foot's. At 0.4, a few weak ones dropped out, the envelope (a median) rose to the strong
     *  peaks, and detection locked into one step per stride, reading half cadence. At 0.3 it recovers. */
    const val STEP_THRESHOLD_FRAC = 0.3

    /** Absolute floor for the threshold, m/s² (filtered). Rejects sensor noise while the phone is still
     *  (resting S21 noise ≈ 0.01–0.02 m/s²). Shuffling strikes still filter to roughly 0.3 m/s². */
    const val STEP_MIN_PEAK = 0.07

    /** Number of recent accepted peak heights whose median forms the envelope. */
    const val STEP_ENVELOPE_PEAKS = 6

    /** Time constant at which the envelope decays after the last step, s. Lets the threshold relax when
     *  strikes get weaker, such as when the walker starts shuffling or restarts after a freeze. */
    const val STEP_ENVELOPE_DECAY_SEC = 4.0

    /** The decay stops at this fraction of the envelope. Without a floor, a long freeze lets the threshold
     *  sink until trembling-in-place (3–8 Hz, partly passed by the low-pass) counts as steps. Weak restart
     *  steps still clear 0.3 × 0.35 ≈ 10% of the old peak height. */
    const val STEP_ENVELOPE_DECAY_FLOOR = 0.35

    /** Minimum refractory period between steps, ms. 250 ms caps detection at 240 spm, which still
     *  admits festination (rapid, tiny steps). */
    const val STEP_REFRACTORY_MIN_MS = 250L

    /** The refractory period also scales with the recent step interval: this fraction of the 75th percentile
     *  of the last few intervals. This stops a push-off bump (about 20% of an interval after a strike) from
     *  double-counting at slow cadence. The 75th percentile, not the median, matters here: once one push-off
     *  slips through, the short/long alternation would drag a median down and let more push-offs through. */
    const val STEP_REFRACTORY_FRAC = 0.45

    /** Upper bound for the adaptive refractory period, ms. It's bounded so a bad interval estimate can't
     *  lock out real steps. */
    const val STEP_REFRACTORY_MAX_MS = 500L

    /** A gap in sensor timestamps longer than this resets the resampler instead of interpolating across
     *  it, ms. Covers dropouts such as a screen-off hiccup. */
    const val SENSOR_GAP_RESET_MS = 500L

    // ───────────────────────── Cadence (gait/CadenceEstimator) ─────────────────────────
    // Cadence and variability come from TWO-STEP intervals (t[k] − t[k−2], i.e. stride time), not from
    // single-step intervals. With the phone on one thigh (a front pocket), real walks showed the two feet's
    // detections alternating ~750/375 ms where true steps were ~560/560. A median of single intervals
    // jumped between the two clusters. A long + short pair always sums to one stride, so two-step
    // intervals cancel the alternation exactly. At the waist, where both feet read alike, it makes no
    // difference.

    /** spm = 120 / median of this many recent two-step intervals. 6 tolerates one missed or extra step
     *  (that corrupts only 2 of the 6). */
    const val CADENCE_WINDOW_STRIDES = 6

    /** A faster estimate for Follow mode: median of this many two-step intervals (~1.5 s of walking). */
    const val CADENCE_FAST_STRIDES = 3

    /** Number of two-step intervals used for the stride-time CV (consistency), the standard clinical
     *  variability measure. */
    const val CV_WINDOW_STRIDES = 10

    /** No cadence is reported until this many two-step intervals exist (4 steps). */
    const val CADENCE_MIN_STRIDES = 2

    /** Plausible inter-step interval range, ms. 250 ms = 240 spm (festination). 2000 ms = 30 spm, a very
     *  slow Parkinson's gait. Don't use a healthy-gait range. */
    const val STEP_INTERVAL_MIN_MS = 250L
    const val STEP_INTERVAL_MAX_MS = 2000L

    // ───────────────────────── Audio (audio/MetronomeEngine, audio/BeatClock) ─────────────────────────

    /** Output sample rate, Hz. 48 kHz is the S21's native mixer rate, so there's no resampling in the path. */
    const val AUDIO_SAMPLE_RATE = 48_000

    /** The writer thread renders this many frames per write. 240 frames = 5 ms, which is fine-grained
     *  enough that the write-ahead stays close to its target. */
    const val AUDIO_CHUNK_FRAMES = 240

    /** How far ahead of the playback head the writer keeps audio queued, ms. Small, so tempo and phase
     *  changes are heard quickly (a change affects only beats not yet written). Large enough to survive
     *  scheduling hiccups without underruns. */
    const val AUDIO_WRITE_AHEAD_MS = 100

    /** How far the detected step (the filtered peak) trails the true heel strike, ms. Measured as a near-
     *  constant ~63 ms in the synthetic detector tests (StepDetectorTest prints it). Real pocket data may
     *  differ, so calibrate it. */
    const val DETECTOR_LAG_MS = 63

    /** Residual latency from AudioTrack.getTimestamp()'s DAC presentation time to sound in the ear, ms.
     *  Small for the phone speaker or wired earbuds. Bluetooth would add 150–300 ms and vary, so don't use
     *  it. Starting guess; calibrate on device. */
    const val SPEAKER_LATENCY_MS = 12

    /** Added to the DAC presentation time to get a beat's comparison time, ms. The PLL compares beats
     *  against *detected* steps, which lag the heel strike by DETECTOR_LAG_MS, so the beat time gets the
     *  same lag plus the speaker latency. When the PLL zeroes its error, the heard beat coincides with the
     *  heel strike. */
    const val OUTPUT_LATENCY_MS = SPEAKER_LATENCY_MS + DETECTOR_LAG_MS

    /** Max change of the beat period per beat, as a fraction of the period. Stops the cue lurching when
     *  the target tempo jumps (for example 90 → 60 takes ~8 beats). */
    const val BEAT_MAX_PERIOD_CHANGE_PCT = 0.05

    /** Max phase shift applied to a single beat, as a fraction of the period. */
    const val BEAT_MAX_PHASE_SHIFT_PCT = 0.05

    /** Sanity guard on any tempo sent to the engine, bpm. Rejects garbage (NaN, 0, absurd values). The
     *  therapeutic ramp caps live in RampController, not here. */
    const val ENGINE_MIN_BPM = 20.0
    const val ENGINE_MAX_BPM = 240.0

    /** Cue tempo when no cadence is known yet, bpm. */
    const val CUE_DEFAULT_BPM = 90.0

    /** Output gain applied to the cue sounds, 0..1. Leaves headroom below full scale. */
    const val CUE_GAIN = 0.8f

    // ───────────────────────── Phase lock (cue/PhaseLockedLoop) ─────────────────────────
    // Per step: error = step time − nearest beat time (wrapped to ±period/2), averaged with the previous
    // step's error to cancel left/right alternation. The upcoming beat shifts by kP × (error outside the
    // dead band), clamped per beat (BEAT_MAX_PHASE_SHIFT_PCT).
    //
    // Tempo is NOT integrated from phase error. While the beat follows the walker (LOCKING, Follow mode),
    // the tempo is set from the measured cadence (a frequency-locked loop). Why: a real walker also follows
    // the beat. A constant measurement bias (a latency offset that's slightly wrong, or the pocket
    // artifact) then makes the two chase each other, and an integrating PLL lets the tempo drift without
    // bound. Cadence is bias-free, so the tempo stays anchored to what the walker actually does.

    /** Phase coupling while the beat follows the walker. Errors inside the dead band are left for the walker
     *  to correct; humans are good at that. */
    const val PLL_LOCK_KP = 0.5
    const val PLL_LOCK_KI = 0.0

    /** Weak phase coupling while leading (ramping, holding): the beat keeps its tempo and the walker has to
     *  come to it. Keep kP small: a persistent error outside the dead band makes each nudge lengthen the
     *  beat, so the walker hears a slower cue than commanded. */
    const val PLL_LEAD_KP = 0.1
    const val PLL_LEAD_KI = 0.0

    /** Phase errors within ±this get no correction, ms. This stops a small constant bias from making beat
     *  and walker chase each other. It sits well inside the ±60 ms sync window. */
    const val PLL_PHASE_DEADBAND_MS = 25

    /** Coupling while acquiring the initial lock with the cue muted. Nothing is audible, so the per-beat
     *  clamps and the dead band are lifted, and the phase snaps in within a few steps. */
    const val PLL_ACQUIRE_KP = 0.7
    const val PLL_ACQUIRE_KI = 0.0

    /** Consecutive in-window steps needed before a muted, acquiring cue becomes audible. */
    const val PLL_ACQUIRE_STEPS = 2

    /** A beat-follows-walker tempo update is skipped if it changes the period by less than this fraction.
     *  It avoids audible micro-jitter from cadence-estimate noise. */
    const val FLL_MIN_UPDATE_PCT = 0.003

    /** Per step, the beat-follows-walker tempo closes this fraction of the gap to the measured cadence.
     *  Raw cadence estimates jitter ±1–2% step to step. Followed directly, that jitter drags an entraining
     *  walker around, and the pair wanders. 0.5 halves the gap each step, so a real change is ~90% matched
     *  in 3 steps. */
    const val FLL_GAIN = 0.5

    // ───────────────────────── Follow mode (demo: the beat always matches the walker) ─────────────────────────

    /** Follow mode needs only enough steps for a first cadence estimate. */
    const val FOLLOW_CALIBRATION_STEPS = 4

    /** Follow mode tracks the steady cadence estimate, but switches to the fast one (and follows it
     *  directly) when the two disagree by more than this fraction in the same direction for
     *  FOLLOW_FAST_SWITCH_STEPS consecutive steps, i.e. a real speed change. A single odd interval (a missed
     *  or extra detection, common on real walks) doesn't trigger it; in real-data replays that kind of
     *  one-step jump knocked the phase out. */
    const val FOLLOW_FAST_SWITCH_PCT = 0.04
    const val FOLLOW_FAST_SWITCH_STEPS = 2

    /** Follow mode re-sync: if the (pair-averaged) error exceeds this fraction of the period, in the same
     *  direction, for FOLLOW_RESYNC_STEPS consecutive steps, the next beat jumps straight onto the steps
     *  instead of creeping back 10% per beat. On real walks, turns and pace changes left the beat ±200 ms
     *  off for 4–5 steps. */
    const val FOLLOW_RESYNC_FRAC = 0.25
    const val FOLLOW_RESYNC_STEPS = 2

    /** In Follow mode, a single beat may shift up to this fraction of the period for phase correction (ramp
     *  mode keeps BEAT_MAX_PHASE_SHIFT_PCT). Recovers a 250 ms misalignment in ~5 steps instead of ~10. */
    const val FOLLOW_MAX_PHASE_SHIFT_PCT = 0.10

    /** In Follow mode, the beat's tempo may change up to this fraction per beat (ramp mode keeps the spec's
     *  BEAT_MAX_PERIOD_CHANGE_PCT). 10% matches a 100→120 spm change in ~2 beats. Fine for a demo of
     *  tracking; too abrupt for therapy. */
    const val FOLLOW_MAX_PERIOD_CHANGE_PCT = 0.10

    // ───────────────────────── Sync metrics (cue/SyncTracker) ─────────────────────────

    /** A step counts as "in sync" if |step − nearest beat| ≤ max(SYNC_WINDOW_MS, SYNC_WINDOW_PERIOD_PCT ×
     *  beat period). The window scales with tempo because slow Parkinson's gait has proportionally larger
     *  step-to-step jitter. At a 5% interval CV and 62 spm, steps wander ±48 ms by themselves, so a fixed
     *  ±60 ms caps even a perfectly following beat near 79% (chosen with the user, 2026-11-21).
     *  ±60 ms floor at ≥ 80 bpm; ±80 ms at 60 bpm. */
    const val SYNC_WINDOW_MS = 60
    const val SYNC_WINDOW_PERIOD_PCT = 0.08

    /** The fraction of recent steps that must be in sync for the walker to count as synced. This gates
     *  LOCKING → RAMPING and every ramp step. */
    const val SYNC_REQUIRED_PCT = 0.8

    /** Below this fraction of in-sync recent steps, sync is lost and the ramp steps back. Between this and
     *  SYNC_REQUIRED_PCT, sync is "marginal": hold, don't advance. */
    const val SYNC_LOST_PCT = 0.5

    /** A persistent step-vs-beat offset is learned (EMA over this many steps, while audible and the beat is
     *  following, i.e. LOCKING / Follow; frozen while it leads) and removed
     *  before phase correction and sync judgment. Why: the exact latency (DETECTOR_LAG_MS + speaker) and
     *  placement effects (the pocket artifact biases errors by up to ~85 ms) are unknown constants. Chasing a
     *  constant bias makes walker and beat drift together, and it would mark a walker who steps
     *  consistently on the beat as "out of sync". Deviations from the learned offset still get corrected
     *  and judged. */
    const val SYNC_OFFSET_TAU_STEPS = 8.0

    /** The learned offset is clamped to ±this, ms. Beyond it, misalignment is real and gets judged. */
    const val SYNC_OFFSET_MAX_MS = 100

    /** The offset learns only from steps within ±this of the current estimate, ms. Disruptions (turns,
     *  stops, a burst of missed steps) mustn't drag it. On real walks they pulled it 150 ms in a few steps. */
    const val SYNC_OFFSET_GATE_MS = 120

    /** The rolling sync metrics cover this many recent steps. Sync judgments wait for a full window. */
    const val SYNC_ROLLING_STEPS = 16

    // ───────────────────────── Ramp (cue/RampController) ─────────────────────────
    // CALIBRATING → LOCKING → RAMPING ⇄ HOLDING, plus STEPPING_BACK and PAUSED. The hard caps are
    // enforced only in RampController.capBpm(), which every tempo sent to the metronome passes through.

    /** Steps measured (with the cue silent) to establish the baseline cadence. */
    const val CALIBRATION_STEPS = 20

    /** Minimum time with an audible, locked cue before ramping may start, s. That plus a full in-sync
     *  rolling window is the "sustained period in sync". */
    const val LOCK_MIN_SEC = 20.0

    /** One ramp step raises the tempo by at most this fraction of the previous tempo. A HARD CAP. */
    const val RAMP_STEP_PCT = 0.02

    /** Each ramp step is held this long before the next may happen (only if still in sync), s. Holding
     *  also waits this long in good sync before resuming the ramp. */
    const val RAMP_HOLD_SEC = 45.0

    /** Tempo never exceeds baseline × (1 + MAX_RAMP_PCT). A HARD CAP. */
    const val MAX_RAMP_PCT = 0.10

    /** An absolute tempo ceiling, bpm, whatever the baseline. A HARD CAP. Brisk healthy walking is
     *  ~110–120 spm; cueing a Parkinson's walker above 130 has no plausible benefit. */
    const val ABS_MAX_CUE_BPM = 130.0

    /** After a step back, the lower tempo is held this long (sync not judged) before HOLDING, s. About 2
     *  entrainment time constants (~10 s each): the walker needs this long to re-adapt from being pushed.
     *  At 10 s, the re-adaptation steps were judged as "sync lost" again, and the ramp cascaded down. */
    const val STEP_BACK_SETTLE_SEC = 20.0

    /** No steps for this long means PAUSED (a possible freeze or stop): the tempo freezes and never
     *  increases, and the beat keeps playing to help a restart, s. */
    const val PAUSE_SEC = 3.0

    /** If measured cadence falls this fraction below the cue tempo while ramping/holding, step back at once. */
    const val CADENCE_DROP_PCT = 0.15

    // ───────────────────────── Demo timing (RampTiming.DEMO) ─────────────────────────
    // The whole calibrate → lock → ramp → overreach → step-back cycle fits in about a minute. The clinical
    // values above stay the defaults. Caps are unchanged.
    const val DEMO_CALIBRATION_STEPS = 8
    const val DEMO_LOCK_MIN_SEC = 8.0
    const val DEMO_RAMP_HOLD_SEC = 10.0
    const val DEMO_STEP_BACK_SETTLE_SEC = 6.0
    const val DEMO_SYNC_ROLLING_STEPS = 8
}
