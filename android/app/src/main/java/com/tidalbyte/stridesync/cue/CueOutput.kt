package com.tidalbyte.stridesync.cue

/**
 * What the cue logic needs from a metronome. MetronomeEngine implements it on the device. Tests use a
 * virtual-time BeatClock. All times are elapsedRealtime nanos, and "beat time" means the predicted time
 * the beat is *heard*, including Tuning.OUTPUT_LATENCY_MS.
 */
interface CueOutput {
    /** Heard times of the recently placed beats, ascending. May include beats a little in the future
     *  (already written, not yet heard). */
    fun recentBeatTimes(): List<Long>

    /** The current target beat period, ns. */
    val periodNs: Double

    /** Sets the target period. The engine slew-limits it per beat while audible. */
    fun setPeriodNs(periodNs: Double)

    /** Moves the next unwritten beat by [ns] (positive = later). Replaces any pending request, and is
     *  clamped per beat while audible unless [unclamped] (a deliberate re-sync). */
    fun requestPhaseShiftNs(ns: Long, unclamped: Boolean = false)

    val muted: Boolean
    fun setMuted(muted: Boolean)

    /** Sets the max tempo change and phase shift per beat while audible (fractions of the period). */
    fun setMaxPerBeatChange(periodPct: Double, phasePct: Double)
}
