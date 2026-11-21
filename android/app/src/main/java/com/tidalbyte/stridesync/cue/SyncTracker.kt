package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import kotlin.math.abs

/** The in-sync window for a given beat period, ns: ±max(SYNC_WINDOW_MS, SYNC_WINDOW_PERIOD_PCT × period). */
fun syncWindowNs(periodNs: Double): Long =
    maxOf(Tuning.SYNC_WINDOW_MS * 1_000_000L, (Tuning.SYNC_WINDOW_PERIOD_PCT * periodNs).toLong())

/** Sync metrics, rolling and cumulative. Null values mean no steps have been recorded yet. */
data class SyncStats(
    /** Fraction of the last [Tuning.SYNC_ROLLING_STEPS] steps within the sync window ([syncWindowNs]). */
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
    private class Entry(val errorNs: Long, val inSync: Boolean)

    private val recent = ArrayDeque<Entry>()
    private var count = 0
    private var inWindow = 0
    private var sumAbsNs = 0.0
    private var sumNs = 0.0

    /** Records one step's error against a beat of period [periodNs] (the period sets the window). */
    fun add(errorNs: Long, periodNs: Double) {
        val ok = abs(errorNs) <= syncWindowNs(periodNs)
        recent.addLast(Entry(errorNs, ok))
        if (recent.size > window) recent.removeFirst()
        count++
        if (ok) inWindow++
        sumAbsNs += abs(errorNs).toDouble()
        sumNs += errorNs.toDouble()
    }

    fun stats(): SyncStats {
        if (count == 0) return SyncStats()
        return SyncStats(
            rollingPct = if (recent.isEmpty()) null else recent.count { it.inSync }.toDouble() / recent.size,
            rollingMeanAbsMs = if (recent.isEmpty()) null else recent.sumOf { abs(it.errorNs).toDouble() } / recent.size / 1e6,
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
