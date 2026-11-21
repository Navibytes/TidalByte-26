package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.cue.VirtualMetronome
import com.tidalbyte.stridesync.gait.GaitEval
import com.tidalbyte.stridesync.sensor.SimulatedWalker
import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Closed-loop tests in virtual time: SimulatedWalker → GaitCuePipeline (detector, cadence, PLL) →
 * VirtualMetronome (the real BeatClock) → back to the pipeline. The walker doesn't entrain (that's M4),
 * so the beat must do all the following.
 */
class GaitCuePipelineTest {
    private val start = GaitEval.START_NS
    private val sec = 1_000_000_000L

    /** The detector's constant lag, measured in the M1 tests. It plays the role of OUTPUT_LATENCY_MS. */
    private val latencyNs = 63_000_000L

    private class Run(
        val walker: SimulatedWalker,
        val cue: VirtualMetronome,
        val pipe: GaitCuePipeline,
        val reports: List<Pair<Long, StepReport>>, // (time reported, report)
        val unmutedAtNs: Long?,
    )

    private fun run(
        config: WalkerConfig,
        seconds: Double,
        mode: CueMode = CueMode.LOCK,
        fixedBpm: Double = 90.0,
        startMuted: Boolean = mode == CueMode.LOCK,
        events: (tSec: Double, walker: SimulatedWalker, pipe: GaitCuePipeline) -> Unit = { _, _, _ -> },
    ): Run {
        val walker = SimulatedWalker(config, start)
        val cue = VirtualMetronome(start, initialBpm = if (mode == CueMode.FIXED) fixedBpm else 90.0, latencyNs, startMuted)
        val pipe = GaitCuePipeline(cue).apply {
            this.mode = mode
            this.fixedBpm = fixedBpm
        }
        val reports = ArrayList<Pair<Long, StepReport>>()
        var unmutedAt: Long? = null
        val end = start + (seconds * sec).toLong()
        var lastEventSec = -1
        while (walker.nextSampleNanos < end) {
            val s = walker.next()
            cue.advanceTo(s.tNanos)
            val tSec = (s.tNanos - start) / 1e9
            if (tSec.toInt() != lastEventSec) {
                lastEventSec = tSec.toInt()
                events(tSec, walker, pipe)
            }
            pipe.onSample(s).forEach { reports += s.tNanos to it }
            if (unmutedAt == null && !cue.muted) unmutedAt = s.tNanos
        }
        return Run(walker, cue, pipe, reports, unmutedAt)
    }

    /** True asynchrony: each heel strike in [fromSec, toSec) vs the nearest *audible* beat as heard. */
    private fun trueAsyncMs(r: Run, fromSec: Double, toSec: Double): List<Double> {
        val beats = r.cue.audibleBeatFrameTimes
        return r.walker.trueStrikes
            .filter { it >= start + (fromSec * sec).toLong() && it < start + (toSec * sec).toLong() }
            .mapNotNull { hs -> beats.minByOrNull { abs(it - hs) }?.let { (hs - it) / 1e6 } }
    }

    private fun List<Double>.medianAbs() = map { abs(it) }.sorted().let { it[it.size / 2] }

    /** The fraction of steps reported in [fromSec, toSec) whose PLL error is within the sync window. */
    private fun syncFraction(r: Run, fromSec: Double, toSec: Double): Double {
        val errs = r.reports.map { it.second }
            .filter { it.errorMs != null && it.tNanos >= start + (fromSec * sec).toLong() && it.tNanos < start + (toSec * sec).toLong() }
            .map { it.errorMs!! }
        return errs.count { abs(it) <= com.tidalbyte.stridesync.Tuning.SYNC_WINDOW_MS }.toDouble() / errs.size
    }

