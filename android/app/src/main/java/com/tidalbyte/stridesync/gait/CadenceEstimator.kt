package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.Tuning

/**
 * Turns step times into cadence (spm) and consistency (CV of inter-step intervals). Pure Kotlin.
 *
 * - spm = 60 / median of the last [Tuning.CADENCE_WINDOW] valid intervals. The median shrugs off a single
 *   missed or extra step.
 * - CV = sample SD / mean over the last [Tuning.CV_WINDOW] valid intervals. This is a key therapist metric.
 * - Intervals outside [Tuning.STEP_INTERVAL_MIN_MS, Tuning.STEP_INTERVAL_MAX_MS] are rejected:
 *   - Too short (a double detection): the step is ignored entirely, so it can't shorten the next interval.
 *   - Too long (a pause or freeze): no interval is recorded, and counting restarts from this step.
 */
class CadenceEstimator {
    private val minNs = Tuning.STEP_INTERVAL_MIN_MS * 1_000_000
    private val maxNs = Tuning.STEP_INTERVAL_MAX_MS * 1_000_000
    private val capacity = maxOf(Tuning.CADENCE_WINDOW, Tuning.CV_WINDOW)

    private val intervals = ArrayDeque<Long>()
    private var lastStepT: Long? = null
    private var stepCount = 0

    /** Records a step. Returns false if it was rejected as a double detection. */
    fun addStep(tNanos: Long): Boolean {
        val prev = lastStepT
        if (prev != null) {
            val iv = tNanos - prev
            if (iv < minNs) return false
            if (iv <= maxNs) {
                intervals.addLast(iv)
                if (intervals.size > capacity) intervals.removeFirst()
            }
        }
        lastStepT = tNanos
        stepCount++
        return true
    }

    fun stats(): GaitStats {
        val enough = intervals.size >= Tuning.CADENCE_MIN_INTERVALS
        val spm = if (enough) 60e9 / median(intervals.takeLast(Tuning.CADENCE_WINDOW)) else null
        val cv = if (enough) coefficientOfVariation(intervals.takeLast(Tuning.CV_WINDOW)) else null
        return GaitStats(spm = spm, cv = cv, stepCount = stepCount, lastStepNanos = lastStepT)
    }

    fun reset() {
        intervals.clear()
        lastStepT = null
        stepCount = 0
    }
}
