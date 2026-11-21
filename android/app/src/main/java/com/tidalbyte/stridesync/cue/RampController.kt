package com.tidalbyte.stridesync.cue

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.gait.GaitStats
import com.tidalbyte.stridesync.gait.median
import kotlin.math.min

enum class CueState(val label: String) {
    /** Silent. Measuring the baseline cadence. */
    CALIBRATING("Calibrating"),

    /** Cue at the walker's cadence with strong coupling: the beat follows. Begins with a muted, fast
     *  phase acquisition. */
    LOCKING("Locking"),

    /** Raising the tempo one step at a time, each held for RAMP_HOLD_SEC and only while in sync. */
    RAMPING("Ramping"),

    /** Constant tempo: at the cap, sync marginal, or settling after a step back or pause. */
    HOLDING("Holding"),

    /** Sync was lost (or cadence dropped): one ramp step down, then a short settle. */
    STEPPING_BACK("Stepping back"),

    /** No steps for PAUSE_SEC: tempo frozen, beat still playing, never increases. */
    PAUSED("Paused"),
}

/** A state transition, or a ramp step within RAMPING (from == to). For the log, the UI and the session summary. */
data class CueEvent(val tNanos: Long, val from: CueState, val to: CueState, val reason: String)

/** Timing knobs only. The hard caps are deliberately *not* configurable here; they come from Tuning. */
data class RampTiming(
    val calibrationSteps: Int = Tuning.CALIBRATION_STEPS,
    val lockMinSec: Double = Tuning.LOCK_MIN_SEC,
    val holdSec: Double = Tuning.RAMP_HOLD_SEC,
    val stepBackSettleSec: Double = Tuning.STEP_BACK_SETTLE_SEC,
    val pauseSec: Double = Tuning.PAUSE_SEC,
)

/**
 * The cue-tempo state machine. Pure Kotlin and deterministic, driven by [onStep] and [onTick].
 *
 * Outputs: [state], [coupling] (which PLL gains to use), [muted], and a tempo command ([commandedBpm],
 * applied by the caller whenever [commandVersion] changes). Outside LOCKING, the commanded tempo is
 * authoritative. In LOCKING, the PLL follows the walker, but its tempo still passes through [capBpm].
 *
 * HARD CAPS, enforced only here:
 *  - tempo ≤ baseline × (1 + MAX_RAMP_PCT), and ≤ ABS_MAX_CUE_BPM ([capBpm]).
 *  - a ramp increase ≤ RAMP_STEP_PCT of the previous tempo ([rampUpFrom]).
 *  - only RAMPING ever increases the tempo. PAUSED freezes it.
 */
