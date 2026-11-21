package com.tidalbyte.stridesync.gait

import kotlin.math.sqrt

/** A detected step. [tNanos] is the filtered-peak time, which lags the true heel strike by a roughly
 *  constant amount that `Tuning.OUTPUT_LATENCY_MS` absorbs. [amplitude] is the filtered peak height, m/s². */
data class StepEvent(val tNanos: Long, val amplitude: Double)

/** Cadence snapshot. [spm], [fastSpm] and [cv] are null until enough steps exist. */
data class GaitStats(
    val spm: Double? = null,
    /** A more responsive (noisier) cadence estimate, for Follow mode. */
    val fastSpm: Double? = null,
    /** Stride-time coefficient of variation (0.05 = 5%). */
    val cv: Double? = null,
    val stepCount: Int = 0,
    val lastStepNanos: Long? = null,
)

internal fun median(values: Collection<Long>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
}

internal fun medianD(values: Collection<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
}

/** Sample standard deviation divided by the mean. Null if there are fewer than two values. */
internal fun coefficientOfVariation(values: Collection<Long>): Double? {
    if (values.size < 2) return null
    val mean = values.sumOf { it.toDouble() } / values.size
    if (mean <= 0.0) return null
    val ss = values.sumOf { (it - mean) * (it - mean) }
    return sqrt(ss / (values.size - 1)) / mean
}
