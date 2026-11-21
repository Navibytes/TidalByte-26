package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.sensor.AccelSample
import com.tidalbyte.stridesync.sensor.SimulatedWalker
import com.tidalbyte.stridesync.sensor.WalkerConfig
import kotlin.math.abs

/** Test helpers: run synthetic gait through the detector and score it against ground truth. */
object GaitEval {
    const val START_NS = 1_000_000_000_000L // arbitrary elapsedRealtime-like base
    private const val MS = 1_000_000L

    data class Run(val truth: List<Long>, val detected: List<StepEvent>, val endNs: Long)

    /**
     * Generates [seconds] of gait and feeds it to a fresh [StepDetector]. [mutate] can drop, reorder or
     * re-time samples to simulate irregular delivery. Return null to drop a sample.
     */
    fun run(
        config: WalkerConfig,
        seconds: Double,
        mutate: (index: Int, s: AccelSample) -> AccelSample? = { _, s -> s },
    ): Run {
        val walker = SimulatedWalker(config, START_NS)
        val detector = StepDetector()
        val detected = ArrayList<StepEvent>()
        val endNs = START_NS + (seconds * 1e9).toLong()
        var i = 0
        while (walker.nextSampleNanos < endNs) {
            val s = mutate(i++, walker.next()) ?: continue
            detected += detector.process(s)
        }
        return Run(walker.trueStrikes.filter { it < endNs }, detected, endNs)
    }

    data class Score(
        val truthCount: Int,
        val matched: Int,
        val missed: Int,
        val extra: Int,
        val medianLagMs: Double,
        val maxResidualMs: Double,
        val p95ResidualMs: Double,
    ) {
        val recall get() = matched.toDouble() / truthCount
        val precision get() = matched.toDouble() / (matched + extra)
        override fun toString() =
            "truth=$truthCount matched=$matched missed=$missed extra=$extra " +
                "recall=%.3f precision=%.3f lag=%.1fms maxRes=%.1fms p95Res=%.1fms"
                    .format(recall, precision, medianLagMs, maxResidualMs, p95ResidualMs)
    }

    /**
     * Matches detections to true strikes one-to-one. First the median detection lag is estimated. Then each
     * true strike t is paired with the closest unused detection to t + lag, within ±[matchWindowMs].
     * Residual = detection − t − lag, which is the timing error left after a constant latency offset absorbs
     * the lag. Strikes in the first [warmupSec] and the last second are excluded.
     */
    fun score(run: Run, warmupSec: Double = 4.0, matchWindowMs: Long = 150): Score {
        val from = START_NS + (warmupSec * 1e9).toLong()
        val to = run.endNs - 1_000_000_000L
        val truth = run.truth.filter { it in from..to }
        val dets = run.detected.map { it.tNanos }.filter { it in (from - 200 * MS)..(to + 400 * MS) }

        val rawLags = truth.mapNotNull { t ->
            dets.filter { it in (t - 100 * MS)..(t + 300 * MS) }.minByOrNull { abs(it - t) }?.let { it - t }
        }
        val lag = if (rawLags.isEmpty()) 0.0 else median(rawLags)

        val used = BooleanArray(dets.size)
        val residuals = ArrayList<Double>()
        for (t in truth) {
            val target = t + lag
            var best = -1
            var bestErr = Double.MAX_VALUE
            for (j in dets.indices) {
                if (used[j]) continue
                val err = abs(dets[j] - target)
                if (err <= matchWindowMs * MS && err < bestErr) {
                    best = j
                    bestErr = err
                }
            }
            if (best >= 0) {
                used[best] = true
                residuals += (dets[best] - target) / 1e6
            }
        }
        val extra = dets.indices.count { !used[it] && dets[it] in from..to }
        val absRes = residuals.map { abs(it) }.sorted()
        return Score(
            truthCount = truth.size,
            matched = residuals.size,
            missed = truth.size - residuals.size,
            extra = extra,
            medianLagMs = lag / 1e6,
            maxResidualMs = absRes.lastOrNull() ?: 0.0,
            p95ResidualMs = if (absRes.isEmpty()) 0.0 else absRes[((absRes.size - 1) * 0.95).toInt()],
        )
    }

}
