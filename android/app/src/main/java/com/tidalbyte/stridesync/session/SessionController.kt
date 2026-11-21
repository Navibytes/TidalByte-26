package com.tidalbyte.stridesync.session

import android.os.SystemClock
import com.tidalbyte.stridesync.SLog
import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.audio.MetronomeEngine
import com.tidalbyte.stridesync.cue.CueEvent
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.sensor.SensorSource
import com.tidalbyte.stridesync.sensor.SimulatedWalkerSource
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
    val cueState: CueState = CueState.CALIBRATING,
    /** Steps still needed to finish calibrating. */
    val calibrationLeft: Int = Tuning.CALIBRATION_STEPS,
    val baselineSpm: Double? = null,
    val maxBpm: Double? = null,
    val rampLevel: Int = 0,
    /** The engine's target tempo, bpm. Null while the cue is silent. */
    val cueBpm: Double? = null,
    /** Increments when each audible beat is heard (UI flash). */
    val beatCount: Long = 0,
    /** The latest step's error vs the nearest beat, ms (positive = step after beat). */
    val lastErrorMs: Double? = null,
    /** Rolling sync: % of recent steps in the window, and mean |asynchrony|. */
    val syncPct: Double? = null,
    val meanAbsAsyncMs: Double? = null,
    /** Most recent first. */
    val recentEvents: List<CueEvent> = emptyList(),
    val sessionStartNs: Long = 0L,
)

/**
 * Hosts a [GaitCuePipeline] (detector → cadence → PLL → ramp) on a coroutine fed by a [SensorSource], and
 * drives a [MetronomeEngine]. Every piece of real logic is in the pipeline (pure Kotlin, tested); this
 * class does the Android wiring and the UI state.
 */
class SessionController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LiveState())
    val state: StateFlow<LiveState> = _state.asStateFlow()

    private var job: Job? = null

    @Volatile private var engine: MetronomeEngine? = null
    @Volatile private var cueSound = CueSound.CLICK

    fun setCueSound(sound: CueSound) {
        cueSound = sound
        engine?.setSound(sound)
    }

    fun start(source: SensorSource) {
        if (job?.isActive == true) return
        _state.value = LiveState(running = true, sourceLabel = source.label)
        SLog.i(TAG, "session start, source=${source.label}")

        val eng = MetronomeEngine().apply {
            setSound(cueSound)
            setMuted(true) // calibration is silent
            onBeat = ::onBeatPlaced
        }
        engine = eng
        val pipe = GaitCuePipeline(eng, log = SLog::d)
        if (source is SimulatedWalkerSource) source.beatSource = eng::audibleBeatsHeardNs
        eng.start(Tuning.CUE_DEFAULT_BPM)

        job = scope.launch(Dispatchers.Default) {
            var startNs = Long.MIN_VALUE
            var lastUiNs = Long.MIN_VALUE
            var lastErrorMs: Double? = null
            var eventsSeen = 0
            try {
                source.samples().collect { s ->
                    if (startNs == Long.MIN_VALUE) {
                        startNs = s.tNanos
                        _state.update { it.copy(sessionStartNs = startNs) }
                    }
                    val reports = pipe.onSample(s)
                    for (r in reports) {
                        SLog.d(
                            "step",
                            "#%d t=%.3fs amp=%.3f spm=%s cv=%s err=%s state=%s".format(
                                r.gait.stepCount, (r.tNanos - startNs) / 1e9, r.amplitude,
                                r.gait.spm?.let { "%.1f".format(it) } ?: "-",
                                r.gait.cv?.let { "%.3f".format(it) } ?: "-",
                                r.errorMs?.let { "%+.0fms".format(it) } ?: "-",
                                r.state,
                            ),
                        )
                        if (r.errorMs != null) lastErrorMs = r.errorMs
                    }
                    val ramp = pipe.ramp
                    val newEvents = ramp.events.size != eventsSeen
                    if (reports.isNotEmpty() || newEvents || s.tNanos - lastUiNs >= UI_PERIOD_NS) {
                        lastUiNs = s.tNanos
                        eventsSeen = ramp.events.size
                        val g = pipe.gaitStats
                        val sy = pipe.syncStats
                        _state.update {
                            it.copy(
                                elapsedSec = (s.tNanos - startNs) / 1e9,
                                spm = g.spm,
                                cv = g.cv,
                                stepCount = g.stepCount,
                                sinceLastStepSec = g.lastStepNanos?.let { t -> (s.tNanos - t) / 1e9 },
                                cueState = ramp.state,
                                calibrationLeft = maxOf(0, Tuning.CALIBRATION_STEPS - g.stepCount),
                                baselineSpm = ramp.baselineSpm,
                                maxBpm = ramp.baselineSpm?.let { ramp.maxBpm },
                                rampLevel = ramp.rampLevel,
                                cueBpm = if (eng.muted) null else pipe.cueBpm,
                                lastErrorMs = lastErrorMs,
                                syncPct = sy.rollingPct,
                                meanAbsAsyncMs = sy.rollingMeanAbsMs,
                                recentEvents = if (newEvents) ramp.events.takeLast(4).reversed() else it.recentEvents,
                            )
                        }
                    }
                }
            } finally {
                eng.stop()
                val sy = pipe.syncStats
                SLog.i(
                    TAG,
                    "session end: state=%s baseline=%s rampSteps=%d inSync=%s meanAbs=%s meanSigned=%s".format(
                        pipe.ramp.state,
                        pipe.ramp.baselineSpm?.let { "%.1f".format(it) } ?: "-",
                        pipe.ramp.rampStepsTaken,
                        sy.sessionPct?.let { "%.0f%%".format(it * 100) } ?: "-",
                        sy.sessionMeanAbsMs?.let { "%.1fms".format(it) } ?: "-",
                        sy.sessionMeanSignedMs?.let { "%+.1fms".format(it) } ?: "-",
                    ),
                )
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

    /** Audio thread → UI: flash when the beat is actually heard, not when it's written ~100 ms ahead. */
    private fun onBeatPlaced(beat: MetronomeEngine.Beat) {
        if (!beat.audible) return
        val delayMs = (beat.tNanos - Tuning.DETECTOR_LAG_MS * 1_000_000L - SystemClock.elapsedRealtimeNanos()) / 1_000_000
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
