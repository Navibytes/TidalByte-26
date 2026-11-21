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
        assertEquals((Tuning.BEAT_MAX_PHASE_SHIFT_PCT * p).toLong(), locked.shiftNs)
        assertTrue(locked.periodNs - p <= Tuning.BEAT_MAX_PERIOD_CHANGE_PCT * p + 1)
        val acq = pll.onStep(step, beats, p, Coupling.ACQUIRE)!!
        assertEquals((Tuning.PLL_ACQUIRE_KP * 250 * ms).toLong(), acq.shiftNs)
        val lead = pll.onStep(600 * ms + 10 * ms, beats, p, Coupling.LEAD)!!
        assertEquals(p, lead.periodNs, 0.0) // leading never changes tempo
    }

    /**
     * Ideal step times (no detector), with the real reporting delay: a step becomes known ~300 ms after it
     * happens. LOCK coupling must pull in a 150 ms phase offset and a 2.5% tempo mismatch.
     */
    @Test
    fun lockCoupling_convergesInPhaseAndFrequency_withReportingDelay() {
        val start = 10_000 * ms
        val walkerPeriod = 650 * ms
        val cue = VirtualMetronome(start, initialBpm = 60e9 / (667.0 * ms), latencyNs = 0)
        val pll = PhaseLockedLoop()
        val firstBeat = start + 150 * ms
        val errors = ArrayList<Long>()
        for (k in 0 until 60) {
            val step = firstBeat + 150 * ms + k * walkerPeriod
            cue.advanceTo(step + 300 * ms) // the step is reported 300 ms late
            val c = pll.onStep(step, cue.recentBeatTimes(), cue.periodNs, Coupling.LOCK) ?: continue
            errors += c.errorNs
            cue.requestPhaseShiftNs(c.shiftNs)
            cue.setPeriodNs(c.periodNs)
        }
        println("lock errors (ms): " + errors.map { it / ms })
        assertTrue("phase settled: ${errors.takeLast(10).map { it / ms }}", errors.takeLast(20).all { abs(it) < 3 * ms })
        assertEquals(walkerPeriod.toDouble(), cue.periodNs, 1.0 * ms)
    }

    /** LEAD coupling with matched tempo aligns phase but never changes the tempo. */
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
        assertTrue("lead errors ${errors.takeLast(5).map { it / ms }}", errors.takeLast(10).all { abs(it) < 5 * ms })
    }

    @Test
    fun syncTracker_metrics() {
        val t = SyncTracker(window = 4)
        listOf(10L, -20L, 70L, -100L, 30L).forEach { t.add(it * ms) } // 60 ms window
        val s = t.stats()
        assertEquals(5, s.count)
        assertEquals(3.0 / 5, s.sessionPct!!, 1e-9)
        assertEquals(46.0, s.sessionMeanAbsMs!!, 1e-9)
        assertEquals(-2.0, s.sessionMeanSignedMs!!, 1e-9)
        assertEquals(2.0 / 4, s.rollingPct!!, 1e-9) // the last 4: -20, 70, -100, 30
        assertEquals(55.0, s.rollingMeanAbsMs!!, 1e-9)
        t.clearRolling()
        t.add(0)
        assertEquals(1.0, t.stats().rollingPct!!, 1e-9)
        assertEquals(6, t.stats().count)
    }
}
