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
    /** step − nearest beat, ms. Null if there was no beat to compare against. */
    val errorMs: Double?,
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
 * Every tempo it sends to the cue passes through [RampController.capBpm]. Not thread-safe: call from one
 * thread.
 */
class GaitCuePipeline(
    private val cue: CueOutput,
    timing: RampTiming = RampTiming(),
    private val log: (component: String, msg: String) -> Unit = { _, _ -> },
) {
    private val detector = StepDetector()
    private val cadence = CadenceEstimator()
    private val pll = PhaseLockedLoop()
    private val sync = SyncTracker()
    val ramp = RampController(timing, log)

    private var appliedCommand = 0
    private var appliedSyncReset = 0

    val gaitStats: GaitStats get() = cadence.stats()
    val syncStats: SyncStats get() = sync.stats()
    val cueBpm: Double get() = 60e9 / cue.periodNs

    init {
        cue.setMuted(ramp.muted) // CALIBRATING is silent
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
        var shiftNs: Long? = null

        if (coupling != null) {
            val c = pll.onStep(tNanos, cue.recentBeatTimes(), periodBefore, coupling)
            if (c != null) {
                errorNs = c.errorNs
                shiftNs = c.shiftNs
                cue.requestPhaseShiftNs(c.shiftNs)
                if (ramp.state == CueState.LOCKING) followTempo(gait, c.periodNs, periodBefore)
                if (!cue.muted) sync.add(c.errorNs, periodBefore)
                log(
                    "pll",
                    "err=%+.1fms shift=%+.1fms period=%.1f→%.1fms k=%s".format(
                        c.errorNs / 1e6, c.shiftNs / 1e6, periodBefore / 1e6, cue.periodNs / 1e6, coupling.name,
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
            shiftMs = shiftNs?.let { it / 1e6 },
            cueBpm = cueBpm,
            state = ramp.state,
            coupling = coupling?.name,
        )
    }

    /** LOCKING only: the beat follows the walker's tempo through the PI integrator, or through a frequency
     *  reset when the error is large. Either way it's capped. */
    private fun followTempo(gait: GaitStats, pllPeriodNs: Double, periodBefore: Double) {
        val spm = gait.spm
        if (!ramp.acquiring && spm != null && abs(60e9 / spm - periodBefore) / periodBefore > Tuning.PLL_FREQ_RESET_PCT) {
            log("pll", "freq reset %.1f → %.1f bpm".format(60e9 / periodBefore, spm))
            cue.setPeriodNs(60e9 / ramp.capBpm(spm))
            sync.clearRolling()
        } else if (pllPeriodNs != periodBefore) {
            cue.setPeriodNs(60e9 / ramp.capBpm(60e9 / pllPeriodNs))
        }
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