    @Test
    fun lockMode_acquiresSilentlyThenHoldsSync() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 120.0)
        val unmutedSec = (r.unmutedAtNs!! - start) / 1e9
        val sync = r.pipe.syncStats
        val asyncs = trueAsyncMs(r, 40.0, 119.0)
        println("lock: unmuted at %.1fs, cue %.1f bpm, walker spm %.1f, sync %s, true |async| median %.1fms max %.1fms"
            .format(unmutedSec, r.pipe.cueBpm, r.pipe.gaitStats.spm, sync, asyncs.medianAbs(), asyncs.maxOf { abs(it) }))
        assertEquals(LockPhase.LOCKED, r.pipe.phase)
        assertTrue("acquired quickly ($unmutedSec s)", unmutedSec < 15.0)
        assertTrue("rolling sync ${sync.rollingPct}", sync.rollingPct!! >= 0.9)
        assertTrue("session sync ${sync.sessionPct}", sync.sessionPct!! >= 0.85)
        assertTrue("mean |async| ${sync.sessionMeanAbsMs}", sync.sessionMeanAbsMs!! < 30.0)
        // The beat as heard lines up with the true heel strike, not the lagging detected peak.
        assertTrue("true async median ${asyncs.medianAbs()}", asyncs.medianAbs() < 25.0)
        assertEquals(r.pipe.gaitStats.spm!!, r.pipe.cueBpm, 3.0)
    }

    @Test
    fun lockMode_audibleBeatNeverLurches() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 90.0)
        val beats = r.cue.audibleBeatFrameTimes
        val intervals = beats.zipWithNext { a, b -> (b - a).toDouble() }
        val worst = intervals.zipWithNext { a, b -> abs(b - a) / a }.max()
        println("lock: worst audible interval change %.1f%%".format(worst * 100))
        assertTrue("worst interval change $worst", worst <= 0.105) // ≤5% period + ≤5% phase, plus rounding
    }

    @Test
    fun lockMode_followsCadenceChange() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 120.0) { t, walker, _ ->
            if (t >= 50.0) walker.targetCadenceSpm = 70.0
        }
        // Judged over the whole settled window (t ≥ 80 s), not a noisy last-16-steps snapshot.
        val settled = syncFraction(r, 80.0, 120.0)
        val asyncs = trueAsyncMs(r, 80.0, 119.0)
        println("lock 90→70: cue %.1f bpm, spm %.1f, settled sync %.2f, true |async| median %.1fms"
            .format(r.pipe.cueBpm, r.pipe.gaitStats.spm, settled, asyncs.medianAbs()))
        assertEquals(70.0, r.pipe.cueBpm, 3.0)
        assertTrue("settled sync $settled", settled >= 0.8)
        assertTrue("true async median ${asyncs.medianAbs()}", asyncs.medianAbs() < 35.0)
    }

    @Test
    fun lockMode_slowShufflingGait_moderateVariability() {
        val r = run(WalkerConfig.SHUFFLING.copy(intervalCv = 0.03), seconds = 150.0)
        val settled = syncFraction(r, 30.0, 150.0)
        println("lock shuffling cv3%%: cue %.1f bpm, spm %.1f, settled sync %.2f".format(r.pipe.cueBpm, r.pipe.gaitStats.spm, settled))
        assertEquals(LockPhase.LOCKED, r.pipe.phase)
        assertEquals(r.pipe.gaitStats.spm!!, r.pipe.cueBpm, 3.0)
        assertTrue("settled sync $settled", settled >= 0.8)
    }

    /**
     * A highly variable walker (5% interval CV at 62 spm, about ±48 ms per step, accumulating like a random
     * walk) can't be predicted to ±60 ms by any beat: even a perfect follower is bounded near
     * P(|N(0, 48 ms)| < 60 ms) ≈ 79%. Without entrainment, the lock should still hold tempo and stay in the
     * right neighborhood.
     */
    @Test
    fun lockMode_slowShufflingGait_highVariability_holdsLock() {
        val r = run(WalkerConfig.SHUFFLING, seconds = 150.0)
        val settled = syncFraction(r, 30.0, 150.0)
        val sync = r.pipe.syncStats
        println("lock shuffling cv5%%: cue %.1f bpm, spm %.1f, settled sync %.2f, MAA %.1fms"
            .format(r.pipe.cueBpm, r.pipe.gaitStats.spm, settled, sync.sessionMeanAbsMs))
        assertEquals(LockPhase.LOCKED, r.pipe.phase)
        assertEquals(r.pipe.gaitStats.spm!!, r.pipe.cueBpm, 3.0)
        assertTrue("settled sync $settled", settled >= 0.6)
        assertTrue("MAA ${sync.sessionMeanAbsMs}", sync.sessionMeanAbsMs!! < 60.0)
    }

    @Test
    fun fixedMode_leadsAtExactTempo_andAlignsPhaseWhenTempoMatches() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = 90.0, driftSpm = 0.0, intervalCv = 0.02)
        val r = run(cfg, seconds = 90.0, mode = CueMode.FIXED, fixedBpm = 90.0)
        val sync = r.pipe.syncStats
        println("fixed matched: cue %.2f bpm, sync %s".format(r.pipe.cueBpm, sync))
        assertEquals(90.0, r.pipe.cueBpm, 1e-9) // weak coupling never touches the tempo
        assertTrue("rolling sync ${sync.rollingPct}", sync.rollingPct!! >= 0.8)
    }

    @Test
    fun fixedMode_doesNotChaseAWalkerWhoIgnoresIt() {
        // The walker stays at 90 while the cue leads at 100. A non-entraining walker means low sync. That's
        // the signal the ramp (M4) uses to step back.
        val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = 90.0, driftSpm = 0.0)
        val r = run(cfg, seconds = 90.0, mode = CueMode.FIXED, fixedBpm = 100.0)
        val sync = r.pipe.syncStats
        println("fixed mismatched: cue %.2f bpm, sync %s".format(r.pipe.cueBpm, sync))
        assertEquals(100.0, r.pipe.cueBpm, 1e-9)
        assertTrue("rolling sync should be poor: ${sync.rollingPct}", sync.rollingPct!! < 0.5)
    }

    @Test
    fun switchingModesMidSession() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 120.0) { t, _, pipe ->
            if (t >= 40.0 && t < 80.0) {
                pipe.fixedBpm = 90.0
                pipe.mode = CueMode.FIXED
            }
            if (t >= 80.0) pipe.mode = CueMode.LOCK
        }
        println("switch: final phase=${r.pipe.phase} cue=%.1f sync=${r.pipe.syncStats}".format(r.pipe.cueBpm))
        assertEquals(LockPhase.LOCKED, r.pipe.phase) // re-acquired after returning to LOCK
        assertTrue(r.pipe.syncStats.rollingPct!! >= 0.8)
    }
}
