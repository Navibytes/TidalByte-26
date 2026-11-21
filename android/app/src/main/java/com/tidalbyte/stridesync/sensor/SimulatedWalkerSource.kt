package com.tidalbyte.stridesync.sensor

import android.os.SystemClock
import com.tidalbyte.stridesync.Tuning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * Plays a [SimulatedWalker] in real time, in the elapsedRealtime timebase, delivering samples in batches
 * like a real sensor. Everything downstream treats it exactly like the live accelerometer.
 */
class SimulatedWalkerSource(
    private val config: WalkerConfig,
    private val clockNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
) : SensorSource {

    override val label = "Simulated walker"

    @Volatile
    private var walker: SimulatedWalker? = null

    @Volatile
    private var targetCadence = config.cadenceSpm

    /** Changes the walker's cadence while it runs. It eases toward the new value over a few steps. */
    fun setCadence(spm: Double) {
        targetCadence = spm
        walker?.targetCadenceSpm = spm
    }

    override fun samples(): Flow<AccelSample> = flow {
        val w = SimulatedWalker(config, startNanos = clockNanos())
        w.targetCadenceSpm = targetCadence
        walker = w
        while (true) {
            val now = clockNanos()
            while (w.nextSampleNanos <= now) emit(w.next())
            delay(Tuning.SIM_BATCH_MS)
        }
    }.flowOn(Dispatchers.Default)
}
