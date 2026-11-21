package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Gains for the phase lock. Errors within ±[deadbandMs] get no phase correction. [clamped] = false lifts
 * the per-beat limits (only while the cue is muted).
 */
data class Coupling(
    val name: String,
    val kP: Double,
    val kI: Double,
    val deadbandMs: Int = Tuning.PLL_PHASE_DEADBAND_MS,
    val clamped: Boolean = true,
    /** Max phase shift per beat while clamped, as a fraction of the period. */
    val maxShiftPct: Double = Tuning.BEAT_MAX_PHASE_SHIFT_PCT,
) {
    companion object {
        /** The beat follows the walker (its tempo comes from cadence; see Tuning). */
        val LOCK = Coupling("lock", Tuning.PLL_LOCK_KP, Tuning.PLL_LOCK_KI)

        /** Follow mode: like LOCK, but recovers phase faster. */
        val FOLLOW = Coupling("follow", Tuning.PLL_LOCK_KP, Tuning.PLL_LOCK_KI, maxShiftPct = Tuning.FOLLOW_MAX_PHASE_SHIFT_PCT)

        /** With a song as the cue: gentle phase nudges whenever the beat follows (LOCK or FOLLOW). */
        val MUSIC_FOLLOW = Coupling("music", Tuning.MUSIC_PHASE_KP, 0.0, maxShiftPct = Tuning.MUSIC_MAX_PHASE_SHIFT_PCT)

        /** With a song as the cue while leading: LEAD's gain, with music's per-beat cap. */
        val MUSIC_LEAD = Coupling("music-lead", Tuning.PLL_LEAD_KP, 0.0, maxShiftPct = Tuning.MUSIC_MAX_PHASE_SHIFT_PCT)

        /** The beat leads and the walker must entrain. */
        val LEAD = Coupling("lead", Tuning.PLL_LEAD_KP, Tuning.PLL_LEAD_KI)

        /** Muted initial alignment: fast, unclamped, no dead band. */
        val ACQUIRE = Coupling("acquire", Tuning.PLL_ACQUIRE_KP, Tuning.PLL_ACQUIRE_KI, deadbandMs = 0, clamped = false)
    }
}

/**
 * The phase lock between detected steps and cue beats. Pure Kotlin and stateless: the loop's integrator is
 * the beat period itself, which the metronome holds.
 *
 * For each step: error = step − nearest beat, wrapped to ±period/2. Positive means the step came after the
 * beat. The correction shifts the next unwritten beat by kP × (the error beyond the dead band) and changes
 * the period by kI·error. While audible, each is clamped to [Tuning.BEAT_MAX_PHASE_SHIFT_PCT] /
 * [Tuning.BEAT_MAX_PERIOD_CHANGE_PCT] of the period, so the cue never lurches.
 */
class PhaseLockedLoop {

    data class Correction(
        /** step − nearest beat, ns. */
        val errorNs: Long,
        /** The beat the step was matched to, ns. */
        val beatNs: Long,
        /** The shift to request on the next beat, ns. */
        val shiftNs: Long,
        /** The new target period, ns. */
        val periodNs: Double,
    )

    /** Computes the correction for a step at [stepNs]. Returns null if there's no beat to compare against. */
    fun onStep(stepNs: Long, beatTimes: List<Long>, periodNs: Double, coupling: Coupling): Correction? {
        val (errorNs, beatNs) = phaseError(stepNs, beatTimes, periodNs) ?: return null
        return correct(errorNs, beatNs, periodNs, coupling)
    }

    /** The correction for a given (possibly pair-averaged) error. */
    fun correct(errorNs: Long, beatNs: Long, periodNs: Double, coupling: Coupling): Correction {
        val band = coupling.deadbandMs * 1_000_000L
        val beyond = when {
            errorNs > band -> errorNs - band
            errorNs < -band -> errorNs + band
            else -> 0L
        }
        var shift = coupling.kP * beyond
        var dPeriod = coupling.kI * errorNs
        if (coupling.clamped) {
            val maxShift = coupling.maxShiftPct * periodNs
            val maxDp = Tuning.BEAT_MAX_PERIOD_CHANGE_PCT * periodNs
            shift = shift.coerceIn(-maxShift, maxShift)
            dPeriod = dPeriod.coerceIn(-maxDp, maxDp)
        }
        return Correction(errorNs, beatNs, shift.roundToLong(), periodNs + dPeriod)
    }

    companion object {
        /**
         * Returns (step − nearest beat, nearest beat). [beatTimes] must be ascending. Beats after the last
         * known one are extrapolated at [periodNs], because at slow cadence the nearest beat may not be
         * written yet. Returns null if the step precedes all beats, or if the last beat is so old (more than
         * 2 periods) that the cue evidently isn't running.
         */
        fun phaseError(stepNs: Long, beatTimes: List<Long>, periodNs: Double): Pair<Long, Long>? {
            if (beatTimes.isEmpty() || periodNs <= 0.0) return null
            val idx = beatTimes.indexOfLast { it <= stepNs }
            if (idx < 0) return null
            var before = beatTimes[idx]
            val after: Long
            if (idx + 1 < beatTimes.size) {
                // Listed beats are ground truth, even mid-slew when their spacing differs from periodNs.
                after = beatTimes[idx + 1]
            } else {
                val since = stepNs - before
                if (since > 2 * periodNs) return null
                before += (floor(since / periodNs) * periodNs).roundToLong()
                after = before + periodNs.roundToLong()
            }
            val nearest = if (stepNs - before <= after - stepNs) before else after
            return Pair(stepNs - nearest, nearest)
        }
    }
}