class RampController(
    private val timing: RampTiming = RampTiming(),
    private val log: (component: String, msg: String) -> Unit = { _, _ -> },
) {
    var state: CueState = CueState.CALIBRATING
        private set

    var baselineSpm: Double? = null
        private set

    /** The tempo ceiling, bpm. The absolute ceiling until a baseline exists. */
    val maxBpm: Double
        get() = baselineSpm?.let { min(it * (1 + Tuning.MAX_RAMP_PCT), Tuning.ABS_MAX_CUE_BPM) } ?: Tuning.ABS_MAX_CUE_BPM

    /** In LOCKING (or PAUSED from it): still aligning phase with the cue muted. */
    var acquiring: Boolean = false
        private set

    var commandedBpm: Double? = null
        private set
    var commandVersion: Int = 0
        private set

    /** Incremented whenever the rolling sync window should restart, so later judgments see only fresh
     *  steps: after any tempo command, when a step-back settle ends, and when steps resume after a pause.
     *  Without this, steps from the walker's ~10 s re-adaptation would be judged as "sync lost" again,
     *  causing a cascade of step-backs. */
    var syncResetVersion: Int = 0
        private set

    /** The number of ramp increases so far (for the session summary). */
    var rampStepsTaken: Int = 0
        private set

    private val _events = ArrayList<CueEvent>()
    val events: List<CueEvent> get() = _events

    /** The ramp ladder: [0] = the locked base tempo when ramping began; [i] = after i net steps. */
    private val ladder = ArrayList<Double>()
    val rampLevel: Int get() = maxOf(0, ladder.size - 1)

    private val calibIntervals = ArrayList<Long>()
    private var calibSteps = 0
    private var lastCalibStepNs: Long? = null
    private var stateSinceNs = 0L
    private var holdStartNs = 0L
    private var audibleSinceNs = 0L
    private var acquireStreak = 0
    private var pausedFrom: CueState? = null

    val coupling: Coupling?
        get() = when (state) {
            CueState.CALIBRATING, CueState.PAUSED -> null
            CueState.LOCKING -> if (acquiring) Coupling.ACQUIRE else Coupling.LOCK
            CueState.RAMPING, CueState.HOLDING, CueState.STEPPING_BACK -> Coupling.LEAD
        }

    val muted: Boolean get() = state == CueState.CALIBRATING || acquiring

    /** THE tempo cap. Every tempo sent to the metronome passes through here. */
    fun capBpm(bpm: Double): Double = min(min(bpm, maxBpm), Tuning.ABS_MAX_CUE_BPM)

    /** One ramp increment from [fromBpm]: at most +RAMP_STEP_PCT, then capped. */
    fun rampUpFrom(fromBpm: Double): Double = capBpm(fromBpm * (1 + Tuning.RAMP_STEP_PCT))

    /**
     * Call for every accepted step. [errorNs] is the PLL error (null if not measured). [syncWindowNs] is
     * the current in-sync window. [cueBpm] is the metronome's current target tempo.
     */
    fun onStep(tNs: Long, gait: GaitStats, sync: SyncStats, errorNs: Long?, syncWindowNs: Long, cueBpm: Double) {
        when (state) {
            CueState.CALIBRATING -> calibrate(tNs)
            CueState.LOCKING -> {
                if (acquiring) {
                    acquireStreak = if (errorNs != null && kotlin.math.abs(errorNs) <= syncWindowNs) acquireStreak + 1 else 0
                    if (acquireStreak >= Tuning.PLL_ACQUIRE_STEPS) {
                        acquiring = false
                        audibleSinceNs = tNs
                        event(tNs, CueState.LOCKING, "phase acquired, cue audible at %.1f bpm".format(cueBpm))
                    }
                } else if (syncGood(sync) && secSince(audibleSinceNs, tNs) >= timing.lockMinSec) {
                    enterRamping(tNs, cueBpm, "in sync %s for %.0fs".format(pct(sync), secSince(audibleSinceNs, tNs)))
                }
            }
            CueState.RAMPING -> {
                if (checkLostOrDrop(tNs, gait, sync)) return
                if (secSince(holdStartNs, tNs) >= timing.holdSec) {
                    when {
                        !syncGood(sync) -> transition(tNs, CueState.HOLDING, "sync marginal (${pct(sync)}), not advancing")
                        current() >= maxBpm - EPS -> transition(tNs, CueState.HOLDING, "at cap %.1f bpm".format(maxBpm))
                        else -> rampUp(tNs)
                    }
                }
            }
            CueState.HOLDING -> {
                if (checkLostOrDrop(tNs, gait, sync)) return
                if (syncGood(sync) && secSince(holdStartNs, tNs) >= timing.holdSec && current() < maxBpm - EPS) {
                    transition(tNs, CueState.RAMPING, "in sync %s through a %.0fs hold".format(pct(sync), timing.holdSec))
                    rampUp(tNs)
                }
            }
            CueState.STEPPING_BACK -> settleIfDue(tNs)
            CueState.PAUSED -> {
                val to = if (pausedFrom == CueState.LOCKING) CueState.LOCKING else CueState.HOLDING
                transition(tNs, to, "steps resumed")
                syncResetVersion++
                if (to == CueState.LOCKING && !acquiring) audibleSinceNs = tNs
                pausedFrom = null
            }
        }
    }

    /** Call regularly (every sample is fine). Detects pauses and finishes the step-back settle. */
    fun onTick(tNs: Long, lastStepNs: Long?, cueBpm: Double) {
        when (state) {
            CueState.LOCKING, CueState.RAMPING, CueState.HOLDING, CueState.STEPPING_BACK -> {
                if (lastStepNs != null && secSince(lastStepNs, tNs) >= timing.pauseSec) {
                    pausedFrom = state
                    transition(tNs, CueState.PAUSED, "no steps for %.1fs; tempo frozen at %.1f bpm".format(secSince(lastStepNs, tNs), cueBpm))
                    if (pausedFrom != CueState.LOCKING) command(current())
                    return
                }
                if (state == CueState.STEPPING_BACK) settleIfDue(tNs)
            }
            CueState.CALIBRATING, CueState.PAUSED -> Unit
        }
    }

    private fun calibrate(tNs: Long) {
        val prev = lastCalibStepNs
        if (prev != null) {
            val iv = tNs - prev
            if (iv in Tuning.STEP_INTERVAL_MIN_MS * MS..Tuning.STEP_INTERVAL_MAX_MS * MS) calibIntervals += iv
        }
        lastCalibStepNs = tNs
        calibSteps++
        if (calibSteps >= timing.calibrationSteps && calibIntervals.size >= Tuning.CADENCE_MIN_INTERVALS) {
            val base = 60e9 / median(calibIntervals)
            baselineSpm = base
            acquiring = true
            acquireStreak = 0
            transition(
                tNs, CueState.LOCKING,
                "baseline %.1f spm from %d steps; cap %.1f bpm".format(base, calibSteps, maxBpm),
            )
            command(base)
        }
    }

    private fun enterRamping(tNs: Long, cueBpm: Double, reason: String) {
        ladder.clear()
        ladder += capBpm(cueBpm)
        transition(tNs, CueState.RAMPING, reason)
        rampUp(tNs)
    }

    private fun rampUp(tNs: Long) {
        val from = current()
        val to = rampUpFrom(from)
        if (to <= from + EPS) {
            transition(tNs, CueState.HOLDING, "at cap %.1f bpm".format(maxBpm))
            return
        }
        ladder += to
        rampStepsTaken++
        holdStartNs = tNs
        command(to)
        event(tNs, CueState.RAMPING, "ramp step %d: %.1f → %.1f bpm (+%.1f%%)".format(rampLevel, from, to, (to / from - 1) * 100))
    }

    /** RAMPING/HOLDING: step back on lost sync or a sudden cadence drop. Returns true if it did. */
    private fun checkLostOrDrop(tNs: Long, gait: GaitStats, sync: SyncStats): Boolean {
        val spm = gait.spm
        val reason = when {
            sync.rollingFull && (sync.rollingPct ?: 1.0) < Tuning.SYNC_LOST_PCT -> "sync lost (${pct(sync)})"
            spm != null && spm < current() * (1 - Tuning.CADENCE_DROP_PCT) ->
                "cadence dropped to %.1f spm (cue %.1f)".format(spm, current())
            else -> return false
        }
        stepBack(tNs, reason)
        return true
    }

    private fun stepBack(tNs: Long, reason: String) {
        if (rampLevel == 0) {
            ladder.clear()
            transition(tNs, CueState.LOCKING, "$reason at base tempo: re-locking to the walker")
            audibleSinceNs = tNs
            return
        }
        ladder.removeAt(ladder.lastIndex)
        transition(tNs, CueState.STEPPING_BACK, reason + ": back to %.1f bpm".format(current()))
        command(current())
    }

    private fun settleIfDue(tNs: Long) {
        if (secSince(stateSinceNs, tNs) >= timing.stepBackSettleSec) {
            transition(tNs, CueState.HOLDING, "settled at %.1f bpm".format(current()))
            syncResetVersion++
        }
    }

    private fun current(): Double = ladder.lastOrNull() ?: commandedBpm ?: baselineSpm ?: Tuning.CUE_DEFAULT_BPM

    private fun command(bpm: Double) {
        commandedBpm = capBpm(bpm)
        commandVersion++
        syncResetVersion++
    }

    private fun transition(tNs: Long, to: CueState, reason: String) {
        val from = state
        state = to
        stateSinceNs = tNs
        if (to == CueState.HOLDING || to == CueState.RAMPING) holdStartNs = tNs
        _events += CueEvent(tNs, from, to, reason)
        log("ramp", "$from → $to: $reason")
    }

    private fun event(tNs: Long, s: CueState, reason: String) {
        _events += CueEvent(tNs, s, s, reason)
        log("ramp", "$s: $reason")
    }

    private fun syncGood(s: SyncStats) = s.rollingFull && (s.rollingPct ?: 0.0) >= Tuning.SYNC_REQUIRED_PCT

    private fun pct(s: SyncStats) = s.rollingPct?.let { "%.0f%%".format(it * 100) } ?: "-"

    private fun secSince(fromNs: Long, toNs: Long) = (toNs - fromNs) / 1e9

    private companion object {
        const val MS = 1_000_000L
        const val EPS = 1e-6
    }
}
