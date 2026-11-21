package com.tidalbyte.stridesync.session

import android.os.SystemClock
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.audio.MetronomeEngine
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
    val cueMode: CueMode = CueMode.LOCK,
    val phase: LockPhase = LockPhase.WAITING,
    /** The engine's target tempo, bpm. Null while the cue is silent. */
    val cueBpm: Double? = null,
    /** Increments when each audible beat is heard (UI flash). */
    val beatCount: Long = 0,
    /** The latest step's error vs the nearest beat, ms (positive = step after beat). */
    val lastErrorMs: Double? = null,
    /** Rolling sync: % of recent steps within ±SYNC_WINDOW_MS, and mean |asynchrony|. */
    val syncPct: Double? = null,
    val meanAbsAsyncMs: Double? = null,
)

/**
 * Hosts a [GaitCuePipeline] on a coroutine fed by a [SensorSource], and drives a [MetronomeEngine]. Every
 * piece of real logic is in the pipeline (pure Kotlin, tested); this class does the Android wiring and the
 * UI state.
 */
class SessionController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile private var engine: MetronomeEngine? = null
    @Volatile private var pipeline: GaitCuePipeline? = null
    @Volatile private var cueMode = CueMode.LOCK
    @Volatile private var fixedBpm = Tuning.CUE_DEFAULT_BPM
    @Volatile private var cueSound = CueSound.CLICK

    fun setCueMode(mode: CueMode) {
        cueMode = mode
        pipeline?.mode = mode
        _state.update { it.copy(cueMode = mode) }
    }

    fun setFixedBpm(bpm: Double) {
        fixedBpm = bpm
        pipeline?.fixedBpm = bpm
    }

    fun setCueSound(sound: CueSound) {
        cueSound = sound
        engine?.setSound(sound)
    }

    fun start(source: SensorSource) {
        if (job?.isActive == true) return
        _state.value = LiveState(running = true, sourceLabel = source.label, cueMode = cueMode)
        SLog.i(TAG, "session start, source=${source.label}, cue=$cueMode")

        val eng = MetronomeEngine().apply {
            setSound(cueSound)
            setMuted(cueMode == CueMode.LOCK) // lock mode stays silent until it has acquired the beat
            onBeat = ::onBeatPlaced
        }
        val pipe = GaitCuePipeline(eng, log = SLog::d).apply {
            mode = cueMode
            fixedBpm = this@SessionController.fixedBpm
        }
        engine = eng
        pipeline = pipe
        eng.start(if (cueMode == CueMode.FIXED) fixedBpm else Tuning.CUE_DEFAULT_BPM)

        job = scope.launch(Dispatchers.Default) {
            var startNs = Long.MIN_VALUE
            var lastUiNs = Long.MIN_VALUE
            var lastErrorMs: Double? = null
            try {
                source.samples().collect { s ->
                    if (startNs == Long.MIN_VALUE) startNs = s.tNanos
                    val reports = pipe.onSample(s)
                    for (r in reports) {
                        SLog.d(
                            "step",
                            "#%d t=%.3fs amp=%.3f spm=%s cv=%s err=%s phase=%s".format(
                                r.gait.stepCount, (r.tNanos - startNs) / 1e9, r.amplitude,
                                r.gait.spm?.let { "%.1f".format(it) } ?: "-",
                                r.gait.cv?.let { "%.3f".format(it) } ?: "-",
                                r.errorMs?.let { "%+.0fms".format(it) } ?: "-",
                                r.phase,
                            ),
                        )
                        if (r.errorMs != null) lastErrorMs = r.errorMs
                    }
                    if (reports.isNotEmpty() || s.tNanos - lastUiNs >= UI_PERIOD_NS) {
                        lastUiNs = s.tNanos
                        val g = pipe.gaitStats
                        val sy = pipe.syncStats
                        _state.update {
                            it.copy(
                                elapsedSec = (s.tNanos - startNs) / 1e9,
                                spm = g.spm,
                                cv = g.cv,
                                stepCount = g.stepCount,
                                sinceLastStepSec = g.lastStepNanos?.let { t -> (s.tNanos - t) / 1e9 },
                                phase = pipe.phase,
                                cueBpm = if (eng.muted) null else pipe.cueBpm,
                                lastErrorMs = lastErrorMs,
                                syncPct = sy.rollingPct,
                                meanAbsAsyncMs = sy.rollingMeanAbsMs,
                            )
                        }
                    }
                }
            } finally {
                eng.stop()
                pipe.syncStats.let { sy ->
                    SLog.i(
                        TAG,
                        "session sync: steps=%d inWindow=%s meanAbs=%s meanSigned=%s".format(
                            sy.count,
                            sy.sessionPct?.let { "%.0f%%".format(it * 100) } ?: "-",
                            sy.sessionMeanAbsMs?.let { "%.1fms".format(it) } ?: "-",
                            sy.sessionMeanSignedMs?.let { "%+.1fms".format(it) } ?: "-",
                        ),
                    )
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        engine?.stop()
        engine = null
        pipeline = null
        _state.update { it.copy(running = false, cueBpm = null) }
        SLog.i(TAG, "session stop")
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
