package com.tidalbyte.stridesync.sensor

import com.tidalbyte.stridesync.cue.VirtualMetronome
import com.tidalbyte.stridesync.gait.GaitEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SimulatedWalkerTest {
    private val start = GaitEval.START_NS
    private val sec = 1_000_000_000L

    /** Walks [seconds] with a fixed, always-audible cue at [cueBpm]. Returns (walker, cue). */
    private fun walkWithCue(cfg: WalkerConfig, cueBpm: Double, seconds: Double): Pair<SimulatedWalker, VirtualMetronome> {
        val walker = SimulatedWalker(cfg, start)
        val cue = VirtualMetronome(start, cueBpm, latencyNs = 0)
        walker.beatSource = { cue.audibleBeatFrameTimes }
        val end = start + (seconds * sec).toLong()
        while (walker.nextSampleNanos < end) {
            val s = walker.next()
            cue.advanceTo(s.tNanos)
        }
        return walker to cue
    }

    private fun medianAbsAsyncMs(walker: SimulatedWalker, cue: VirtualMetronome, fromSec: Double): Double {
        val beats = cue.audibleBeatFrameTimes
        val asyncs = walker.trueStrikes.filter { it >= start + (fromSec * sec).toLong() && it <= beats.last() }
            .map { hs -> abs(hs - beats.minBy { abs(it - hs) }) / 1e6 }.sorted()
        return asyncs[asyncs.size / 2]
    }

    @Test
    fun noAudibleCue_walksExactlyAsWithoutBeatSource() {
        val a = SimulatedWalker(WalkerConfig.PARKINSONIAN, start)
        val b = SimulatedWalker(WalkerConfig.PARKINSONIAN, start).apply { beatSource = { emptyList() } }
        repeat(6000) {
            assertEquals(a.next(), b.next())
        }
        assertEquals(a.trueStrikes, b.trueStrikes)
    }

    @Test
    fun entrainsToCueTempoAndPhase() {
        val (walker, cue) = walkWithCue(WalkerConfig.PARKINSONIAN.copy(driftSpm = 0.0), cueBpm = 94.0, seconds = 90.0)
        val async = medianAbsAsyncMs(walker, cue, fromSec = 45.0)
        println("entrained: cadence %.1f (natural 90, cue 94), median |async| %.1f ms".format(walker.currentCadenceSpm, async))
        // Partial: the cadence goes 90% of the way, and phase correction makes up the rest step by step.
        assertEquals(90.0 + 0.9 * 4.0, walker.currentCadenceSpm, 0.5)
        assertTrue("median async $async", async < 40.0)
    }

    @Test
    fun cannotFollowBeyondAbilityCeiling() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(driftSpm = 0.0) // ability = 90 × 1.06 = 95.4
        val (walker, cue) = walkWithCue(cfg, cueBpm = 100.0, seconds = 90.0)
        val async = medianAbsAsyncMs(walker, cue, fromSec = 45.0)
        println("over ability: cadence %.1f, median |async| %.1f ms".format(walker.currentCadenceSpm, async))
        assertTrue(walker.currentCadenceSpm <= 95.4 + 1e-9)
        assertTrue("falls out of sync: $async", async > 80.0)
    }

    @Test
    fun ignoresCueWhenNotEntraining() {
        val (walker, _) = walkWithCue(WalkerConfig.PARKINSONIAN.copy(entrains = false, driftSpm = 0.0), cueBpm = 94.0, seconds = 60.0)
        assertEquals(90.0, walker.currentCadenceSpm, 0.5)
    }
}
