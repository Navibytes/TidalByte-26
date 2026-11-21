package com.tidalbyte.stridesync.audio

import kotlin.math.abs
import kotlin.math.ln

/**
 * Plays a [SongTrack] locked to [BeatClock]'s beats. Pure Kotlin; BeatClock calls it under the engine lock.
 *
 * BeatClock keeps deciding exactly when every beat is heard. This class fills the audio between beats with
 * the matching stretch of the song (song beat k → k+1), resampled so that each song beat lands exactly on the
 * clock's beat frame. The rate for each segment is (target song position − current position) / (frames until the
 * target beat). The target is a beat BeatClock placed in this chunk, or its currently planned next beat
 * (re-targeted every chunk). So tempo changes, PLL phase shifts and re-sync jumps all just bend the playback rate.
 * Pitch follows tempo (varispeed); no time-stretching.
 *
 * - Each cue beat covers [unitsPerBeat] half-beats of the song (1 = ½ beat, 2 = 1 beat, 4 = 2 beats), chosen once
 *   at the first render to keep the rate nearest 1.
 * - The loop wraps at a bar boundary with a short crossfade. Any position snap (a clamped rate) also crossfades.
 * - Gain ramps toward its target over [fadeFrames], so the muted acquisition fades straight into in-phase music.
 */
class SongRenderer(
    private val track: SongTrack,
    private val outRate: Int,
    private val fadeFrames: Int = (0.3 * outRate).toInt(),
    private val crossfadeFrames: Int = (0.006 * outRate).toInt(),
) {
    /** Song half-beats per cue beat (1, 2 or 4). 0 until the first render picks it. */
    var unitsPerBeat: Int = 0
        private set

    private var pos = Double.NaN // current source position, frames
    private var targetUnit = 0 // the song half-beat the next (unplaced) cue beat lands on
    private var gain = 0f
    private var xfadeLeft = 0 // remaining crossfade frames
    private var xfadePos = 0.0 // the old position being faded out
    private var xfadeRate = 1.0

    /**
     * Mixes [len] frames starting at absolute output frame [chunkStart] into [out] (adds to it).
     * [placed] holds the beat frames BeatClock placed in this chunk (ascending, [placedCount] of them).
     * [plannedNext] is the planned frame of the next unplaced beat. [cuePeriodFrames] is the current beat period.
     */
    fun render(
        chunkStart: Long,
        out: FloatArray,
        len: Int,
        placed: LongArray,
        placedCount: Int,
        plannedNext: Double,
        cuePeriodFrames: Double,
        gainTarget: Float,
    ) {
        if (unitsPerBeat == 0) start(chunkStart, plannedNext, placedCount, placed, cuePeriodFrames)
        var f = chunkStart
        for (k in 0 until placedCount) {
            val b = placed[k]
            renderTo(f, b, unitPositionWrapped(targetUnit), chunkStart, out, gainTarget)
            land(unitPositionWrapped(targetUnit))
            advance()
            f = b
        }
        val end = chunkStart + len
        if (f < end) {
            // Head for the planned next beat. If the plan is behind us (it shouldn't be), use the nominal rate.
            val toPlanned = plannedNext - f
            val target = unitPositionWrapped(targetUnit)
            val rate = if (toPlanned > 1.0) ((target - pos) / toPlanned) else nominalRate(cuePeriodFrames)
            renderSegment(f, end, rate, chunkStart, out, gainTarget)
        }
    }

    private fun start(chunkStart: Long, plannedNext: Double, placedCount: Int, placed: LongArray, cuePeriodFrames: Double) {
        // Pick ½, 1 or 2 song beats per cue beat, whichever keeps the rate nearest 1 (the least pitch shift).
        val songSecPerBeat = track.beatPeriodFrames / track.sampleRate
        val cueSecPerBeat = cuePeriodFrames / outRate
        unitsPerBeat = listOf(1, 2, 4).minBy { u -> abs(ln((u / 2.0) * songSecPerBeat / cueSecPerBeat)) }
        targetUnit = track.loopStartUnit
        // Start far enough before the first target that we reach it on time at the nominal rate.
        val firstBeatFrame = if (placedCount > 0) placed[0].toDouble() else plannedNext
        pos = (track.unitPosition(targetUnit) - (firstBeatFrame - chunkStart) * nominalRate(cuePeriodFrames)).coerceAtLeast(0.0)
    }

    private fun nominalRate(cuePeriodFrames: Double): Double =
        (unitsPerBeat / 2.0) * track.beatPeriodFrames / cuePeriodFrames

    /** Renders [from, to) so that the position reaches [target] exactly at frame [to]. */
    private fun renderTo(from: Long, to: Long, target: Double, chunkStart: Long, out: FloatArray, gainTarget: Float) {
        if (to <= from) return
        renderSegment(from, to, (target - pos) / (to - from), chunkStart, out, gainTarget)
    }

    private fun renderSegment(from: Long, to: Long, rawRate: Double, chunkStart: Long, out: FloatArray, gainTarget: Float) {
        // Guard against absurd rates (e.g. a beat moved right on top of us): a clamped rate leaves a residual
        // that land() snaps with a crossfade.
        val rate = rawRate.coerceIn(0.25, 4.0)
        val step = 1f / fadeFrames
        for (fr in from until to) {
            gain = when {
                gain < gainTarget -> minOf(gainTarget, gain + step)
                gain > gainTarget -> maxOf(gainTarget, gain - step)
                else -> gain
            }
            var s = track.sampleAt(pos)
            if (xfadeLeft > 0) {
                val w = xfadeLeft.toFloat() / crossfadeFrames
                s = s * (1 - w) + track.sampleAt(xfadePos) * w
                xfadePos += xfadeRate
                xfadeLeft--
            }
            if (gain > 0f) out[(fr - chunkStart).toInt()] += s * gain
            pos += rate
        }
    }

    /** At a placed beat the position must equal the target; snap any residual, crossfading if it's audible. */
    private fun land(target: Double) {
        if (abs(pos - target) > 2.0) startCrossfade(pos, 1.0)
        pos = target
    }

    /** Moves the target to the next cue beat's song position, wrapping the loop at a bar boundary. */
    private fun advance() {
        targetUnit += unitsPerBeat
        if (targetUnit >= track.loopEndUnit) {
            val span = track.loopEndUnit - track.loopStartUnit
            // Jump the position back by the loop length so playback continues seamlessly into the loop start.
            val shift = track.unitPosition(track.loopEndUnit) - track.unitPosition(track.loopStartUnit)
            startCrossfade(pos, 1.0)
            pos -= shift
            targetUnit -= span
        }
    }

    private fun unitPositionWrapped(unit: Int): Double = track.unitPosition(unit)

    private fun startCrossfade(oldPos: Double, rate: Double) {
        xfadePos = oldPos
        xfadeRate = rate
        xfadeLeft = crossfadeFrames
    }
}
