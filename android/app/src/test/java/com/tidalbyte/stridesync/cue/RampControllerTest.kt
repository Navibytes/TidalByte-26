package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.gait.GaitStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RampControllerTest {
    private val ms = 1_000_000L
    private val sec = 1_000_000_000L

    private val good = SyncStats(rollingPct = 1.0, rollingMeanAbsMs = 10.0, count = 16, rollingFull = true)
    private val marginal = SyncStats(rollingPct = 0.7, rollingMeanAbsMs = 50.0, count = 16, rollingFull = true)
    private val lost = SyncStats(rollingPct = 0.3, rollingMeanAbsMs = 150.0, count = 16, rollingFull = true)

    /** Drives a RampController the way the pipeline does. The cue obeys every tempo command. */
    private inner class Driver(val ramp: RampController = RampController()) {
        var t = 10 * sec
        var cueBpm = Tuning.CUE_DEFAULT_BPM
        var lastStep: Long? = null
        val tempoHistory = ArrayList<Pair<CueState, Double>>() // (state after the command, bpm)
        private var seenVersion = 0

        fun steps(n: Int, intervalMs: Long = 600, sync: SyncStats = good, errMs: Long? = 0, spm: Double? = 100.0) {
            repeat(n) {
                t += intervalMs * ms
                lastStep = t
                ramp.onStep(t, GaitStats(spm = spm, cv = 0.03), sync, errMs?.let { it * ms }, 60 * ms, cueBpm)
                ramp.onTick(t, lastStep, cueBpm)
                apply()
            }
        }

        /** Steps for [seconds] of walking. */
        fun walk(seconds: Double, sync: SyncStats = good, spm: Double? = 100.0) =
            steps((seconds * 1000 / 600).toInt().coerceAtLeast(1), sync = sync, spm = spm)

        fun silence(seconds: Double) {
            val end = t + (seconds * sec).toLong()
            while (t < end) {
                t += 100 * ms
                ramp.onTick(t, lastStep, cueBpm)
                apply()
            }
        }

        fun apply() {
            if (ramp.commandVersion != seenVersion) {
                seenVersion = ramp.commandVersion
                ramp.commandedBpm?.let {
                    cueBpm = it
                    tempoHistory += ramp.state to it
                }
            }
        }

        /** Calibrate at 100 spm, acquire, and lock long enough to start ramping. */
        fun toRamping() {
            steps(Tuning.CALIBRATION_STEPS)
            steps(Tuning.PLL_ACQUIRE_STEPS)
            walk(Tuning.LOCK_MIN_SEC + 1)
            assertEquals(CueState.RAMPING, ramp.state)
        }
    }

    @Test
    fun calibratesSilently_thenLocksAtBaseline() {
        val d = Driver()
        d.steps(Tuning.CALIBRATION_STEPS - 1)
        assertEquals(CueState.CALIBRATING, d.ramp.state)
        assertTrue(d.ramp.muted)
        assertNull(d.ramp.coupling)
        d.steps(1)
        assertEquals(CueState.LOCKING, d.ramp.state)
        assertEquals(100.0, d.ramp.baselineSpm!!, 1e-6)
        assertEquals(110.0, d.ramp.maxBpm, 1e-6)
        assertEquals(100.0, d.cueBpm, 1e-6) // commanded to the baseline
        assertTrue("acquiring while muted", d.ramp.acquiring && d.ramp.muted)
        assertEquals(Coupling.ACQUIRE, d.ramp.coupling)
    }

    @Test
    fun acquisitionNeedsConsecutiveInWindowSteps() {
        val d = Driver()
        d.steps(Tuning.CALIBRATION_STEPS)
        d.steps(1, errMs = 10)
        d.steps(1, errMs = 200) // out of window: streak resets
        assertTrue(d.ramp.acquiring)
        d.steps(Tuning.PLL_ACQUIRE_STEPS, errMs = 10)
        assertFalse(d.ramp.acquiring)
        assertFalse(d.ramp.muted)
        assertEquals(Coupling.LOCK, d.ramp.coupling)
    }

    @Test
    fun locking_requiresSustainedSync_beforeRamping() {
        val d = Driver()
        d.steps(Tuning.CALIBRATION_STEPS)
        d.steps(Tuning.PLL_ACQUIRE_STEPS)
        d.walk(Tuning.LOCK_MIN_SEC - 2)
        assertEquals("not yet: too soon", CueState.LOCKING, d.ramp.state)
        d.walk(10.0, sync = marginal)
        assertEquals("not while marginal", CueState.LOCKING, d.ramp.state)
        d.walk(1.0)
        assertEquals(CueState.RAMPING, d.ramp.state)
        assertEquals(Coupling.LEAD, d.ramp.coupling)
        assertEquals(102.0, d.cueBpm, 1e-6) // the first step: +2%
        assertEquals(1, d.ramp.rampStepsTaken)
    }

    @Test
    fun ramping_holdsEachStep_andAdvancesOnlyInSync() {
        val d = Driver()
        d.toRamping()
        d.walk(Tuning.RAMP_HOLD_SEC - 2)
        assertEquals(102.0, d.cueBpm, 1e-6)
        d.walk(3.0)
        assertEquals(102.0 * 1.02, d.cueBpm, 1e-6)
        // Marginal at the next hold expiry: HOLDING, no increase.
        d.walk(Tuning.RAMP_HOLD_SEC + 1, sync = marginal)
        assertEquals(CueState.HOLDING, d.ramp.state)
        assertEquals(102.0 * 1.02, d.cueBpm, 1e-6)
        // Good sync through a full hold: back to RAMPING with one more step.
        d.walk(Tuning.RAMP_HOLD_SEC + 1)
        assertEquals(CueState.RAMPING, d.ramp.state)
        assertEquals(102.0 * 1.02 * 1.02, d.cueBpm, 1e-6)
    }

    @Test
    fun hardCaps_neverExceedBaselinePlus10pct_incrementsAtMost2pct() {
        val d = Driver()
        d.toRamping()
        d.walk(20 * 60.0) // 20 minutes of perfect sync
        assertEquals(CueState.HOLDING, d.ramp.state)
        assertEquals(110.0, d.cueBpm, 1e-6)
        assertTrue(d.ramp.events.last().reason.contains("cap"))
        var prev = 100.0
        for ((_, bpm) in d.tempoHistory) {
            assertTrue("$bpm over cap", bpm <= 110.0 + 1e-9)
            assertTrue("$prev → $bpm exceeds +2%", bpm <= prev * (1 + Tuning.RAMP_STEP_PCT) + 1e-9)
            prev = bpm
        }
        assertEquals(110.0, d.ramp.capBpm(500.0), 1e-9)
        assertEquals(110.0, d.ramp.rampUpFrom(109.5), 1e-9)
    }

    @Test
    fun hardCaps_absoluteCeiling() {
        val d = Driver()
        d.steps(Tuning.CALIBRATION_STEPS, intervalMs = 480) // 125 spm baseline
        assertEquals(125.0, d.ramp.baselineSpm!!, 1e-6)
        assertEquals(Tuning.ABS_MAX_CUE_BPM, d.ramp.maxBpm, 1e-9) // not 137.5
        assertEquals(Tuning.ABS_MAX_CUE_BPM, d.ramp.capBpm(1000.0), 1e-9)
        assertEquals(Tuning.ABS_MAX_CUE_BPM, RampController().capBpm(1000.0), 1e-9) // even before calibration
    }

    @Test
    fun syncLost_stepsBackOneLevel_settles_thenHolds() {
        val d = Driver()
        d.toRamping()
        d.walk(Tuning.RAMP_HOLD_SEC + 1)
        assertEquals(102.0 * 1.02, d.cueBpm, 1e-6)
        d.steps(1, sync = lost)
        assertEquals(CueState.STEPPING_BACK, d.ramp.state)
        assertEquals(102.0, d.cueBpm, 1e-6)
        assertEquals(Coupling.LEAD, d.ramp.coupling)
        d.walk(Tuning.STEP_BACK_SETTLE_SEC - 1, sync = lost) // sync isn't judged while settling
        assertEquals(CueState.STEPPING_BACK, d.ramp.state)
        assertEquals(102.0, d.cueBpm, 1e-6)
        d.walk(2.0)
        assertEquals(CueState.HOLDING, d.ramp.state)
        assertEquals(102.0, d.cueBpm, 1e-6)
    }

    @Test
    fun syncLostAtBaseTempo_relocks() {
        val d = Driver()
        d.toRamping()
        d.steps(1, sync = lost) // level 1 → level 0
        d.walk(Tuning.STEP_BACK_SETTLE_SEC + 1)
        assertEquals(CueState.HOLDING, d.ramp.state)
        assertEquals(0, d.ramp.rampLevel)
        d.steps(1, sync = lost)
        assertEquals(CueState.LOCKING, d.ramp.state)
        assertEquals(Coupling.LOCK, d.ramp.coupling)
        assertTrue(d.ramp.events.last().reason.contains("re-locking"))
    }

    @Test
    fun suddenCadenceDrop_stepsBack() {
        val d = Driver()
        d.toRamping()
        d.steps(1, spm = 100.0)
        assertEquals(CueState.RAMPING, d.ramp.state)
        d.steps(1, spm = 85.0) // >15% below the 102 cue
        assertEquals(CueState.STEPPING_BACK, d.ramp.state)
        assertEquals(100.0, d.cueBpm, 1e-6)
        assertTrue(d.ramp.events.last().reason.contains("cadence dropped"))
    }

    @Test
    fun paused_freezesTempo_neverIncreases_resumesHolding() {
        val d = Driver()
        d.toRamping()
        val before = d.cueBpm
        d.silence(Tuning.PAUSE_SEC + 0.2)
        assertEquals(CueState.PAUSED, d.ramp.state)
        assertNull(d.ramp.coupling)
        assertFalse("beat keeps playing", d.ramp.muted)
        d.silence(5 * 60.0) // far beyond any hold timer
        assertEquals(CueState.PAUSED, d.ramp.state)
        assertEquals(before, d.cueBpm, 1e-9)
        d.steps(1)
        assertEquals(CueState.HOLDING, d.ramp.state)
        assertEquals(before, d.cueBpm, 1e-9)
    }

    @Test
    fun pausedDuringLocking_resumesLocking() {
        val d = Driver()
        d.steps(Tuning.CALIBRATION_STEPS)
        d.steps(Tuning.PLL_ACQUIRE_STEPS)
        d.silence(Tuning.PAUSE_SEC + 0.2)
        assertEquals(CueState.PAUSED, d.ramp.state)
        d.steps(1)
        assertEquals(CueState.LOCKING, d.ramp.state)
    }

    @Test
    fun calibrationIgnoresPauses() {
        val d = Driver()
        d.steps(5)
        d.silence(10.0)
        assertEquals(CueState.CALIBRATING, d.ramp.state)
    }

    /** A randomized session: whatever happens, the tempo only rises in RAMPING, by ≤2%, and never above the cap. */
    @Test
    fun fuzz_capsHoldUnderArbitrarySequences() {
        for (seed in 1L..20L) {
            val rng = Random(seed)
            val d = Driver()
            d.steps(Tuning.CALIBRATION_STEPS, intervalMs = 550 + rng.nextLong(100))
            val cap = d.ramp.maxBpm
            var prev = d.cueBpm
            repeat(400) {
                when (rng.nextInt(10)) {
                    0 -> d.silence(rng.nextDouble() * 8)
                    1 -> d.steps(1 + rng.nextInt(20), sync = lost)
                    2, 3 -> d.steps(1 + rng.nextInt(20), sync = marginal, spm = 95.0 + rng.nextDouble() * 10)
                    4 -> d.steps(1, spm = 70.0)
                    else -> d.walk(rng.nextDouble() * 60)
                }
            }
            for ((state, bpm) in d.tempoHistory) {
                assertTrue("seed $seed: $bpm > cap $cap", bpm <= cap + 1e-9)
                if (bpm > prev + 1e-9) {
                    assertEquals("seed $seed: increase outside RAMPING", CueState.RAMPING, state)
                    assertTrue("seed $seed: $prev → $bpm", bpm <= prev * (1 + Tuning.RAMP_STEP_PCT) + 1e-9)
                }
                prev = bpm
            }
            // The event log is a consistent chain of transitions.
            var s = CueState.CALIBRATING
            for (e in d.ramp.events) {
                assertEquals("seed $seed: broken chain at $e", s, e.from)
                s = e.to
            }
            assertEquals(d.ramp.state, s)
        }
    }
}
