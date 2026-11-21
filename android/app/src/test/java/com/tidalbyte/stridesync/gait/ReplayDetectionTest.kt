package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.sensor.AccelCsv
import com.tidalbyte.stridesync.sensor.SimulatedWalker
import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

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
                "%s: %.0fs @ %.0f Hz → %d steps, median interval %.0f ms (%.1f spm), final spm %.1f, CV %.1f%%, doubled %.0f%%, halved %.0f%%"
                    .format(f.name, durSec, rate, steps.size, med, 60000 / med, s.spm, (s.cv ?: 0.0) * 100, doubled * 100, halved * 100),
            )
            assertTrue("${f.name}: spm ${60000 / med}", 60000 / med in 40.0..140.0)
            assertTrue("${f.name}: too many doubled intervals (missed steps): $doubled", doubled < 0.1)
            assertTrue("${f.name}: too many halved intervals (double counting): $halved", halved < 0.1)
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
