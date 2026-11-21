package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.cue.RampTiming
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
        /** (time s, cue bpm) once per second. */
        val cueTrace: List<Pair<Double, Double>>,
        /** (time s, walker's actual cadence from its true strikes) once per second. */
        val walkerTrace: List<Pair<Double, Double>>,
    )

    /**
     * [heardOffsetNs] makes the walker hear each beat this much later than the app assumes. That models a
     * wrong OUTPUT_LATENCY_MS, a constant bias the app must not chase. [events] runs once per second.
     */
    private fun run(
        config: WalkerConfig,
        seconds: Double,
        freeze: FreezeEpisode? = null,
        timing: RampTiming = RampTiming(),
        followOnly: Boolean = false,
        heardOffsetNs: Long = 0L,
        music: Boolean = false,
        log: (String, String) -> Unit = { _, _ -> },
        events: (tSec: Double, walker: SimulatedWalker) -> Unit = { _, _ -> },
    ): Run {
        val walker = SimulatedWalker(config, start)
        val cue = VirtualMetronome(start, initialBpm = Tuning.CUE_DEFAULT_BPM, latencyNs = latencyNs, muted = true)
        walker.beatSource = { cue.audibleBeatFrameTimes.map { it + heardOffsetNs } }
        val pipe = GaitCuePipeline(cue, timing, followOnly, log).also { it.music = music }
        val reports = ArrayList<StepReport>()
        var maxBpm = 0.0
        var firstAudible: Long? = null
        val freezeTempos = ArrayList<Double>()
        val trace = ArrayList<Pair<Double, Double>>()
        val wTrace = ArrayList<Pair<Double, Double>>()
        var lastSec = -1
        val end = start + (seconds * sec).toLong()
        while (walker.nextSampleNanos < end) {
            val s = walker.next()
            cue.advanceTo(s.tNanos)
            val tSec = (s.tNanos - start) / 1e9
            if (tSec.toInt() != lastSec) {
                lastSec = tSec.toInt()
                events(tSec, walker)
                trace += tSec to pipe.cueBpm
                val past = walker.trueStrikes.filter { it <= s.tNanos }.takeLast(5)
                if (past.size == 5) {
                    val strides = (2 until 5).map { past[it] - past[it - 2] }.sorted()
                    wTrace += tSec to 120e9 / strides[1]
                }
            }
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
        val r = Run(walker, cue, pipe, reports, maxBpm, firstAudible, freezeTempos, trace, wTrace)
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
        // The ramp never got past its first step (the cue may still follow the walker's own wander while LOCKING).
        assertTrue("climbed past one step: ${ramp.events.map { it.reason }}", ramp.events.none { it.reason.startsWith("ramp step 2") })
        assertTrue("max cue ${r.maxCueBpm} vs baseline $baseline", r.maxCueBpm <= baseline * 1.06)
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

    // ───────────── Demo timing, Follow mode, and real-world robustness ─────────────

    @Test
    fun demoTiming_fullCycleInAboutAMinute() {
        val r = run(WalkerConfig.PARKINSONIAN, seconds = 75.0, timing = RampTiming.DEMO)
        val ev = r.pipe.ramp.events
        val rampAt = ev.firstOrNull { it.to == CueState.RAMPING }?.let { (it.tNanos - start) / 1e9 }
        val backAt = ev.firstOrNull { it.to == CueState.STEPPING_BACK }?.let { (it.tNanos - start) / 1e9 }
        println("demo timing: ramping at %s s, first step-back at %s s".format(rampAt, backAt))
        assertTrue("ramping by 25 s: $rampAt", rampAt != null && rampAt <= 25.0)
        assertTrue("stepped back by 70 s: $backAt", backAt != null && backAt <= 70.0)
        assertTrue("never above cap", r.maxCueBpm <= r.pipe.ramp.maxBpm + 1e-6)
    }

    @Test
    fun followMode_cueTracksSpeedChangesWithinSeconds() {
        // A person deliberately changing pace drives it themselves: a weak pull toward the beat's tempo, and
        // little step-by-step correction toward it (a strongly phase-correcting walker stays locked to the beat).
        val cfg = WalkerConfig.PARKINSONIAN.copy(driftSpm = 0.5, entrainStrength = 0.3, phaseCorrection = 0.05)
        val r = run(cfg, seconds = 90.0, followOnly = true) { t, w ->
            w.targetCadenceSpm = when {
                t < 30 -> 90.0
                t < 60 -> 115.0
                else -> 80.0
            }
        }
        // Compare the cue with what the walker is actually doing (its true strikes), second by second.
        val walkerAt = r.walkerTrace.toMap()
        val pairs = r.cueTrace.mapNotNull { (t, cueBpm) -> walkerAt[t]?.let { Triple(t, cueBpm, it) } }
        println("follow trace (t:cue/walker): " + pairs.filter { it.first.toInt() % 3 == 0 }
            .joinToString(" ") { "%.0f:%.0f/%.0f".format(it.first, it.second, it.third) })
        fun settleAfter(changeSec: Double): Double {
            // Start the clock once the walker has actually moved ≥8 spm from its pre-change cadence. Then find
            // the first time the cue is within ±4 bpm of the walker for 3 seconds running.
            val before = pairs.last { it.first < changeSec }.third
            val fromSec = pairs.first { it.first >= changeSec && kotlin.math.abs(it.third - before) >= 8.0 }.first
            val window = pairs.filter { it.first >= fromSec }
            val i = window.indices.first { k -> k + 2 < window.size && (0..2).all { kotlin.math.abs(window[k + it].second - window[k + it].third) <= 4.0 } }
            return window[i].first - fromSec
        }
        val up = settleAfter(30.0)
        val down = settleAfter(60.0)
        val meanErr = pairs.filter { it.first >= 10 }.map { kotlin.math.abs(it.second - it.third) }.average()
        println("follow: cue matched the walker ±4 bpm %.0f s after the speed-up and %.0f s after the slow-down; mean |cue − walker| %.1f bpm; sync %s"
            .format(up, down, meanErr, r.pipe.syncStats))
        assertEquals(CueState.LOCKING, r.pipe.ramp.state) // Follow never ramps
        // ≤6.5 s: the fast estimate (5 strides) and its 2-step persistence ignore one-off glitches on real data.
        assertTrue("speed-up tracked in $up s", up <= 6.5)
        assertTrue("slow-down tracked in $down s", down <= 6.5)
        assertTrue("mean tracking error $meanErr bpm", meanErr <= 2.5)
        assertTrue("session sync ${r.pipe.syncStats.sessionPct}", r.pipe.syncStats.sessionPct!! >= 0.75)
    }

    /** The front-pocket artifact: detections alternate late/early by 170 ms. The cue must hold a steady tempo
     *  at the true cadence and keep sync. */
    @Test
    fun pocketArtifact_lockStaysSteadyAndInSync() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(farStepDelaySec = 0.17, seed = 9)
        val r = run(cfg, seconds = 120.0, followOnly = true)
        val settled = r.cueTrace.filter { it.first >= 30.0 }
        val spread = settled.maxOf { it.second } - settled.minOf { it.second }
        val walker = r.walker.currentCadenceSpm
        val rawSpread = r.reports.filter { it.rawErrorMs != null && it.tNanos > start + 30 * sec }.map { it.rawErrorMs!! }
            .let { it.max() - it.min() }
        println("pocket artifact: cue %.1f–%.1f bpm (walker %.1f), raw per-step error spread %.0f ms, sync %s"
            .format(settled.minOf { it.second }, settled.maxOf { it.second }, walker, rawSpread, r.pipe.syncStats))
        assertTrue("cue tempo steady: spread $spread bpm", spread <= 6.0)
        assertEquals(walker, r.pipe.cueBpm, 3.0)
        assertTrue("rolling sync ${r.pipe.syncStats.rollingPct}", r.pipe.syncStats.rollingPct!! >= 0.8)
    }

    /** A wrong latency constant (the walker hears beats 40 ms later than assumed) is a constant bias. Beat
     *  and walker must not chase each other into a tempo drift. */
    @Test
    fun latencyMismatch_doesNotDrift() {
        for (follow in listOf(true, false)) {
            val cfg = WalkerConfig.PARKINSONIAN.copy(driftSpm = 0.5)
            val r = run(cfg, seconds = 150.0, followOnly = follow, timing = RampTiming.DEMO, heardOffsetNs = 40_000_000L)
            val lockPhase = r.cueTrace.filter { (t, _) ->
                t >= 20 && r.pipe.ramp.events.none { it.to == CueState.RAMPING && (it.tNanos - start) / 1e9 <= t }
            }
            println("latency mismatch (follow=$follow): offset learned %.1f ms; cue during lock %.1f–%.1f bpm; final %.1f; sync %s"
                .format(r.pipe.offsetNs / 1e6, lockPhase.minOfOrNull { it.second }, lockPhase.maxOfOrNull { it.second }, r.pipe.cueBpm, r.pipe.syncStats))
            if (follow) {
                // A strongly entraining walker and a following beat may wander together a little, but a constant bias
                // must not make them run away: bounded, and no trend between the early and late halves.
                val early = r.cueTrace.filter { it.first in 30.0..90.0 }.map { it.second }.average()
                val late = r.cueTrace.filter { it.first >= 90 }.map { it.second }
                assertTrue("bounded: ${late.min()}–${late.max()}", late.all { kotlin.math.abs(it - 90.0) <= 6.0 })
                assertTrue("no trend: early %.1f vs late %.1f".format(early, late.average()), kotlin.math.abs(late.average() - early) <= 3.0)
            }
            assertTrue("session sync ${r.pipe.syncStats.sessionPct}", r.pipe.syncStats.sessionPct!! >= 0.7)
        }
    }

    /** Follow mode: after a stop the walker restarts at an arbitrary phase. The beat must jump back onto the
     *  steps within a few steps, not creep back 10% per beat. */
    @Test
    fun followMode_resyncsQuicklyAfterAStop() {
        val freeze = FreezeEpisode(startSec = 40.0, durationSec = 4.3)
        val cfg = WalkerConfig.PARKINSONIAN.copy(freezes = listOf(freeze), entrainStrength = 0.3, phaseCorrection = 0.05, seed = 13)
        val r = run(cfg, seconds = 80.0, followOnly = true)
        val after = r.reports.filter { it.tNanos > start + ((freeze.startSec + freeze.durationSec) * sec).toLong() && it.errorMs != null }
        val firstInSync = after.indexOfFirst { kotlin.math.abs(it.errorMs!!) <= Tuning.SYNC_WINDOW_MS }
        val stayed = after.drop(firstInSync).take(10).count { kotlin.math.abs(it.errorMs!!) <= Tuning.SYNC_WINDOW_MS }
        println("follow re-sync: back in sync %d steps after the restart; %d/10 in sync afterwards; errors %s"
            .format(firstInSync, stayed, after.take(8).map { "%+.0f".format(it.errorMs) }))
        assertTrue("re-synced within 4 steps: $firstInSync", firstInSync in 0..4)
        assertTrue("stays in sync: $stayed/10", stayed >= 8)
    }

    // ───────────── Music: the smooth profile ─────────────

    /** The instantaneous tempo the cue actually played (60 / each audible beat interval), with its time. */
    private fun Run.playedTempo(): List<Pair<Double, Double>> =
        cue.audibleBeatFrameTimes.map { (it - start) / 1e9 }.zipWithNext { a, b -> b to 60 / (b - a) }

    /** With a song as the cue, every per-beat timing change is a tempo wobble. Deliberate pace changes must glide,
     *  not lurch, and the song must still arrive at the walker's new pace. */
    @Test
    fun followMode_withMusic_tempoGlidesAndArrives() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(driftSpm = 0.5, entrainStrength = 0.3, phaseCorrection = 0.05)
        val r = run(cfg, seconds = 90.0, followOnly = true, music = true) { t, w ->
            w.targetCadenceSpm = when {
                t < 30 -> 90.0
                t < 60 -> 115.0
                else -> 80.0
            }
        }
        val played = r.playedTempo().filter { it.first > 10 }
        val changes = played.zipWithNext { (_, a), (_, b) -> kotlin.math.abs(b / a - 1) }.sorted()
        val p95 = changes[(changes.size * 95) / 100]
        val worst = changes.last()
        val walkerAt = r.walkerTrace.toMap()
        val arrivedUp = r.cueTrace.first { it.first >= 30 && kotlin.math.abs(it.second - (walkerAt[it.first] ?: 0.0)) <= 4 && it.second > 105 }.first - 30
        val arrivedDown = r.cueTrace.first { it.first >= 60 && kotlin.math.abs(it.second - (walkerAt[it.first] ?: 0.0)) <= 4 && it.second < 90 }.first - 60
        println("music follow: beat-to-beat tempo change p95 %.1f%%, worst %.1f%%; reached the new pace %.0f s after the speed-up and %.0f s after the slow-down"
            .format(p95 * 100, worst * 100, arrivedUp, arrivedDown))
        assertTrue("p95 change ${p95 * 100}%", p95 <= 0.035)
        assertTrue("worst change ${worst * 100}%", worst <= 0.065)
        assertTrue("arrived up in $arrivedUp s", arrivedUp <= 12.0)
        assertTrue("arrived down in $arrivedDown s", arrivedDown <= 12.0)
    }

    @Test
    fun music_neverResyncJumps_evenAfterAStop() {
        val freeze = FreezeEpisode(startSec = 40.0, durationSec = 4.3)
        val cfg = WalkerConfig.PARKINSONIAN.copy(freezes = listOf(freeze), entrainStrength = 0.3, phaseCorrection = 0.05, seed = 13)
        var resyncs = 0
        val r = run(cfg, seconds = 80.0, followOnly = true, music = true, log = { _, m -> if (m.startsWith("re-sync")) resyncs++ })
        val worst = r.playedTempo().filter { it.first > 10 }.zipWithNext { (_, a), (_, b) -> kotlin.math.abs(b / a - 1) }.max()
        println("music after a stop: re-syncs $resyncs, worst beat-to-beat tempo change %.1f%%".format(worst * 100))
        assertEquals(0, resyncs)
        assertTrue("worst change ${worst * 100}%", worst <= 0.065)
    }
}
