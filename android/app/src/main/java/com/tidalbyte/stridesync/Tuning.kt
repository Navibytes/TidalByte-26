package com.tidalbyte.stridesync

/**
 * Every tunable constant in the app lives here. Treat these values as starting guesses to tune on the
 * device. Each comment gives the units and the reason for the value.
 *
 * Pure Kotlin, so `gait/` and `cue/` can use it from JVM unit tests.
 */
object Tuning {

    // ───────────────────────── Sensor ─────────────────────────

    /** Requested accelerometer sampling period, µs. 10 ms ≈ 100 Hz, which is under the Android 12+ 200 Hz cap,
     *  so no HIGH_SAMPLING_RATE_SENSORS permission is needed. */
    const val SENSOR_PERIOD_US = 10_000

    /** SimulatedWalker emits its samples in batches this often, ms. This mimics the batched delivery of
     *  real sensors, so the pipeline never depends on arrival time. */
    const val SIM_BATCH_MS = 40L

    // ───────────────────────── Step detection (gait/StepDetector) ─────────────────────────

    /** Rate of the detector's internal uniform resampling grid, Hz. Delivery of real samples is irregular,
     *  but the biquad coefficients assume a fixed rate. */
    const val DETECTOR_FS_HZ = 100.0

    /** Time constant of the moving mean that tracks gravity, s. It must be much longer than one slow
     *  Parkinson's step (up to ~1.5 s at 40 spm), or it starts eating the step signal. */
    const val GRAVITY_TAU_SEC = 2.0

    /** Low-pass cutoff, Hz. Keeps the energy at step rate (0.5–3 Hz) and the broad heel-strike bump.
     *  Removes jiggle and most rest/freeze tremor (4–8 Hz). */
    const val STEP_LOWPASS_HZ = 4.0

    /** A candidate peak must exceed this fraction of the recent peak envelope. Kept low because
     *  Parkinson's strikes are weak and asymmetric (the far-side foot reads weaker in a pocket). */
    const val STEP_THRESHOLD_FRAC = 0.4

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
     *  steps still clear 0.4 × 0.25 = 10% of the old peak height. */
    const val STEP_ENVELOPE_DECAY_FLOOR = 0.25

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

    /** spm = 60 / median of this many recent inter-step intervals. 8 smooths jitter without lagging much. */
    const val CADENCE_WINDOW = 8

    /** Number of intervals used for the CV (consistency) metric. Longer than CADENCE_WINDOW because CV
     *  from very few intervals is noisy. */
    const val CV_WINDOW = 12

    /** No cadence is reported until this many valid intervals exist. */
    const val CADENCE_MIN_INTERVALS = 3

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

    /** Added to the DAC presentation time from AudioTrack.getTimestamp() to get the time a beat is
     *  "heard", ms. This also absorbs the step detector's constant peak-vs-heel-strike lag (~63 ms in the
     *  synthetic tests), because the PLL compares beat times against detected-step times. Starting guess:
     *  63 ms lag + ~12 ms residual speaker latency. Calibrate on device. Bluetooth would add 150–300 ms,
     *  so don't use it. */
    const val OUTPUT_LATENCY_MS = 75

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
    // Per step: error = step time − nearest beat time (wrapped to ±period/2). The upcoming beat shifts by
    // kP·error and the period changes by kI·error, each clamped per beat (BEAT_MAX_*_PCT). For a 1:1
    // step/beat loop, kI = kP²/4 is critically damped: the error decays by a factor (1 − kP/2) per step.

    /** Strong coupling while locking: the beat follows the walker. 0.7 → error ×0.65 per step. A walker's
     *  unaided step timing drifts like a random walk, so following closely beats smoothing. In closed-loop
     *  tests, 0.5 lagged cadence drift, and 0.8 started ringing (steps are reported up to ~0.3 s late).
     *  kI = kP²/4 (critical damping). */
    const val PLL_LOCK_KP = 0.7
    const val PLL_LOCK_KI = 0.1225

    /** Weak coupling while leading (ramping, or a fixed tempo): a gentle phase nudge and no period change,
     *  so the beat keeps its tempo and the walker has to come to it. If the walker doesn't entrain, the
     *  steady-state error ≈ tempo mismatch / kP, which shows up as lost sync. That's the signal to step back. */
    const val PLL_LEAD_KP = 0.15
    const val PLL_LEAD_KI = 0.0

    /** Coupling while acquiring the initial lock with the cue muted. Nothing is audible, so the per-beat
     *  clamps are lifted and the phase snaps in within a few steps. */
    const val PLL_ACQUIRE_KP = 0.7
    const val PLL_ACQUIRE_KI = 0.0

    /** Consecutive in-window steps needed before a muted, acquiring cue becomes audible. */
    const val PLL_ACQUIRE_STEPS = 2

    /** Lock mode only: if the cue tempo and the measured cadence disagree by more than this fraction, the
     *  tempo jumps straight to the cadence (still slew-limited per beat) instead of waiting for the slow
     *  integrator. A PLL can't pull in a large frequency error by itself. 6% is about 4× the noise of the
     *  median-of-8 cadence estimate at a 3% interval CV. */
    const val PLL_FREQ_RESET_PCT = 0.06

    // ───────────────────────── Sync metrics (cue/SyncTracker) ─────────────────────────

    /** A step counts as "in sync" if |step − nearest beat| ≤ this, ms. */
    const val SYNC_WINDOW_MS = 60

    /** The fraction of recent steps that must be in sync for the walker to count as synced (used by the
     *  ramp in M4). */
    const val SYNC_REQUIRED_PCT = 0.8

    /** The rolling sync metrics cover this many recent steps. */
    const val SYNC_ROLLING_STEPS = 16
}
