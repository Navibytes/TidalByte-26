package com.tidalbyte.stridesync.audio

/**
 * A prepared song: mono 16-bit samples plus its beat positions, from tools/prepare_song.py. Pure Kotlin.
 *
 * Positions are in *source* frames ([sampleRate]); the renderer converts implicitly. Half-beat positions
 * (midpoints) are included, so a cue beat can map to ½, 1 or 2 song beats.
 */
class SongTrack(
    val title: String,
    val bpm: Double,
    val sampleRate: Int,
    val samples: ShortArray,
    beats: DoubleArray,
    val loopStartBeat: Int,
    val loopEndBeat: Int,
) {
    /** Beat positions in source frames: index 2k = beat k, 2k+1 = the midpoint to beat k+1. */
    private val halfBeats: DoubleArray = DoubleArray(beats.size * 2 - 1) { i ->
        if (i % 2 == 0) beats[i / 2] else (beats[i / 2] + beats[i / 2 + 1]) / 2
    }

    init {
        require(beats.size >= 8) { "need at least 8 beats" }
        require(loopStartBeat in 0 until loopEndBeat && loopEndBeat < beats.size) { "bad loop $loopStartBeat..$loopEndBeat" }
    }

    /** The median beat period, in source frames. */
    val beatPeriodFrames: Double = beats.toList().zipWithNext { a, b -> b - a }.sorted().let { it[it.size / 2] }

    /** The half-beat units spanned by the loop. */
    val loopStartUnit: Int get() = loopStartBeat * 2
    val loopEndUnit: Int get() = loopEndBeat * 2

    fun unitPosition(unit: Int): Double = halfBeats[unit]

    /** A 4-point cubic Hermite (Catmull-Rom) interpolated sample at a fractional source position, in [-1, 1]. */
    fun sampleAt(pos: Double): Float {
        val i = kotlin.math.floor(pos).toInt()
        val f = (pos - i).toFloat()
        val y0 = at(i - 1)
        val y1 = at(i)
        val y2 = at(i + 1)
        val y3 = at(i + 2)
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * f + c2) * f + c1) * f + y1
    }

    private fun at(i: Int): Float = if (i < 0 || i >= samples.size) 0f else samples[i] / 32768f
}
