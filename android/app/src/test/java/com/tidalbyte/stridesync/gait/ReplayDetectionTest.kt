package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.sensor.AccelCsv
import com.tidalbyte.stridesync.sensor.SimulatedWalker
import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Runs every recorded walk in `app/src/main/assets/replays/` through the real StepDetector and
 * CadenceEstimator. Recordings have no ground truth, so this prints step counts and cadence (compare them
 * with a hand count) and asserts only plausibility: cadence in the Parkinson's-to-healthy range, and no
 * dominant doubled or halved intervals (a sign of double-counting or missed strikes).
 */
class ReplayDetectionTest {

    private val replayDir = File("src/main/assets/replays")

    @Test
    fun recordedWalks_plausibleCadence() {
        val files = replayDir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        assumeTrue("no recordings in ${replayDir.absolutePath} yet", files.isNotEmpty())
        for (f in files) {
            val samples = AccelCsv.parse(f.readLines().asSequence())
            val detector = StepDetector()
            val cadence = CadenceEstimator()
            val steps = samples.flatMap { detector.process(it) }
            steps.forEach { cadence.addStep(it.tNanos) }
            val intervals = steps.zipWithNext { a, b -> (b.tNanos - a.tNanos) / 1e6 }.filter { it in 250.0..2000.0 }
            val med = intervals.sorted().let { it[it.size / 2] }
            val doubled = intervals.count { it > 1.7 * med }.toDouble() / intervals.size
            val halved = intervals.count { it < 0.6 * med }.toDouble() / intervals.size
            val durSec = (samples.last().tNanos - samples.first().tNanos) / 1e9
            val rate = samples.size / durSec
            val s = cadence.stats()
            println(
                "%s: %.0fs @ %.0f Hz → %d steps, median interval %.0f ms (%.1f spm), final spm %.1f, stride CV %.1f%%, doubled %.0f%%, halved %.0f%%"
                    .format(f.name, durSec, rate, steps.size, med, 60000 / med, s.spm, (s.cv ?: 0.0) * 100, doubled * 100, halved * 100),
            )
            assertTrue("${f.name}: spm ${60000 / med}", 60000 / med in 40.0..140.0)
            assertTrue("${f.name}: too many doubled intervals (missed steps): $doubled", doubled < 0.1)
            assertTrue("${f.name}: too many halved intervals (double counting): $halved", halved < 0.1)
        }
    }

    /**
     * Cadence from the detector's two-step intervals must agree with the signal's own periodicity
     * (autocorrelation of the filtered magnitude at the stride lag) within ±5% in at least 90% of the 20 s
     * windows that show clear periodicity. That holds even when the per-step timing alternates (the pocket
     * artifact). The ACF is a reference, not ground truth: a turn or stop inside a window can skew its lag.
     */
    @Test
    fun recordedWalks_strideCadenceMatchesAutocorrelation() {
        val files = replayDir.listFiles { f -> f.name.endsWith(".csv") }.orEmpty().sortedBy { it.name }
        assumeTrue("no recordings yet", files.isNotEmpty())
        for (f in files) {
            val samples = AccelCsv.parse(f.readLines().asSequence())
            val steps = StepDetector().let { d -> samples.flatMap { d.process(it) } }.map { it.tNanos }
            // Filtered magnitude on a uniform 100 Hz grid (gravity EMA + 4 Hz low-pass), for the ACF.
            val fs = 100.0
            val t0 = samples.first().tNanos
            val grid = ArrayList<Double>()
            var j = 0
            var g = Double.NaN
            val lp = Biquad.lowPass(4.0, fs)
            val alpha = 1 - exp(-1 / (fs * 2.0))
            var tg = t0
            while (tg <= samples.last().tNanos) {
                while (j + 1 < samples.size && samples[j + 1].tNanos < tg) j++
                val a = samples[j]
                val b = samples[minOf(j + 1, samples.lastIndex)]
                val span = (b.tNanos - a.tNanos).coerceAtLeast(1)
                val w = ((tg - a.tNanos).toDouble() / span).coerceIn(0.0, 1.0)
                fun mag(s: com.tidalbyte.stridesync.sensor.AccelSample) = sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble())
                val m = mag(a) + w * (mag(b) - mag(a))
                if (g.isNaN()) g = m
                g += alpha * (m - g)
                grid += lp.process(m - g)
                tg += (1e9 / fs).toLong()
            }
            var checked = 0
            var agree = 0
            var worst = 0.0
            val lines = ArrayList<String>()
            val win = (20 * fs).toInt()
            var w0 = (10 * fs).toInt()
            while (w0 + win <= grid.size) {
                val seg = grid.subList(w0, w0 + win)
                val mean = seg.average()
                val x = seg.map { it - mean }
                val e0 = x.sumOf { it * it }
                fun r(lag: Int) = (0 until x.size - lag).sumOf { x[it] * x[it + lag] } / e0
                val lags = (70..240) // 0.7–2.4 s stride
                val best = lags.maxBy { r(it) }
                val segStart = t0 + (w0 / fs * 1e9).toLong()
                val segEnd = segStart + 20_000_000_000L
                val inSeg = steps.filter { it in segStart..segEnd }
                val two = (2 until inSeg.size).map { inSeg[it] - inSeg[it - 2] }.filter { it in 500_000_000L..4_000_000_000L }.sorted()
                if (r(best) > 0.5 && two.size >= 10) {
                    val acfSpm = 120.0 / (best / fs)
                    val detSpm = 120e9 / two[two.size / 2]
                    val err = abs(detSpm / acfSpm - 1)
                    worst = maxOf(worst, err)
                    checked++
                    if (err <= 0.05) agree++
                    lines += "%.0fs:%.0f/%.0f".format(w0 / fs, detSpm, acfSpm)
                }
                w0 += win
            }
            println("${f.name}: stride cadence vs ACF (detector/acf spm) ${lines.joinToString(" ")} → $agree/$checked within ±5%%, worst %.1f%%".format(worst * 100))
            val possible = ((grid.size / fs - 10) / 20).toInt()
            assertTrue("${f.name}: too few periodic windows ($checked of $possible)", checked >= minOf(5, possible))
            // With few windows (short walks), one window spanning a turn or stop is a large share, so require half.
            val required = if (checked >= 5) 0.9 else 0.5
            assertTrue("${f.name}: only $agree/$checked windows agree", agree >= required * checked)
        }
    }

    @Test
    fun csvRoundTrip_preservesSamplesExactly() {
        val walker = SimulatedWalker(WalkerConfig.PARKINSONIAN, GaitEval.START_NS)
        val original = List(500) { walker.next() }
        val text = listOf(AccelCsv.HEADER) + original.map(AccelCsv::format) + listOf("", "# comment", "garbage,row")
        assertEquals(original, AccelCsv.parse(text.asSequence()))
    }
}
