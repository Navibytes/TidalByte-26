package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.cue.VirtualMetronome
import com.tidalbyte.stridesync.gait.GaitEval
import com.tidalbyte.stridesync.sensor.FreezeEpisode
import com.tidalbyte.stridesync.sensor.SimulatedWalker
import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Closed-loop tests in virtual time, with the real Tuning timings (45 s holds and so on):
 * SimulatedWalker → GaitCuePipeline (detector, cadence, PLL, RampController) → VirtualMetronome (the real
 * BeatClock). The beats feed back to the walker, which hears them and partially entrains.
 */
class GaitCuePipelineTest {
    private val start = GaitEval.START_NS
    private val sec = 1_000_000_000L

    /** The detector's lag plays the role of OUTPUT_LATENCY_MS in virtual time (no speaker). */
    private val latencyNs = Tuning.DETECTOR_LAG_MS * 1_000_000L

    private class Run(
        val walker: SimulatedWalker,
        val cue: VirtualMetronome,
        val pipe: GaitCuePipeline,
        val reports: List<StepReport>,
        val maxCueBpm: Double, // highest tempo the cue was ever set to
        val firstAudibleNs: Long?,
        /** (time, bpm) whenever the cue tempo changed during a freeze window, if one was given. */
        val tempoDuringFreeze: List<Double>,
    )

    private fun run(config: WalkerConfig, seconds: Double, freeze: FreezeEpisode? = null): Run {
        val walker = SimulatedWalker(config, start)
        val cue = VirtualMetronome(start, initialBpm = Tuning.CUE_DEFAULT_BPM, latencyNs = latencyNs, muted = true)
        walker.beatSource = { cue.audibleBeatFrameTimes }
        val pipe = GaitCuePipeline(cue)
        val reports = ArrayList<StepReport>()
        var maxBpm = 0.0
        var firstAudible: Long? = null
        val freezeTempos = ArrayList<Double>()
        val end = start + (seconds * sec).toLong()
        while (walker.nextSampleNanos < end) {
            val s = walker.next()
            cue.advanceTo(s.tNanos)
            reports += pipe.onSample(s)
            if (!cue.muted) {
                maxBpm = maxOf(maxBpm, pipe.cueBpm)
                if (firstAudible == null) firstAudible = s.tNanos
            }
            if (freeze != null) {
                val t = (s.tNanos - start) / 1e9
                if (t >= freeze.startSec + Tuning.PAUSE_SEC + 1 && t < freeze.startSec + freeze.durationSec) freezeTempos += pipe.cueBpm
            }
        }
        val r = Run(walker, cue, pipe, reports, maxBpm, firstAudible, freezeTempos)
        println(summary(r, config))
        return r
    }

    private fun summary(r: Run, cfg: WalkerConfig): String {
        val ramp = r.pipe.ramp
        val events = ramp.events.joinToString("\n    ") { e ->
            "%6.1fs %s→%s: %s".format((e.tNanos - start) / 1e9, e.from, e.to, e.reason)
        }
        return "walker natural=%.0f entrains=%b headroom=%.0f%% → baseline=%.1f final cue=%.1f max cue=%.1f cap=%.1f walker now=%.1f sync=%s\n    %s"
            .format(
                cfg.cadenceSpm, cfg.entrains, cfg.abilityHeadroomPct * 100, ramp.baselineSpm, r.pipe.cueBpm,
                r.maxCueBpm, ramp.maxBpm, r.walker.currentCadenceSpm, r.pipe.syncStats.sessionPct, events,
            )
    }

    private fun Run.transitions() = pipe.ramp.events.filter { it.from != it.to }.map { it.from to it.to }

