package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.audio.BeatClock
import kotlin.math.roundToLong

/**
 * A [CueOutput] in virtual time, backed by the real [BeatClock]. [advanceTo] renders audio up to
 * now + write-ahead, as MetronomeEngine's writer thread does. Beat "heard" time = frame time + [latencyNs].
 * In tests, set latencyNs to the detector's lag, which plays the role of Tuning.OUTPUT_LATENCY_MS.
 */
class VirtualMetronome(
    private val startNs: Long,
    initialBpm: Double,
    private val latencyNs: Long,
    muted: Boolean = false,
) : CueOutput {
    private val sr = Tuning.AUDIO_SAMPLE_RATE
    private val clock = BeatClock(sr * 60.0 / initialBpm, firstBeatFrame = 0.15 * sr, sound = FloatArray(1))
    private val buf = FloatArray(Tuning.AUDIO_CHUNK_FRAMES)
    private val writeAheadNs = Tuning.AUDIO_WRITE_AHEAD_MS * 1_000_000L
    private var written = 0L
    private val heard = ArrayList<Long>()
    private val audible = ArrayList<Boolean>()

    /** Every beat placed so far: (heard time, audible?). */
    val beats: List<Pair<Long, Boolean>> get() = heard.zip(audible)

    /** Frame times (no latency) of audible beats: when the walker actually hears them. */
    val audibleBeatFrameTimes: List<Long> get() = beats.filter { it.second }.map { it.first - latencyNs }

    init {
        setMuted(muted)
    }

    fun frameTimeNs(frame: Long): Long = startNs + (frame * 1e9 / sr).roundToLong()

    fun advanceTo(nowNs: Long) {
        while (frameTimeNs(written) < nowNs + writeAheadNs) {
            clock.render(written, buf, buf.size) { f ->
                heard += frameTimeNs(f) + latencyNs
                audible += clock.gain > 0f
            }
            written += buf.size
        }
    }

    override fun recentBeatTimes(): List<Long> = heard.takeLast(32)

    override val periodNs: Double get() = clock.targetPeriodFrames * 1e9 / sr

    override fun setPeriodNs(periodNs: Double) = clock.setTargetPeriod(periodNs * sr / 1e9)

    override fun requestPhaseShiftNs(ns: Long, unclamped: Boolean) = clock.requestPhaseShift(ns * sr / 1e9, unclamped)

    override var muted: Boolean = muted
        private set

    override fun setMaxPerBeatChange(periodPct: Double, phasePct: Double) {
        clock.maxPeriodChangePct = periodPct
        clock.maxPhaseShiftPct = phasePct
    }

    override fun setMuted(muted: Boolean) {
        this.muted = muted
        clock.gain = if (muted) 0f else 1f
    }
}
