package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import kotlin.math.abs

/** Sync metrics, rolling and cumulative. Null values mean no steps have been recorded yet. */
data class SyncStats(
    /** Fraction of the last [Tuning.SYNC_ROLLING_STEPS] steps within ±[Tuning.SYNC_WINDOW_MS]. */
    val rollingPct: Double? = null,
    /** Mean |asynchrony| over the rolling window, ms. */
    val rollingMeanAbsMs: Double? = null,
    /** Fraction of all session steps within the window. */
    val sessionPct: Double? = null,
    /** Mean |asynchrony| over the session, ms. */
    val sessionMeanAbsMs: Double? = null,
    /** Mean signed asynchrony over the session, ms. Negative means steps tend to land before the beat
     *  (the "negative mean asynchrony" of the tapping literature). */
    val sessionMeanSignedMs: Double? = null,
    /** The number of steps measured, and whether the rolling window is full. */
    val count: Int = 0,
    val rollingFull: Boolean = false,
)

/** Accumulates step-vs-beat errors. Record only steps measured against an audible cue. Pure Kotlin. */
class SyncTracker(private val window: Int = Tuning.SYNC_ROLLING_STEPS) {
    private val windowNs = Tuning.SYNC_WINDOW_MS * 1_000_000L
    private val recent = ArrayDeque<Long>()
    private var count = 0
    private var inWindow = 0
    private var sumAbsNs = 0.0
    private var sumNs = 0.0

    fun add(errorNs: Long) {
        recent.addLast(errorNs)
        if (recent.size > window) recent.removeFirst()
        count++
        if (abs(errorNs) <= windowNs) inWindow++
        sumAbsNs += abs(errorNs).toDouble()
        sumNs += errorNs.toDouble()
    }

    fun stats(): SyncStats {
        if (count == 0) return SyncStats()
        return SyncStats(
            rollingPct = recent.count { abs(it) <= windowNs }.toDouble() / recent.size,
            rollingMeanAbsMs = recent.sumOf { abs(it).toDouble() } / recent.size / 1e6,
            sessionPct = inWindow.toDouble() / count,
            sessionMeanAbsMs = sumAbsNs / count / 1e6,
            sessionMeanSignedMs = sumNs / count / 1e6,
            count = count,
            rollingFull = recent.size >= window,
        )
    }

    /** Clears only the rolling window, for example after a deliberate tempo change. Session totals stay. */
    fun clearRolling() = recent.clear()

    fun reset() {
        recent.clear()
        count = 0
        inWindow = 0
        sumAbsNs = 0.0
        sumNs = 0.0
    }
}
