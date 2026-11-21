package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.sensor.AccelSample
import kotlin.math.exp
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Detects heel strikes in raw accelerometer samples. Pure Kotlin and deterministic.
 *
 * The pipeline per sample:
 *  1. Magnitude √(x²+y²+z²), so phone orientation doesn't matter.
 *  2. Linear resampling onto a uniform grid at [Tuning.DETECTOR_FS_HZ]. Real delivery is irregular and
 *     batched; timestamps are trusted, arrival times are not.
 *  3. Gravity removal by subtracting a moving mean (EMA, [Tuning.GRAVITY_TAU_SEC]).
 *  4. Biquad Butterworth low-pass at [Tuning.STEP_LOWPASS_HZ].
 *  5. Local-maximum peak picking with parabolic sub-sample timing.
 *  6. An adaptive threshold: [Tuning.STEP_THRESHOLD_FRAC] × the median of recent peak heights. It decays
 *     after the last step (but only down to [Tuning.STEP_ENVELOPE_DECAY_FLOOR]) and never goes below
 *     [Tuning.STEP_MIN_PEAK].
 *  7. A refractory period. A candidate is held as *pending* and is confirmed only after the refractory
 *     period passes without a higher peak, so a push-off bump can't steal the step. As a result, steps
 *     are reported up to [Tuning.STEP_REFRACTORY_MAX_MS] late, but they carry their true peak timestamp.
 */
class StepDetector(private val fsHz: Double = Tuning.DETECTOR_FS_HZ) {

    private val gridNs = (1e9 / fsHz).roundToLong()
    private val lowPass = Biquad.lowPass(Tuning.STEP_LOWPASS_HZ, fsHz)
    private val gravityAlpha = 1.0 - exp(-1.0 / (fsHz * Tuning.GRAVITY_TAU_SEC))
    private val gapNs = Tuning.SENSOR_GAP_RESET_MS * 1_000_000
    private val intervalMinNs = Tuning.STEP_INTERVAL_MIN_MS * 1_000_000
    private val intervalMaxNs = Tuning.STEP_INTERVAL_MAX_MS * 1_000_000

    // Resampler state.
    private var hasPrev = false
    private var prevT = 0L
    private var prevMag = 0.0
    private var nextGridT = 0L

    // Filter state.
    private var gravity = Double.NaN
    private var y1 = 0.0 // filtered value one grid step ago
    private var y2 = 0.0 // two grid steps ago
    private var historyLen = 0

    // Peak/step state.
    private var hasPending = false
    private var pendingT = 0L
    private var pendingH = 0.0
    private var hasLastStep = false
    private var lastStepT = 0L
    private val recentHeights = ArrayDeque<Double>()
    private val recentIntervals = ArrayDeque<Long>()

    /** The latest filtered (gravity-removed, low-passed) value, m/s². Used by the Debug trace. */
    var lastFiltered: Double = 0.0
        private set

    /** The threshold at the latest grid point, m/s². Used by the Debug trace. */
    var lastThreshold: Double = Tuning.STEP_MIN_PEAK
        private set

    /** Feed one raw sample. Returns any steps confirmed by it (usually none, at most one in practice). */
    fun process(s: AccelSample): List<StepEvent> {
        val x = s.x.toDouble()
        val y = s.y.toDouble()
        val z = s.z.toDouble()
        val mag = sqrt(x * x + y * y + z * z)

        if (!hasPrev) {
            hasPrev = true
            prevT = s.tNanos
            prevMag = mag
            nextGridT = s.tNanos
        } else if (s.tNanos <= prevT) {
            return emptyList() // duplicate or out-of-order timestamp
        } else if (s.tNanos - prevT > gapNs) {
            // Don't interpolate across a dropout. Restart the grid, and drop the peak history so the
            // discontinuity can't create a fake peak.
            prevT = s.tNanos
            prevMag = mag
            nextGridT = s.tNanos
            historyLen = 0
        }

        var out: MutableList<StepEvent>? = null
        while (nextGridT <= s.tNanos) {
            val span = s.tNanos - prevT
            val frac = if (span == 0L) 1.0 else (nextGridT - prevT).toDouble() / span
            val m = prevMag + frac * (mag - prevMag)
            val ev = onGridSample(nextGridT, m)
            if (ev != null) (out ?: ArrayList<StepEvent>(1).also { out = it }).add(ev)
            nextGridT += gridNs
        }
        prevT = s.tNanos
        prevMag = mag
        return out ?: emptyList()
    }

