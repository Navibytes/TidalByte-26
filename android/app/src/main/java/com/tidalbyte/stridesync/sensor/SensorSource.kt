package com.tidalbyte.stridesync.sensor

import kotlinx.coroutines.flow.Flow

/**
 * One accelerometer reading in m/s², including gravity. [tNanos] is in the elapsedRealtime (boot-time)
 * timebase: `SensorEvent.timestamp` for live data, and the same base for replay and simulation.
 */
data class AccelSample(val tNanos: Long, val x: Float, val y: Float, val z: Float)

/**
 * Live sensor, CSV replay and simulation all implement this, and all three feed the same pipeline.
 * The flow is cold: each collection starts a fresh run.
 */
interface SensorSource {
    val label: String
    fun samples(): Flow<AccelSample>
}
