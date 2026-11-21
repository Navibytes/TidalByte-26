package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.Tuning
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

    /** Feeds a first step at [start], then one step after each interval. Returns the last step time. */
    private fun feed(est: CadenceEstimator, intervalsMs: List<Long>, start: Long = 5_000 * ms): Long {
        var t = start
        est.addStep(t)
        for (iv in intervalsMs) {
            t += iv * ms
            est.addStep(t)
        }
        return t
    }

    private fun sampleCv(xs: List<Double>): Double {
        val mean = xs.average()
        return sqrt(xs.sumOf { (it - mean) * (it - mean) } / (xs.size - 1)) / mean
    }

    @Test
    fun nullUntilTwoStrides() {
        val est = CadenceEstimator()
        feed(est, listOf(600, 600)) // 3 steps = 1 two-step interval
        assertNull(est.stats().spm)
        assertNull(est.stats().fastSpm)
        est.addStep(est.stats().lastStepNanos!! + 600 * ms)
        assertNotNull(est.stats().spm)
    }

    @Test
    fun constantIntervals_exactCadenceZeroCv() {
        val est = CadenceEstimator()
        feed(est, List(10) { 600L })
        val s = est.stats()
        assertEquals(100.0, s.spm!!, 1e-9)
        assertEquals(100.0, s.fastSpm!!, 1e-9)
        assertEquals(0.0, s.cv!!, 1e-12)
        assertEquals(11, s.stepCount)
    }

    /** The front-pocket artifact from real walks: detections alternate 750/375 ms. True cadence is
     *  120 / 1.125 s = 106.7 spm, and it must read steady, not jump between 80 and 160. */
    @Test
    fun alternatingLongShortIntervals_cancelExactly() {
        val est = CadenceEstimator()
        val ivs = List(20) { if (it % 2 == 0) 750L else 375L }
        var t = 5_000 * ms
        est.addStep(t)
        for ((i, iv) in ivs.withIndex()) {
            t += iv * ms
            est.addStep(t)
            if (i >= 4) {
                assertEquals("after step ${i + 2}", 120.0 / 1.125, est.stats().spm!!, 1e-9)
                assertEquals(120.0 / 1.125, est.stats().fastSpm!!, 1e-9)
            }
        }
        assertEquals("stride time is perfectly steady", 0.0, est.stats().cv!!, 1e-12)
    }

    @Test
    fun slowParkinsonianCadence_notClipped() {
        val est = CadenceEstimator()
        feed(est, List(10) { 1500L }) // 40 spm
        assertEquals(40.0, est.stats().spm!!, 1e-9)
    }

    @Test
    fun medianToleratesSingleMissedStep() {
        val est = CadenceEstimator()
        // A missed detection doubles one interval. It corrupts 2 of the last 6 two-step intervals.
        feed(est, listOf(600, 600, 600, 600, 600, 600, 1200, 600, 600, 600))
        assertEquals(100.0, est.stats().spm!!, 1e-9)
    }

    @Test
    fun doubleDetection_rejectedAndDoesNotDisturbCadence() {
        val est = CadenceEstimator()
        val t = feed(est, List(8) { 600L })
        assertFalse(est.addStep(t + 100 * ms)) // too close: a double detection
        assertTrue(est.addStep(t + 600 * ms))
        assertEquals(100.0, est.stats().spm!!, 1e-9)
        assertEquals(10, est.stats().stepCount)
    }

    @Test
    fun longPause_restartsTheChain() {
        val est = CadenceEstimator()
        val t = feed(est, List(8) { 750L }) // 80 spm
        est.addStep(t + 5_000 * ms) // a freeze or stop
        est.addStep(t + 5_750 * ms)
        assertEquals(80.0, est.stats().spm!!, 1e-9) // no two-step interval spans the pause
        assertEquals(0.0, est.stats().cv!!, 1e-12)
    }

    @Test
    fun cv_isStrideTimeCv_overLastWindow() {
        val est = CadenceEstimator()
        val ivs = listOf(600L, 560, 640, 600, 580, 620, 590, 610, 600, 570, 630, 600, 610, 590)
        feed(est, ivs)
        val strides = ivs.zipWithNext { a, b -> (a + b).toDouble() }.takeLast(Tuning.CV_WINDOW_STRIDES)
        assertEquals(sampleCv(strides), est.stats().cv!!, 1e-9)
    }

    @Test
    fun fastEstimate_respondsSoonerThanMain() {
        val est = CadenceEstimator()
        var t = feed(est, List(10) { 600L }) // 100 spm
        for (i in 0 until 3) {
            t += 500 * ms // speeds up to 120 spm
            est.addStep(t)
        }
        val s = est.stats()
        assertEquals(120.0, s.fastSpm!!, 1e-9)
        assertTrue("main estimate still mostly old: ${s.spm}", s.spm!! < 110.0)
    }

    @Test
    fun endToEnd_walkerThroughDetector_cadenceAndCv() {
        for (spm in listOf(45.0, 72.0, 100.0)) {
            val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = spm, driftSpm = 0.0, intervalCv = 0.04, seed = spm.toLong())
            val run = GaitEval.run(cfg, seconds = 90.0)
            val est = CadenceEstimator()
            run.detected.forEach { est.addStep(it.tNanos) }
            val s = est.stats()
            println("e2e $spm spm → measured %.1f spm, stride cv=%.3f".format(s.spm, s.cv))
            assertEquals(spm, s.spm!!, spm * 0.04)
            assertTrue("cv ${s.cv}", s.cv!! in 0.005..0.06)
        }
    }

    @Test
    fun endToEnd_pocketArtifact_cadenceStillAccurate() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = 100.0, driftSpm = 0.0, farStepDelaySec = 0.17, seed = 21)
        val run = GaitEval.run(cfg, seconds = 90.0)
        val ivs = run.detected.zipWithNext { a, b -> (b.tNanos - a.tNanos) / 1e6 }.drop(10)
        val odd = ivs.filterIndexed { i, _ -> i % 2 == 0 }.sorted().let { it[it.size / 2] }
        val even = ivs.filterIndexed { i, _ -> i % 2 == 1 }.sorted().let { it[it.size / 2] }
        val est = CadenceEstimator()
        run.detected.forEach { est.addStep(it.tNanos) }
        val s = est.stats()
        println("pocket artifact: detected intervals alternate %.0f/%.0f ms → spm %.1f, stride cv %.3f".format(odd, even, s.spm, s.cv))
        assertTrue("artifact present", kotlin.math.abs(odd - even) > 200)
        assertEquals(100.0, s.spm!!, 3.0)
        assertTrue("stride cv ${s.cv}", s.cv!! < 0.06)
    }
}
