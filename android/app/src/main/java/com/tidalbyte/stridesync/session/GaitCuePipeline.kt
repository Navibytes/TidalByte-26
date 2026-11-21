package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.cue.CueOutput
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.cue.PhaseLockedLoop
import com.tidalbyte.stridesync.cue.RampController
import com.tidalbyte.stridesync.cue.RampTiming
import com.tidalbyte.stridesync.cue.SyncStats
import com.tidalbyte.stridesync.cue.SyncTracker
import com.tidalbyte.stridesync.cue.syncWindowNs
import com.tidalbyte.stridesync.gait.CadenceEstimator
import com.tidalbyte.stridesync.gait.GaitStats
import com.tidalbyte.stridesync.gait.StepDetector
import com.tidalbyte.stridesync.sensor.AccelSample
import kotlin.math.abs

/** One processed step, for logging and the UI. */
data class StepReport(
    val tNanos: Long,
    val amplitude: Double,
    val gait: GaitStats,
    /** The error the PLL and the sync metrics used (pair-averaged, step − beat), ms. Null if there was no
     *  beat to compare against. */
    val errorMs: Double?,
    /** This step alone vs its nearest beat, ms, before pair averaging. */
    val rawErrorMs: Double?,
    val shiftMs: Double?,
    val cueBpm: Double,
    val state: CueState,
    val coupling: String?,
)

/**
 * The per-sample pipeline: StepDetector → CadenceEstimator → PhaseLockedLoop → [CueOutput], plus sync
 * metrics, all driven by the [RampController] state machine. Pure Kotlin. It runs the same on the device
 * (fed from a SensorSource, driving MetronomeEngine) and in JVM closed-loop tests (driving a virtual
 * BeatClock).
 *
 * Phase errors are averaged over consecutive step pairs, so left/right alternation in the detected step
 * timing (the front-pocket artifact) cancels instead of dragging the beat back and forth. While the beat
 * follows the walker (LOCKING / Follow mode), its tempo is set from the measured cadence: a frequency-
 * locked loop, which a constant phase bias can't make drift (see Tuning, phase lock section).
 *
 * Every tempo it sends to the cue passes through [RampController.capBpm]. Not thread-safe: call from one
 * thread.
 */
