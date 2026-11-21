package com.tidalbyte.stridesync.session

import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.cue.Coupling
import com.tidalbyte.stridesync.cue.CueOutput
import com.tidalbyte.stridesync.cue.PhaseLockedLoop
import com.tidalbyte.stridesync.cue.SyncStats
import com.tidalbyte.stridesync.cue.SyncTracker
import com.tidalbyte.stridesync.gait.CadenceEstimator
import com.tidalbyte.stridesync.gait.GaitStats
import com.tidalbyte.stridesync.gait.StepDetector
import com.tidalbyte.stridesync.sensor.AccelSample
import kotlin.math.abs

/** How the cue tempo is chosen. Temporary dev modes; RampController (M4) replaces them. */
enum class CueMode(val label: String) {
    /** Lock to the walker: silent until a cadence is known, then a muted fast acquisition, then audible
     *  with strong coupling. The beat follows the walker. */
    LOCK("Lock to walker"),

    /** A fixed tempo from the UI with weak, phase-only coupling. The beat leads (as it will in RAMPING). */
    FIXED("Fixed tempo"),
}

/** Where LOCK mode is in its startup. */
enum class LockPhase(val label: String) {
    WAITING("Waiting for steps"),
    ACQUIRING("Finding the beat"),
    LOCKED("Locked"),
    LEADING("Leading"),
}

/** One processed step, for logging and the UI. */
data class StepReport(
    val tNanos: Long,
    val amplitude: Double,
    val gait: GaitStats,
    /** step − nearest beat, ms. Null if there was no beat to compare against. */
    val errorMs: Double?,
    val shiftMs: Double?,
    val cueBpm: Double,
    val phase: LockPhase,
    val coupling: String?,
)

/**
 * The per-sample pipeline: StepDetector → CadenceEstimator → PhaseLockedLoop → [CueOutput], plus sync
 * metrics. Pure Kotlin. It runs the same on the device (fed from a SensorSource, driving MetronomeEngine)
 * and in JVM closed-loop tests (driving a virtual BeatClock).
 *
 * Not thread-safe except for [mode] and [fixedBpm], which the UI may set from any thread. They take
 * effect on the next sample.
 */
class GaitCuePipeline(
    private val cue: CueOutput,
    private val log: (component: String, msg: String) -> Unit = { _, _ -> },
) {
    @Volatile var mode: CueMode = CueMode.LOCK
    @Volatile var fixedBpm: Double = Tuning.CUE_DEFAULT_BPM

    private val detector = StepDetector()
    private val cadence = CadenceEstimator()
    private val pll = PhaseLockedLoop()
    private val sync = SyncTracker()

    private var appliedMode: CueMode? = null
    private var appliedFixedBpm = Double.NaN
    private var acquireStreak = 0

    var phase: LockPhase = LockPhase.WAITING
        private set

    val gaitStats: GaitStats get() = cadence.stats()
    val syncStats: SyncStats get() = sync.stats()
    val cueBpm: Double get() = 60e9 / cue.periodNs

    fun onSample(s: AccelSample): List<StepReport> {
        applyMode()
        val steps = detector.process(s)
        if (steps.isEmpty()) return emptyList()
        val out = ArrayList<StepReport>(steps.size)
        for (step in steps) {
            if (!cadence.addStep(step.tNanos)) continue
            out += onStep(step.tNanos, step.amplitude)
        }
        return out
    }

    private fun applyMode() {
        val m = mode
        if (m != appliedMode) {
            log("cue", "mode ${appliedMode ?: "-"} → $m")
            appliedMode = m
            appliedFixedBpm = Double.NaN
            acquireStreak = 0
            sync.clearRolling()
            when (m) {
                CueMode.LOCK -> {
                    val spm = cadence.stats().spm
                    cue.setMuted(true)
                    if (spm != null) cue.setPeriodNs(60e9 / spm)
                    phase = if (spm != null) LockPhase.ACQUIRING else LockPhase.WAITING
                }
                CueMode.FIXED -> {
                    cue.setMuted(false)
                    phase = LockPhase.LEADING
                }
            }
        }
        if (m == CueMode.FIXED && fixedBpm != appliedFixedBpm) {
            appliedFixedBpm = fixedBpm
            cue.setPeriodNs(60e9 / fixedBpm)
            sync.clearRolling()
        }
    }

    private fun onStep(tNanos: Long, amplitude: Double): StepReport {
        val gait = cadence.stats()
        var coupling: Coupling? = null

        when (phase) {
            LockPhase.WAITING -> if (gait.spm != null) {
                cue.setPeriodNs(60e9 / gait.spm) // muted, so this applies immediately
                phase = LockPhase.ACQUIRING
                log("cue", "cadence %.1f spm → acquiring".format(gait.spm))
            }
            LockPhase.ACQUIRING -> coupling = Coupling.ACQUIRE
            LockPhase.LOCKED -> {
                val spm = gait.spm
                val cuePeriod = cue.periodNs
                if (spm != null && abs(60e9 / spm - cuePeriod) / cuePeriod > Tuning.PLL_FREQ_RESET_PCT) {
                    // A large frequency error: jump the tempo target to the cadence (slewed per beat).
                    // Skip the PI update this step.
                    log("pll", "freq reset %.1f → %.1f bpm".format(60e9 / cuePeriod, spm))
                    cue.setPeriodNs(60e9 / spm)
                    sync.clearRolling()
                } else {
                    coupling = Coupling.LOCK
                }
            }
            LockPhase.LEADING -> coupling = Coupling.LEAD
        }

        var errorMs: Double? = null
        var shiftMs: Double? = null
        if (coupling != null) {
            val periodBefore = cue.periodNs
            val c = pll.onStep(tNanos, cue.recentBeatTimes(), periodBefore, coupling)
            if (c != null) {
                errorMs = c.errorNs / 1e6
                shiftMs = c.shiftNs / 1e6
                cue.requestPhaseShiftNs(c.shiftNs)
                if (c.periodNs != periodBefore) cue.setPeriodNs(c.periodNs)
                if (!cue.muted) sync.add(c.errorNs)

                if (phase == LockPhase.ACQUIRING) {
                    acquireStreak = if (abs(c.errorNs) <= Tuning.SYNC_WINDOW_MS * 1_000_000L) acquireStreak + 1 else 0
                    if (acquireStreak >= Tuning.PLL_ACQUIRE_STEPS) {
                        cue.setMuted(false)
                        phase = LockPhase.LOCKED
                        log("cue", "acquired lock at %.1f bpm → audible".format(60e9 / cue.periodNs))
                    }
                }
                log(
                    "pll",
                    "err=%+.1fms shift=%+.1fms period=%.1f→%.1fms k=%s".format(
                        errorMs, shiftMs, periodBefore / 1e6, c.periodNs / 1e6, coupling.name,
                    ),
                )
            }
        }

        return StepReport(
            tNanos = tNanos,
            amplitude = amplitude,
            gait = gait,
            errorMs = errorMs,
            shiftMs = shiftMs,
            cueBpm = 60e9 / cue.periodNs,
            phase = phase,
            coupling = coupling?.name,
        )
    }
}
