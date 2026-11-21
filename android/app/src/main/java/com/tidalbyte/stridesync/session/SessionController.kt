package com.tidalbyte.stridesync.session

import android.os.SystemClock
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.audio.MetronomeEngine
import com.tidalbyte.stridesync.gait.CadenceEstimator
import com.tidalbyte.stridesync.gait.StepDetector
import com.tidalbyte.stridesync.sensor.SensorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How the cue tempo is chosen. Temporary dev modes until RampController (M4) takes over. */
enum class CueMode(val label: String) {
    /** A constant tempo set in the UI. */
    FIXED("Fixed"),

    /** The tempo tracks measured cadence. Silent until a cadence is known. No phase lock yet (M3). */
    FOLLOW("Follow"),
}

/** A snapshot of the running session for the UI. */
data class LiveState(
    val running: Boolean = false,
    val sourceLabel: String = "",
    val elapsedSec: Double = 0.0,
    val spm: Double? = null,
    val cv: Double? = null,
    val stepCount: Int = 0,
    /** Seconds since the last detected step (null before the first one). */
    val sinceLastStepSec: Double? = null,
    val cueMode: CueMode = CueMode.FOLLOW,
    /** The engine's target tempo, bpm. Null while the cue is silent. */
    val cueBpm: Double? = null,
    /** Increments when each audible beat is heard (UI flash). */
    val beatCount: Long = 0,
)

/**
 * Owns the pipeline: SensorSource → StepDetector → CadenceEstimator → MetronomeEngine (the PLL and ramp
 * come in later milestones). The sensor stages all run on one coroutine, so the pure-Kotlin stages need no
 * locking. The engine has its own audio thread and thread-safe setters.
 */
class SessionController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile private var engine: MetronomeEngine? = null
    @Volatile private var cueMode = CueMode.FOLLOW
    @Volatile private var fixedBpm = Tuning.CUE_DEFAULT_BPM
    @Volatile private var cueSound = CueSound.CLICK
    @Volatile private var latestSpm: Double? = null

    fun setCueMode(mode: CueMode) {
        cueMode = mode
        SLog.i(TAG, "cue mode → $mode")
        applyCue()
    }

    fun setFixedBpm(bpm: Double) {
        fixedBpm = bpm
        applyCue()
    }

    fun setCueSound(sound: CueSound) {
        cueSound = sound
        engine?.setSound(sound)
    }

    fun start(source: SensorSource) {
        if (job?.isActive == true) return
        val detector = StepDetector()
        val cadence = CadenceEstimator()
        latestSpm = null
        _state.value = LiveState(running = true, sourceLabel = source.label, cueMode = cueMode)
        SLog.i(TAG, "session start, source=${source.label}, cue=$cueMode")

        val eng = MetronomeEngine().apply {
            setSound(cueSound)
            setMuted(cueMode == CueMode.FOLLOW) // follow mode stays silent until a cadence is known
            onBeat = ::onBeatPlaced
        }
        engine = eng
        eng.start(if (cueMode == CueMode.FIXED) fixedBpm else Tuning.CUE_DEFAULT_BPM)
        applyCue()

        job = scope.launch(Dispatchers.Default) {
            var startNs = Long.MIN_VALUE
            var lastUiNs = Long.MIN_VALUE
            try {
                source.samples().collect { s ->
                    if (startNs == Long.MIN_VALUE) startNs = s.tNanos
                    val steps = detector.process(s)
                    for (step in steps) {
                        if (!cadence.addStep(step.tNanos)) continue
                        val st = cadence.stats()
                        SLog.d(
                            "step",
                            "#%d t=%.3fs amp=%.3f spm=%s cv=%s".format(
                                st.stepCount, (step.tNanos - startNs) / 1e9, step.amplitude,
                                st.spm?.let { "%.1f".format(it) } ?: "-",
                                st.cv?.let { "%.3f".format(it) } ?: "-",
                            ),
                        )
                        if (st.spm != null) {
                            latestSpm = st.spm
                            applyCue()
                        }
                    }
                    if (steps.isNotEmpty() || s.tNanos - lastUiNs >= UI_PERIOD_NS) {
                        lastUiNs = s.tNanos
                        val st = cadence.stats()
                        _state.update {
                            it.copy(
                                elapsedSec = (s.tNanos - startNs) / 1e9,
                                spm = st.spm,
                                cv = st.cv,
                                stepCount = st.stepCount,
                                sinceLastStepSec = st.lastStepNanos?.let { t -> (s.tNanos - t) / 1e9 },
                            )
                        }
                    }
                }
            } finally {
                eng.stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine?.stop()
        engine = null
        _state.update { it.copy(running = false, cueBpm = null) }
        SLog.i(TAG, "session stop")
    }

    /** Pushes the current cue mode/tempo to the engine. Safe from any thread. */
    private fun applyCue() {
        val eng = engine ?: run {
            _state.update { it.copy(cueMode = cueMode) }
            return
        }
        val target: Double? = when (cueMode) {
            CueMode.FIXED -> fixedBpm
            CueMode.FOLLOW -> latestSpm
        }
        if (target != null) {
            eng.setBpm(target)
            eng.setMuted(false)
        } else {
            eng.setMuted(true)
        }
        _state.update { it.copy(cueMode = cueMode, cueBpm = target?.let { eng.bpm }) }
    }

    /** Audio thread → UI: flash when the beat is actually heard, not when it's written ~100 ms ahead. */
    private fun onBeatPlaced(beat: MetronomeEngine.Beat) {
        val eng = engine ?: return
        if (eng.muted) return
        val delayMs = (beat.tNanos - SystemClock.elapsedRealtimeNanos()) / 1_000_000
        scope.launch {
            if (delayMs > 0) delay(delayMs)
            _state.update { it.copy(beatCount = it.beatCount + 1) }
        }
    }

    private companion object {
        const val TAG = "session"
        const val UI_PERIOD_NS = 100_000_000L // UI refresh at ~10 Hz
    }
}
