package com.tidalbyte.stridesync.gait

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR section using RBJ cookbook coefficients, transposed direct form II, double precision. */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        /** Butterworth low-pass by default (Q = 1/√2). */
        fun lowPass(cutoffHz: Double, sampleRateHz: Double, q: Double = 1.0 / sqrt(2.0)): Biquad {
            val w0 = 2.0 * PI * cutoffHz / sampleRateHz
            val alpha = sin(w0) / (2.0 * q)
            val cosW = cos(w0)
            val a0 = 1.0 + alpha
            return Biquad(
                b0 = (1.0 - cosW) / 2.0 / a0,
                b1 = (1.0 - cosW) / a0,
                b2 = (1.0 - cosW) / 2.0 / a0,
                a1 = -2.0 * cosW / a0,
                a2 = (1.0 - alpha) / a0,
            )
        }
    }
}