    private fun onGridSample(t: Long, mag: Double): StepEvent? {
        if (gravity.isNaN()) gravity = mag
        gravity += gravityAlpha * (mag - gravity)
        val yNow = lowPass.process(mag - gravity)
        lastFiltered = yNow
        lastThreshold = threshold(t)

        var confirmed: StepEvent? = null

        // A local maximum at the previous grid point.
        if (historyLen >= 2 && y1 > y2 && y1 >= yNow) {
            val denom = y2 - 2.0 * y1 + yNow
            val delta = if (denom < 0.0) (0.5 * (y2 - yNow) / denom).coerceIn(-0.5, 0.5) else 0.0
            val tPeak = t - gridNs + (delta * gridNs).roundToLong()
            val hPeak = y1 - 0.25 * (y2 - yNow) * delta
            confirmed = onPeak(tPeak, hPeak)
        }

        if (hasPending && t - pendingT >= refractoryNs()) {
            confirmed = confirmPending()
        }

        y2 = y1
        y1 = yNow
        if (historyLen < 2) historyLen++
        return confirmed
    }

    /** Handles a candidate peak. Returns a step if this peak forced the previous pending one to confirm. */
    private fun onPeak(t: Long, h: Double): StepEvent? {
        if (h < threshold(t)) return null
        var confirmed: StepEvent? = null
        if (hasPending) {
            if (t - pendingT < refractoryNs()) {
                if (h > pendingH) {
                    pendingT = t
                    pendingH = h
                }
                return null
            }
            confirmed = confirmPending()
        }
        hasPending = true
        pendingT = t
        pendingH = h
        return confirmed
    }

    private fun confirmPending(): StepEvent {
        hasPending = false
        if (hasLastStep) {
            val interval = pendingT - lastStepT
            if (interval in intervalMinNs..intervalMaxNs) {
                recentIntervals.addLast(interval)
                if (recentIntervals.size > 5) recentIntervals.removeFirst()
            }
        }
        hasLastStep = true
        lastStepT = pendingT
        recentHeights.addLast(pendingH)
        if (recentHeights.size > Tuning.STEP_ENVELOPE_PEAKS) recentHeights.removeFirst()
        return StepEvent(pendingT, pendingH)
    }

    private fun threshold(t: Long): Double {
        if (recentHeights.isEmpty()) return Tuning.STEP_MIN_PEAK
        val env = medianD(recentHeights)
        val sinceSec = if (hasLastStep) (t - lastStepT).coerceAtLeast(0L) / 1e9 else 0.0
        val decay = exp(-sinceSec / Tuning.STEP_ENVELOPE_DECAY_SEC).coerceAtLeast(Tuning.STEP_ENVELOPE_DECAY_FLOOR)
        val decayed = env * decay
        return maxOf(Tuning.STEP_MIN_PEAK, Tuning.STEP_THRESHOLD_FRAC * decayed)
    }

    private fun refractoryNs(): Long {
        val minNs = Tuning.STEP_REFRACTORY_MIN_MS * 1_000_000
        if (recentIntervals.isEmpty()) return minNs
        val sorted = recentIntervals.sorted()
        val p75 = sorted[(sorted.size * 3) / 4]
        val adaptive = (Tuning.STEP_REFRACTORY_FRAC * p75).roundToLong()
        return adaptive.coerceIn(minNs, Tuning.STEP_REFRACTORY_MAX_MS * 1_000_000)
    }
}