    @Test
    fun calibratesSilently_locks_thenRamps() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 150.0)
        val t = r.transitions()
        assertEquals(CueState.CALIBRATING to CueState.LOCKING, t[0])
        assertTrue("reached RAMPING: $t", (CueState.LOCKING to CueState.RAMPING) in t)
        // Nothing audible until calibration finished (20 steps).
        val calibratedAt = r.pipe.ramp.events.first().tNanos
        assertTrue("silent during calibration", r.firstAudibleNs!! > calibratedAt)
        // Locked before ramping: steps in the sync window.
        val rampAt = r.pipe.ramp.events.first { it.to == CueState.RAMPING }.tNanos
        val lockErrs = r.reports.filter { it.state == CueState.LOCKING && it.errorMs != null && it.tNanos < rampAt }
            .drop(Tuning.PLL_ACQUIRE_STEPS + 2).map { it.errorMs!! }
        val inSync = lockErrs.count { abs(it) <= Tuning.SYNC_WINDOW_MS }.toDouble() / lockErrs.size
        assertTrue("lock sync $inSync", inSync >= 0.85)
    }

    /** The demo scenario: the walker can sustain about +6%. The ramp climbs, overreaches, steps back, and holds. */
    @Test
    fun entrainingWalker_rampsUp_overreaches_stepsBack() {
        val cfg = WalkerConfig.PARKINSONIAN
        val r = run(cfg, seconds = 12 * 60.0)
        val ramp = r.pipe.ramp
        val baseline = ramp.baselineSpm!!
        val t = r.transitions()
        assertTrue("ramp steps ${ramp.rampStepsTaken}", ramp.rampStepsTaken >= 3)
        assertTrue("stepped back: $t", t.any { it.second == CueState.STEPPING_BACK })
        assertTrue("never above cap", r.maxCueBpm <= ramp.maxBpm + 1e-6)
        // It never got more than one ramp step beyond what the walker can do.
        assertTrue("max cue ${r.maxCueBpm} vs baseline $baseline", r.maxCueBpm <= baseline * (1 + cfg.abilityHeadroomPct + 0.035))
        // And the walker really was pulled faster than its natural cadence.
        assertTrue("walker cadence ${r.walker.currentCadenceSpm}", r.walker.currentCadenceSpm >= cfg.cadenceSpm * 1.03)
        assertTrue("session sync ${r.pipe.syncStats.sessionPct}", r.pipe.syncStats.sessionPct!! >= 0.7)
    }

    /**
     * The ramp only ever steps back from a tempo the walker *can't* sustain, never from one within its
     * ability. That's the guard against cascading back down while the walker re-adapts after an
     * overreach. Checked over several seeds, because timing varies run to run on the device.
     */
    @Test
    fun stepsBackOnlyFromTemposBeyondWalkerAbility() {
        for (seed in 1L..4L) {
            val cfg = WalkerConfig.PARKINSONIAN.copy(seed = seed)
            val ability = cfg.cadenceSpm * (1 + cfg.abilityHeadroomPct)
            val r = run(cfg, seconds = 10 * 60.0)
            for (e in r.pipe.ramp.events.filter { it.from != it.to && (it.to == CueState.STEPPING_BACK || (it.to == CueState.LOCKING && it.from != CueState.CALIBRATING)) }) {
                val fromBpm = r.reports.last { it.tNanos < e.tNanos }.cueBpm
                assertTrue(
                    "seed $seed: stepped back at %.0fs from %.1f bpm, within ability %.1f: %s"
                        .format((e.tNanos - start) / 1e9, fromBpm, ability, e.reason),
                    fromBpm > ability * 0.99,
                )
            }
            assertTrue("seed $seed ended at level ${r.pipe.ramp.rampLevel}", r.pipe.ramp.rampLevel >= 1)
        }
    }

    @Test
    fun entrainingWalker_withAmpleAbility_reachesCapAndHolds() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(abilityHeadroomPct = 0.25, seed = 5)
        val r = run(cfg, seconds = 10 * 60.0)
        val ramp = r.pipe.ramp
        assertEquals(CueState.HOLDING, ramp.state)
        assertTrue(ramp.events.last().reason.contains("cap"))
        assertEquals(ramp.maxBpm, r.pipe.cueBpm, 1e-6)
        assertTrue("never above cap", r.maxCueBpm <= ramp.maxBpm + 1e-6)
    }

    @Test
    fun walkerWhoIgnoresTheCue_isNeverPushed() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(entrains = false, driftSpm = 0.0)
        val r = run(cfg, seconds = 8 * 60.0)
        val ramp = r.pipe.ramp
        val baseline = ramp.baselineSpm!!
        val t = r.transitions()
        assertTrue("backed off: $t", t.any { it.second == CueState.STEPPING_BACK || it.second == CueState.LOCKING && it.first != CueState.CALIBRATING })
        assertTrue("max cue ${r.maxCueBpm}", r.maxCueBpm <= baseline * (1 + Tuning.RAMP_STEP_PCT) + 0.5)
    }

    @Test
    fun freezeDuringRamp_pausesWithSteadyBeat_thenHolds() {
        val freeze = FreezeEpisode(startSec = 110.0, durationSec = 10.0)
        val r = run(WalkerConfig.PARKINSONIAN.copy(freezes = listOf(freeze)), seconds = 180.0, freeze = freeze)
        val t = r.transitions()
        assertTrue("paused: $t", t.any { it.second == CueState.PAUSED })
        val paused = r.pipe.ramp.events.first { it.to == CueState.PAUSED }
        assertTrue("paused during the freeze", (paused.tNanos - start) / 1e9 in freeze.startSec..(freeze.startSec + freeze.durationSec))
        assertTrue("tempo frozen during the freeze", r.tempoDuringFreeze.distinct().size == 1)
        val resumed = r.pipe.ramp.events.first { it.from == CueState.PAUSED }
        assertEquals(CueState.HOLDING, resumed.to)
    }

    @Test
    fun audibleBeatNeverLurches() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 8 * 60.0)
        val intervals = r.cue.audibleBeatFrameTimes.zipWithNext { a, b -> (b - a).toDouble() }
        val worst = intervals.zipWithNext { a, b -> abs(b - a) / a }.max()
        println("worst audible interval change %.1f%%".format(worst * 100))
        assertTrue("worst interval change $worst", worst <= 0.105) // ≤5% period + ≤5% phase, plus rounding
    }

    @Test
    fun slowShufflingWalker_calibratesLocksAndRamps() {
        val r = run(WalkerConfig.SHUFFLING.copy(intervalCv = 0.04), seconds = 5 * 60.0)
        val t = r.transitions()
        assertTrue("reached RAMPING: $t", (CueState.LOCKING to CueState.RAMPING) in t)
        assertTrue("never above cap", r.maxCueBpm <= r.pipe.ramp.maxBpm + 1e-6)
    }
}
