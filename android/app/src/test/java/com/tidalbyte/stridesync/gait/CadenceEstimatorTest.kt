package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class CadenceEstimatorTest {
    private val ms = 1_000_000L

    private fun feed(est: CadenceEstimator, intervalsMs: List<Long>, start: Long = 5_000 * ms): Long {
        var t = start
        est.addStep(t)
        for (iv in intervalsMs) {
            t += iv * ms
            est.addStep(t)
        }
        return t
    }

    @Test
    fun nullUntilEnoughIntervals() {
        val est = CadenceEstimator()
        feed(est, listOf(600, 600))
        assertNull(est.stats().spm)
        assertNull(est.stats().cv)
        est.addStep(est.stats().lastStepNanos!! + 600 * ms)
        assertNotNull(est.stats().spm)
    }

    @Test
    fun constantIntervals_exactCadenceZeroCv() {
        val est = CadenceEstimator()
        feed(est, List(10) { 600L })
        val s = est.stats()
        assertEquals(100.0, s.spm!!, 1e-9)
        assertEquals(0.0, s.cv!!, 1e-12)
        assertEquals(11, s.stepCount)
    }

    @Test
    fun slowParkinsonianCadence_notClipped() {
        val est = CadenceEstimator()
        feed(est, List(10) { 1500L }) // 40 spm
        assertEquals(40.0, est.stats().spm!!, 1e-9)
    }

    @Test
    fun medianIgnoresSingleMissedStep() {
        val est = CadenceEstimator()
        feed(est, listOf(600, 600, 600, 1200, 600, 600, 600, 600)) // one missed detection doubles an interval
        assertEquals(100.0, est.stats().spm!!, 1e-9)
    }

    @Test
    fun doubleDetection_rejectedAndDoesNotShortenNextInterval() {
        val est = CadenceEstimator()
        val t = feed(est, List(8) { 600L })
        assertFalse(est.addStep(t + 100 * ms)) // too close: a double detection
        assertTrue(est.addStep(t + 600 * ms)) // measured from t, not from the rejected step
        assertEquals(100.0, est.stats().spm!!, 1e-9)
        assertEquals(10, est.stats().stepCount)
    }

    @Test
    fun longPause_noIntervalRecorded_countingRestarts() {
        val est = CadenceEstimator()
        val t = feed(est, List(8) { 750L }) // 80 spm
        est.addStep(t + 5_000 * ms) // a freeze or stop: 5 s > max interval
        assertEquals(80.0, est.stats().spm!!, 1e-9) // the pause isn't averaged in
        assertEquals(0.0, est.stats().cv!!, 1e-12)
    }

    @Test
    fun cv_matchesSampleSdOverMean() {
        val est = CadenceEstimator()
        // Alternating 550/650 ms: mean 600, sample SD = 50·√(n/(n−1)).
        val ivs = List(12) { if (it % 2 == 0) 550L else 650L }
        feed(est, ivs)
        val n = 12.0
        val expected = 50.0 * sqrt(n / (n - 1)) / 600.0
        assertEquals(expected, est.stats().cv!!, 1e-9)
    }

    @Test
    fun endToEnd_walkerThroughDetector_cadenceAndCv() {
        for (spm in listOf(45.0, 72.0, 100.0)) {
            val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = spm, driftSpm = 0.0, intervalCv = 0.04, seed = spm.toLong())
            val run = GaitEval.run(cfg, seconds = 90.0)
            val est = CadenceEstimator()
            run.detected.forEach { est.addStep(it.tNanos) }
            val s = est.stats()
            println("e2e $spm spm → measured %.1f spm, cv=%.3f".format(s.spm, s.cv))
            assertEquals(spm, s.spm!!, spm * 0.04)
            assertTrue("cv ${s.cv}", s.cv!! in 0.015..0.08)
        }
    }
}
