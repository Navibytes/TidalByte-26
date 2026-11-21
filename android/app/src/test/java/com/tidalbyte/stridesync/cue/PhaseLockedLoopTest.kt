package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PhaseLockedLoopTest {
    private val ms = 1_000_000L

    @Test
    fun phaseError_nearestBeat_signAndWrap() {
        val beats = listOf(0L, 1000 * ms, 2000 * ms)
        val p = 1000.0 * ms
        assertEquals(Pair(100 * ms, 1000 * ms), PhaseLockedLoop.phaseError(1100 * ms, beats, p))
        assertEquals(Pair(-400 * ms, 2000 * ms), PhaseLockedLoop.phaseError(1600 * ms, beats, p))
        assertEquals(Pair(0L, 1000 * ms), PhaseLockedLoop.phaseError(1000 * ms, beats, p))
        // Exactly halfway: the earlier beat wins, so the error is +P/2 (never past ±P/2).
        assertEquals(Pair(500 * ms, 1000 * ms), PhaseLockedLoop.phaseError(1500 * ms, beats, p))
    }

    @Test
    fun phaseError_extrapolatesPastLastBeat_andRejectsStaleOrEarly() {
        val beats = listOf(0L, 1000 * ms, 2000 * ms)
        val p = 1000.0 * ms
        assertEquals(Pair(300 * ms, 2000 * ms), PhaseLockedLoop.phaseError(2300 * ms, beats, p))
        assertEquals(Pair(-300 * ms, 3000 * ms), PhaseLockedLoop.phaseError(2700 * ms, beats, p))
        assertEquals(Pair(200 * ms, 3000 * ms), PhaseLockedLoop.phaseError(3200 * ms, beats, p))
        assertNull(PhaseLockedLoop.phaseError(4500 * ms, beats, p)) // cue evidently stopped
        assertNull(PhaseLockedLoop.phaseError(-5 * ms, beats, p)) // before the first beat
        assertNull(PhaseLockedLoop.phaseError(0L, emptyList(), p))
    }

    @Test
    fun phaseError_usesListedBeatsEvenWhenSpacingDiffersFromPeriod() {
        // Mid-slew: actual spacing 700 ms, target period already 1000 ms.
        val beats = listOf(0L, 700 * ms, 1400 * ms)
        assertEquals(Pair(-100 * ms, 1400 * ms), PhaseLockedLoop.phaseError(1300 * ms, beats, 1000.0 * ms))
    }

    @Test
    fun correction_clampedWhileAudible_unclampedWhileAcquiring() {
        val pll = PhaseLockedLoop()
        val p = 600.0 * ms
        val beats = listOf(0L, 600 * ms)
        val step = 600 * ms + 250 * ms // +250 ms error
        val locked = pll.onStep(step, beats, p, Coupling.LOCK)!!
        assertEquals((Tuning.BEAT_MAX_PHASE_SHIFT_PCT * p).toLong(), locked.shiftNs) // 0.5 × 225 ms, clamped to 30
        assertEquals("tempo isn't integrated from phase (cadence sets it)", p, locked.periodNs, 0.0)
        val acq = pll.onStep(step, beats, p, Coupling.ACQUIRE)!!
        assertEquals((Tuning.PLL_ACQUIRE_KP * 250 * ms).toLong(), acq.shiftNs) // no dead band, no clamp
        val lead = pll.onStep(600 * ms + 40 * ms, beats, p, Coupling.LEAD)!!
        assertEquals(p, lead.periodNs, 0.0) // leading never changes tempo
    }

    @Test
    fun deadband_smallErrorsUntouched_largerOnesCorrectedBeyondIt() {
        val pll = PhaseLockedLoop()
        val p = 600.0 * ms
        val band = Tuning.PLL_PHASE_DEADBAND_MS * ms
        assertEquals(0L, pll.correct(band, 0, p, Coupling.LOCK).shiftNs)
        assertEquals(0L, pll.correct(-band + 1, 0, p, Coupling.LOCK).shiftNs)
        assertEquals((Tuning.PLL_LOCK_KP * 10 * ms).toLong(), pll.correct(band + 10 * ms, 0, p, Coupling.LOCK).shiftNs)
        assertEquals(-(Tuning.PLL_LOCK_KP * 10 * ms).toLong(), pll.correct(-band - 10 * ms, 0, p, Coupling.LOCK).shiftNs)
    }

    /**
     * Ideal step times (no detector), with the real reporting delay: a step becomes known ~300 ms after it
     * happens. With matching tempo, LOCK coupling pulls a 150 ms phase offset into the dead band and
     * leaves it there.
     */
    @Test
    fun lockCoupling_pullsPhaseIntoDeadband_withReportingDelay() {
        val start = 10_000 * ms
        val period = 650 * ms
        val cue = VirtualMetronome(start, initialBpm = 60e9 / period, latencyNs = 0)
        val pll = PhaseLockedLoop()
        val errors = ArrayList<Long>()
        for (k in 0 until 40) {
            val step = start + 150 * ms + 150 * ms + k * period
            cue.advanceTo(step + 300 * ms)
            val c = pll.onStep(step, cue.recentBeatTimes(), cue.periodNs, Coupling.LOCK) ?: continue
            errors += c.errorNs
            cue.requestPhaseShiftNs(c.shiftNs)
        }
        println("lock errors (ms): " + errors.map { it / ms })
        val band = Tuning.PLL_PHASE_DEADBAND_MS * ms
        assertTrue("settled: ${errors.takeLast(10).map { it / ms }}", errors.takeLast(20).all { kotlin.math.abs(it) <= band + 1 * ms })
    }

    /** LEAD coupling with matched tempo aligns phase into the dead band but never changes the tempo. */
    @Test
    fun leadCoupling_alignsPhaseWithoutChangingTempo() {
        val start = 10_000 * ms
        val period = 600 * ms
        val cue = VirtualMetronome(start, initialBpm = 100.0, latencyNs = 0)
        val pll = PhaseLockedLoop()
        val errors = ArrayList<Long>()
        for (k in 0 until 80) {
            val step = start + 150 * ms + 200 * ms + k * period
            cue.advanceTo(step + 300 * ms)
            val c = pll.onStep(step, cue.recentBeatTimes(), cue.periodNs, Coupling.LEAD) ?: continue
            errors += c.errorNs
            cue.requestPhaseShiftNs(c.shiftNs)
            cue.setPeriodNs(c.periodNs)
        }
        assertEquals(period.toDouble(), cue.periodNs, 0.01)
        val band = Tuning.PLL_PHASE_DEADBAND_MS * ms
        assertTrue("lead errors ${errors.takeLast(5).map { it / ms }}", errors.takeLast(10).all { kotlin.math.abs(it) <= band + 1 * ms })
    }

    @Test
    fun syncTracker_metrics() {
        val t = SyncTracker(window = 4)
        listOf(10L, -20L, 70L, -100L, 30L).forEach { t.add(it * ms, 600.0 * ms) } // 600 ms period → ±60 ms window
        val s = t.stats()
        assertEquals(5, s.count)
        assertEquals(3.0 / 5, s.sessionPct!!, 1e-9)
        assertEquals(46.0, s.sessionMeanAbsMs!!, 1e-9)
        assertEquals(-2.0, s.sessionMeanSignedMs!!, 1e-9)
        assertEquals(2.0 / 4, s.rollingPct!!, 1e-9) // the last 4: -20, 70, -100, 30
        assertEquals(55.0, s.rollingMeanAbsMs!!, 1e-9)
        t.clearRolling()
        t.add(0, 600.0 * ms)
        assertEquals(1.0, t.stats().rollingPct!!, 1e-9)
        assertEquals(6, t.stats().count)
    }

    @Test
    fun syncWindow_scalesWithTempo() {
        assertEquals(60 * ms, syncWindowNs(600.0 * ms)) // 100 bpm: the 60 ms floor
        assertEquals(60 * ms, syncWindowNs(750.0 * ms)) // 80 bpm: 8% = 60 ms
        assertEquals(80 * ms, syncWindowNs(1000.0 * ms)) // 60 bpm: ±80 ms
        val t = SyncTracker()
        t.add(75 * ms, 1000.0 * ms) // in sync at 60 bpm…
        t.add(75 * ms, 600.0 * ms) // …but not at 100 bpm
        assertEquals(0.5, t.stats().sessionPct!!, 1e-9)
    }
}
