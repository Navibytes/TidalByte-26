package com.tidalbyte.stridesync.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * The phone's accelerometer (TYPE_ACCELEROMETER, including gravity) at ~100 Hz
 * ([Tuning.SENSOR_PERIOD_US]). It uses `event.timestamp` (the elapsedRealtime base), never arrival time,
 * because delivery is irregular and batched. Callbacks run on a dedicated thread, not the main looper. To
 * keep receiving events with the screen off, collect this from inside the foreground service.
 */
class LiveAccelSource(context: Context) : SensorSource {

    override val label = "Live accelerometer"

    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)

    override fun samples(): Flow<AccelSample> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            close(IllegalStateException("No accelerometer on this device"))
            return@callbackFlow
        }
        val thread = HandlerThread("StrideSync-sensor").apply { start() }
        var windowStart = Long.MIN_VALUE
        var windowCount = 0
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // e.values is reused by the framework, so copy it now.
                trySend(AccelSample(e.timestamp, e.values[0], e.values[1], e.values[2]))
                if (windowStart == Long.MIN_VALUE) windowStart = e.timestamp
                windowCount++
                val span = e.timestamp - windowStart
                if (span >= RATE_LOG_NS) {
                    SLog.d(TAG, "rate %.1f Hz (%d samples in %.1fs)".format(windowCount * 1e9 / span, windowCount, span / 1e9))
                    windowStart = e.timestamp
                    windowCount = 0
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) = Unit
        }
        val ok = sensorManager.registerListener(listener, sensor, Tuning.SENSOR_PERIOD_US, 0, Handler(thread.looper))
        SLog.i(TAG, "registered ${sensor.name} (${sensor.vendor}) ok=$ok minDelay=${sensor.minDelay}us")
        if (!ok) close(IllegalStateException("registerListener failed"))
        awaitClose {
            sensorManager.unregisterListener(listener)
            thread.quitSafely()
            SLog.i(TAG, "unregistered")
        }
    }.buffer(SAMPLE_BUFFER)

    private companion object {
        const val TAG = "sensor"
        const val RATE_LOG_NS = 10_000_000_000L
        const val SAMPLE_BUFFER = 4096 // ~40 s at 100 Hz, so a slow collector never drops samples
    }
}