class GaitCuePipeline(
    private val cue: CueOutput,
    timing: RampTiming = RampTiming(),
    /** Follow mode: the beat always matches the walker; no ramp. */
    val followOnly: Boolean = false,
    private val log: (component: String, msg: String) -> Unit = { _, _ -> },
) {
    private val detector = StepDetector()
    private val cadence = CadenceEstimator()
    private val pll = PhaseLockedLoop()
    private val sync = SyncTracker(timing.syncWindowSteps)
    val ramp = RampController(timing, followOnly, log)

    private var appliedCommand = 0
    private var appliedSyncReset = 0

    // The previous step's error, and the shift requested after it, for pair averaging.
    private var prevStepNs = Long.MIN_VALUE
    private var prevErrorNs = 0L
    private var prevShiftNs = 0L

    /** Follow mode: consecutive steps with a large same-sign error (signed count), for re-sync. */
    private var bigErrorSteps = 0

    /** Consecutive steps on which Follow mode's fast and steady cadence estimates disagreed (signed). */
    private var fastDisagreeSteps = 0

    /** Learned persistent step-vs-beat offset, ns (see Tuning.SYNC_OFFSET_TAU_STEPS). 0 while muted. */
    var offsetNs: Double = 0.0
        private set

    val gaitStats: GaitStats get() = cadence.stats()
    val syncStats: SyncStats get() = sync.stats()
    val cueBpm: Double get() = 60e9 / cue.periodNs

    init {
        cue.setMuted(ramp.muted) // CALIBRATING is silent
        if (followOnly) {
            cue.setMaxPerBeatChange(Tuning.FOLLOW_MAX_PERIOD_CHANGE_PCT, Tuning.FOLLOW_MAX_PHASE_SHIFT_PCT)
        } else {
            cue.setMaxPerBeatChange(Tuning.BEAT_MAX_PERIOD_CHANGE_PCT, Tuning.BEAT_MAX_PHASE_SHIFT_PCT)
        }
    }

    fun onSample(s: AccelSample): List<StepReport> {
        var out: MutableList<StepReport>? = null
        for (step in detector.process(s)) {
            if (!cadence.addStep(step.tNanos)) continue
            (out ?: ArrayList<StepReport>(1).also { out = it }).add(onStep(step.tNanos, step.amplitude))
        }
        ramp.onTick(s.tNanos, cadence.stats().lastStepNanos, cueBpm)
        applyRamp()
        return out ?: emptyList()
    }

    private fun onStep(tNanos: Long, amplitude: Double): StepReport {
        val gait = cadence.stats()
        val coupling = ramp.coupling
        val periodBefore = cue.periodNs
        var errorNs: Long? = null
        var rawNs: Long? = null
        var shiftNs: Long? = null

        // While the beat follows the walker, its tempo tracks the measured cadence (FLL).
        if (ramp.state == CueState.LOCKING) followTempo(gait, periodBefore)

        if (coupling != null) {
            val pe = PhaseLockedLoop.phaseError(tNanos, cue.recentBeatTimes(), periodBefore)
            if (pe != null) {
                val raw = pe.first
                val paired = prevStepNs != Long.MIN_VALUE && tNanos - prevStepNs <= 1.5 * periodBefore
                // The previous error, re-expressed against the grid after the shift that followed it.
                val averaged = if (paired) (raw + (prevErrorNs - prevShiftNs)) / 2 else raw
                // Remove the learned persistent offset: correct and judge only deviations from it.
                if (cue.muted) offsetNs = 0.0
                val used = averaged - offsetNs.toLong()
                val c = pll.correct(used, pe.second, periodBefore, coupling)
                rawNs = raw
                errorNs = used
                shiftNs = c.shiftNs
                if (resyncDue(used, periodBefore)) {
                    // Clearly off for several steps (a turn, a pace change): jump onto the steps in one go.
                    cue.requestPhaseShiftNs(used, unclamped = true)
                    shiftNs = used
                    log("pll", "re-sync: shifting the beat %+.0f ms".format(used / 1e6))
                } else {
                    cue.requestPhaseShiftNs(c.shiftNs)
                }
                if (c.periodNs != periodBefore) cue.setPeriodNs(60e9 / ramp.capBpm(60e9 / c.periodNs))
                if (!cue.muted) {
                    sync.add(used, periodBefore)
                    // Learn the offset only while the beat follows the walker. While it leads (ramping,
                    // holding), a growing lag means the walker isn't keeping up, and that must be judged,
                    // not absorbed.
                    if (ramp.state == CueState.LOCKING && abs(averaged - offsetNs) <= Tuning.SYNC_OFFSET_GATE_MS * 1e6) {
                        val max = Tuning.SYNC_OFFSET_MAX_MS * 1e6
                        offsetNs = (offsetNs + (averaged - offsetNs) / Tuning.SYNC_OFFSET_TAU_STEPS).coerceIn(-max, max)
                    }
                }
                prevStepNs = tNanos
                prevErrorNs = raw
                prevShiftNs = shiftNs
                log(
                    "pll",
                    "err=%+.1fms raw=%+.1fms offset=%+.1fms shift=%+.1fms period=%.1fms k=%s".format(
                        used / 1e6, raw / 1e6, offsetNs / 1e6, c.shiftNs / 1e6, cue.periodNs / 1e6, coupling.name,
                    ),
                )
            }
        }

        ramp.onStep(tNanos, gait, sync.stats(), errorNs, syncWindowNs(periodBefore), cueBpm)
        applyRamp()

        return StepReport(
            tNanos = tNanos,
            amplitude = amplitude,
            gait = gait,
            errorMs = errorNs?.let { it / 1e6 },
            rawErrorMs = rawNs?.let { it / 1e6 },
            shiftMs = shiftNs?.let { it / 1e6 },
            cueBpm = cueBpm,
            state = ramp.state,
            coupling = coupling?.name,
        )
    }

    /** Follow mode only: true when the error has exceeded FOLLOW_RESYNC_FRAC of the period, in the same
     *  direction, for FOLLOW_RESYNC_STEPS steps running. Resets the count when it fires. */
    private fun resyncDue(errorNs: Long, periodNs: Double): Boolean {
        if (!followOnly || ramp.state != CueState.LOCKING || ramp.acquiring || cue.muted) {
            bigErrorSteps = 0
            return false
        }
        val big = abs(errorNs) > Tuning.FOLLOW_RESYNC_FRAC * periodNs
        bigErrorSteps = when {
            !big -> 0
            errorNs > 0 -> if (bigErrorSteps > 0) bigErrorSteps + 1 else 1
            else -> if (bigErrorSteps < 0) bigErrorSteps - 1 else -1
        }
        if (abs(bigErrorSteps) >= Tuning.FOLLOW_RESYNC_STEPS) {
            bigErrorSteps = 0
            return true
        }
        return false
    }

    /**
     * LOCKING only: move the beat tempo toward the walker's measured cadence (a frequency-locked loop),
     * capped. It closes [Tuning.FLL_GAIN] of the gap per step, so estimate jitter doesn't drag the walker
     * around. In Follow mode, a sustained disagreement (over [Tuning.FOLLOW_FAST_SWITCH_PCT] for
     * [Tuning.FOLLOW_FAST_SWITCH_STEPS] steps) between the fast and steady estimates means a real speed change,
     * so it jumps straight to the fast estimate.
     */
    private fun followTempo(gait: GaitStats, periodBefore: Double) {
        val steady = gait.spm ?: return
        val fast = gait.fastSpm ?: steady
        val current = 60e9 / periodBefore
        val disagree = (fast - steady) / steady
        fastDisagreeSteps = when {
            disagree > Tuning.FOLLOW_FAST_SWITCH_PCT -> if (fastDisagreeSteps > 0) fastDisagreeSteps + 1 else 1
            disagree < -Tuning.FOLLOW_FAST_SWITCH_PCT -> if (fastDisagreeSteps < 0) fastDisagreeSteps - 1 else -1
            else -> 0
        }
        val changing = followOnly && abs(fastDisagreeSteps) >= Tuning.FOLLOW_FAST_SWITCH_STEPS
        val goal = if (changing) fast else steady
        val next = if (changing || ramp.acquiring) goal else current + Tuning.FLL_GAIN * (goal - current)
        val target = 60e9 / ramp.capBpm(next)
        if (abs(target - periodBefore) / periodBefore >= Tuning.FLL_MIN_UPDATE_PCT) cue.setPeriodNs(target)
    }

    /** Applies the ramp's mute state, any new tempo command, and any sync-window restart (after a tempo
     *  change, a step-back settle, or a pause), so the next sync judgment covers only fresh steps. */
    private fun applyRamp() {
        if (ramp.muted != cue.muted) cue.setMuted(ramp.muted)
        if (ramp.commandVersion != appliedCommand) {
            appliedCommand = ramp.commandVersion
            ramp.commandedBpm?.let { cue.setPeriodNs(60e9 / it) }
        }
        if (ramp.syncResetVersion != appliedSyncReset) {
            appliedSyncReset = ramp.syncResetVersion
            sync.clearRolling()
        }
    }
}
