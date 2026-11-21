package com.tidalbyte.stridesync.gait

import com.tidalbyte.stridesync.sensor.AccelSample
import com.tidalbyte.stridesync.sensor.FreezeEpisode
import com.tidalbyte.stridesync.sensor.WalkerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StepDetectorTest {

    private fun assertGood(
        name: String,
        score: GaitEval.Score,
        minRecall: Double = 0.99,
        minPrecision: Double = 0.99,
        maxResidualMs: Double = 20.0,
    ) {
        println("$name: $score")
        assertTrue("$name recall: $score", score.recall >= minRecall)
        assertTrue("$name precision: $score", score.precision >= minPrecision)
        assertTrue("$name timing (max): $score", score.maxResidualMs <= maxResidualMs)
        assertTrue("$name timing (p95): $score", score.p95ResidualMs <= 20.0)
        // The constant lag gets absorbed by the latency offset, but it should be modest and positive.
        assertTrue("$name lag: $score", score.medianLagMs in 0.0..150.0)
    }

    @Test
    fun parkinsonianGait_allStepsWithin20ms() {
        val run = GaitEval.run(WalkerConfig.PARKINSONIAN, seconds = 120.0)
        assertGood("parkinsonian", GaitEval.score(run))
    }

    @Test
    fun shufflingLowAmplitude_allStepsWithin20ms() {
        val run = GaitEval.run(WalkerConfig.SHUFFLING, seconds = 120.0)
        assertGood("shuffling", GaitEval.score(run))
    }

    @Test
    fun veryLowAmplitude_stillDetected() {
        // Weaker still: strikes under half the shuffling case, in proportionally more noise. Timing degrades
        // with SNR, so the requirement here is 95% of steps within ±20 ms and none beyond ±35 ms.
        val cfg = WalkerConfig.SHUFFLING.copy(impactAmp = 0.2, swayAmp = 0.14, walkNoise = 0.06, seed = 7)
        val run = GaitEval.run(cfg, seconds = 120.0)
        assertGood("very-low-amp", GaitEval.score(run), minRecall = 0.97, minPrecision = 0.97, maxResidualMs = 35.0)
    }

    @Test
    fun verySlowGait_40spm() {
        val cfg = WalkerConfig.SHUFFLING.copy(cadenceSpm = 40.0, seed = 3)
        val run = GaitEval.run(cfg, seconds = 150.0)
        assertGood("40spm", GaitEval.score(run))
    }

    @Test
    fun festination_fastTinySteps() {
        val cfg = WalkerConfig.SHUFFLING.copy(cadenceSpm = 150.0, impactAmp = 0.35, swayAmp = 0.2, seed = 4)
        val run = GaitEval.run(cfg, seconds = 90.0)
        assertGood("festination", GaitEval.score(run))
    }

    @Test
    fun healthyGait() {
        val run = GaitEval.run(WalkerConfig.HEALTHY, seconds = 90.0)
        assertGood("healthy", GaitEval.score(run))
    }

    @Test
    fun cadenceSweep_differentSeeds() {
        for ((i, spm) in listOf(50.0, 70.0, 90.0, 110.0, 130.0).withIndex()) {
            val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = spm, seed = 100L + i)
            assertGood("sweep-$spm", GaitEval.score(GaitEval.run(cfg, seconds = 90.0)))
        }
    }

    @Test
    fun stillPhone_noFalseSteps() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(startDelaySec = 1e6)
        val run = GaitEval.run(cfg, seconds = 60.0)
        assertEquals(0, run.detected.size)
    }

    @Test
    fun freezeWithTremor_noStepsDuringFreeze_resumesAfter() {
        val freeze = FreezeEpisode(startSec = 40.0, durationSec = 8.0)
        val cfg = WalkerConfig.PARKINSONIAN.copy(freezes = listOf(freeze), freezeTremorAmp = 0.25)
        val run = GaitEval.run(cfg, seconds = 90.0)
        val fStart = GaitEval.START_NS + (freeze.startSec * 1e9).toLong()
        val fEnd = fStart + (freeze.durationSec * 1e9).toLong()
        // Allow the last pre-freeze strike to be reported shortly after the freeze begins.
        val inFreeze = run.detected.count { it.tNanos in (fStart + 500_000_000L) until fEnd }
        assertEquals("steps detected during freeze", 0, inFreeze)
        assertGood("freeze", GaitEval.score(run))
    }

    @Test
    fun irregularDelivery_droppedSamplesAndGap() {
        val rng = Random(11)
        val run = GaitEval.run(WalkerConfig.PARKINSONIAN, seconds = 120.0) { i, s ->
            when {
                i in 6000 until 6030 -> null // a 300 ms hole
                rng.nextDouble() < 0.05 -> null // 5% random drops
                else -> s
            }
        }
        assertGood("irregular", GaitEval.score(run), minRecall = 0.98, minPrecision = 0.98)
    }

    @Test
    fun longDropout_doesNotCreateFakeSteps() {
        // A 2 s sensor dropout, longer than SENSOR_GAP_RESET_MS.
        val run = GaitEval.run(WalkerConfig.PARKINSONIAN, seconds = 90.0) { i, s -> if (i in 5000 until 5200) null else s }
        val gapEnd = GaitEval.START_NS + 52_000_000_000L
        val score = GaitEval.score(run.copy(truth = run.truth.filter { it !in (GaitEval.START_NS + 49_500_000_000L)..gapEnd }))
        println("dropout: $score")
        assertTrue("dropout extras: $score", score.extra <= 1)
    }

    @Test
    fun lowerSensorRate_50Hz() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(sampleRateHz = 50.0, seed = 9)
        assertGood("50Hz", GaitEval.score(GaitEval.run(cfg, seconds = 90.0)))
    }

    @Test
    fun outOfOrderAndDuplicateTimestamps_ignored() {
        var last: AccelSample? = null
        val run = GaitEval.run(WalkerConfig.PARKINSONIAN, seconds = 60.0) { i, s ->
            val out = if (i % 97 == 0 && last != null) last else s // re-deliver a stale sample now and then
            last = s
            out
        }
        assertGood("dupes", GaitEval.score(run))
    }
}
