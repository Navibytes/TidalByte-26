package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.Tuning

/**
 * Turns step times into cadence (spm) and consistency (stride-time CV). Pure Kotlin.
 *
 * Everything is computed from two-step intervals (t[k] − t[k−2], one stride). With the phone on one thigh,
 * the two feet's detections can alternate long/short (~750/375 ms where true steps are ~560/560). A long +
 * short pair always sums to one stride, so two-step intervals cancel the alternation exactly. A median of
 * single intervals would jump between the two clusters.
 *
 * - spm = 120 / median of the last [Tuning.CADENCE_WINDOW_STRIDES] two-step intervals. That tolerates one
 *   missed or extra step.
 * - fastSpm: the same over the last [Tuning.CADENCE_FAST_STRIDES], for Follow mode.
 * - CV = sample SD / mean of the last [Tuning.CV_WINDOW_STRIDES] two-step intervals (stride-time CV).
 * - A single interval outside [Tuning.STEP_INTERVAL_MIN_MS, Tuning.STEP_INTERVAL_MAX_MS] is rejected:
 *   - Too short (a double detection): the step is ignored entirely.
 *   - Too long (a pause or freeze): the step chain restarts, so no two-step interval spans the pause.
 */
class CadenceEstimator {
    private val minNs = Tuning.STEP_INTERVAL_MIN_MS * 1_000_000
    private val maxNs = Tuning.STEP_INTERVAL_MAX_MS * 1_000_000
    private val capacity = maxOf(Tuning.CADENCE_WINDOW_STRIDES, Tuning.CV_WINDOW_STRIDES, Tuning.CADENCE_FAST_STRIDES)

    private val chain = ArrayDeque<Long>() // the last up-to-3 steps of the current unbroken chain
    private val strides = ArrayDeque<Long>() // two-step intervals, ns
    private var lastStepT: Long? = null
    private var stepCount = 0

    /** Records a step. Returns false if it was rejected as a double detection. */
    fun addStep(tNanos: Long): Boolean {
        val prev = lastStepT
        if (prev != null) {
            val iv = tNanos - prev
            if (iv < minNs) return false
            if (iv > maxNs) chain.clear()
        }
        chain.addLast(tNanos)
        if (chain.size > 3) chain.removeFirst()
        if (chain.size == 3) {
            strides.addLast(chain.last() - chain.first())
            if (strides.size > capacity) strides.removeFirst()
        }
        lastStepT = tNanos
        stepCount++
        return true
    }

    fun stats(): GaitStats {
        val enough = strides.size >= Tuning.CADENCE_MIN_STRIDES
        return GaitStats(
            spm = if (enough) 120e9 / median(strides.takeLast(Tuning.CADENCE_WINDOW_STRIDES)) else null,
            fastSpm = if (enough) 120e9 / median(strides.takeLast(Tuning.CADENCE_FAST_STRIDES)) else null,
            cv = if (enough) coefficientOfVariation(strides.takeLast(Tuning.CV_WINDOW_STRIDES)) else null,
            stepCount = stepCount,
            lastStepNanos = lastStepT,
        )
    }

    fun reset() {
        chain.clear()
        strides.clear()
        lastStepT = null
        stepCount = 0
    }
}
