package com.tidalbyte.stridesync.audio

import com.tidalbyte.stridesync.Tuning
import kotlin.math.roundToLong

/**
 * The beat schedule in the audio-frame domain, plus the mixer that renders it. Pure Kotlin, so it's
 * JVM-testable. It isn't thread-safe; MetronomeEngine guards it with a lock.
 *
 * Beats land on exact sample frames. The ideal (fractional) beat position is accumulated as a Double and
 * rounded only at placement, so tempo never drifts. A beat's frame is decided only when rendering reaches
 * it. Until then, tempo and phase changes still apply to it. Once a beat is rendered, it's fixed.
 *
 * Changes are slew-limited per beat, so the cue never lurches:
 *  - The period moves toward its target by at most [Tuning.BEAT_MAX_PERIOD_CHANGE_PCT] per beat.
 *  - A requested phase shift is applied to the next beat, clamped to [Tuning.BEAT_MAX_PHASE_SHIFT_PCT] of
 *    the period. Any remainder is discarded, because the PLL re-measures the error and asks again each step.
 *  - While muted ([gain] == 0), both limits are lifted. Nothing is audible, so the schedule can jump
 *    straight to the right tempo and phase before the cue is unmuted.
 */
class BeatClock(
    initialPeriodFrames: Double,
    /** The frame of the first beat. Leave some lead-in so the first click isn't at stream start. */
    private val firstBeatFrame: Double,
    sound: FloatArray,
    /** Max period change per beat while audible, as a fraction. Follow mode raises it (see Tuning). */
    var maxPeriodChangePct: Double = Tuning.BEAT_MAX_PERIOD_CHANGE_PCT,
    /** Max phase shift per beat while audible, as a fraction. Follow mode raises it (see Tuning). */
    var maxPhaseShiftPct: Double = Tuning.BEAT_MAX_PHASE_SHIFT_PCT,
) {
    private class Voice(val samples: FloatArray, val startFrame: Long, val gain: Float)

    /** The sound used for beats placed from now on. Beats already placed keep theirs. */
    var sound: FloatArray = sound

    /** Volume for new beats, 0..1. A muted beat still advances the schedule (gain 0). */
    var gain: Float = 1f

    /** The period of the most recent beat interval, frames. */
    var periodFrames: Double = initialPeriodFrames
        private set

    /** The period the clock is slewing toward, frames. */
    var targetPeriodFrames: Double = initialPeriodFrames
        private set

    private var pendingShiftFrames = 0.0
    private var lastBeatIdeal = Double.NaN // unrounded position of the most recently placed beat
    private val voices = ArrayList<Voice>()

    /** The number of beats placed so far. */
    var beatCount: Long = 0
        private set

    fun setTargetPeriod(frames: Double) {
        require(frames > 0.0 && frames.isFinite())
        targetPeriodFrames = frames
    }

    private var pendingShiftUnclamped = false

    /** Requests that the next beat move by [frames] (positive = later). This replaces any request that
     *  hasn't been applied yet. [unclamped] skips the per-beat limit for this one shift (a deliberate re-sync). */
    fun requestPhaseShift(frames: Double, unclamped: Boolean = false) {
        pendingShiftFrames = frames
        pendingShiftUnclamped = unclamped
    }

    /**
     * Renders [len] frames starting at absolute frame [chunkStart] into [out], overwriting it. Calls
     * [onBeat] with the exact frame of each beat placed in this chunk.
     */
    fun render(chunkStart: Long, out: FloatArray, len: Int = out.size, onBeat: (frame: Long) -> Unit = {}) {
        val chunkEnd = chunkStart + len
        while (true) {
            val plan = planNext()
            var frame = plan.ideal.roundToLong()
            if (frame >= chunkEnd) break
            if (frame < chunkStart) frame = chunkStart // a late shift can't reach into already-written audio
            commit(plan, frame)
            onBeat(frame)
        }

        out.fill(0f, 0, len)
        val it = voices.iterator()
        while (it.hasNext()) {
            val v = it.next()
            val from = maxOf(chunkStart, v.startFrame)
            val to = minOf(chunkEnd, v.startFrame + v.samples.size)
            for (f in from until to) {
                out[(f - chunkStart).toInt()] += v.samples[(f - v.startFrame).toInt()] * v.gain
            }
            if (v.startFrame + v.samples.size <= chunkEnd) it.remove()
        }
    }

    private class Plan(val ideal: Double, val period: Double)

    private fun planNext(): Plan {
        if (lastBeatIdeal.isNaN()) return Plan(firstBeatFrame, periodFrames)
        if (gain == 0f) return Plan(lastBeatIdeal + targetPeriodFrames + pendingShiftFrames, targetPeriodFrames)
        val maxDp = maxPeriodChangePct * periodFrames
        val period = periodFrames + (targetPeriodFrames - periodFrames).coerceIn(-maxDp, maxDp)
        val maxShift = maxPhaseShiftPct * period
        val shift = if (pendingShiftUnclamped) pendingShiftFrames else pendingShiftFrames.coerceIn(-maxShift, maxShift)
        return Plan(lastBeatIdeal + period + shift, period)
    }

    private fun commit(plan: Plan, frame: Long) {
        periodFrames = plan.period
        pendingShiftFrames = 0.0
        pendingShiftUnclamped = false
        // Keep the unrounded position so rounding never accumulates. Only if the beat was clamped forward,
        // continue from where it actually sounded.
        lastBeatIdeal = if (frame > plan.ideal.roundToLong()) frame.toDouble() else plan.ideal
        beatCount++
        voices += Voice(sound, frame, gain)
    }
}
