package com.tidalbyte.stridesync.session

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.sensor.LiveAccelSource
import com.tidalbyte.stridesync.sensor.ReplayRef
import com.tidalbyte.stridesync.sensor.ReplaySource
import com.tidalbyte.stridesync.sensor.SensorSource
import com.tidalbyte.stridesync.sensor.SimulatedWalkerSource
import com.tidalbyte.stridesync.sensor.WalkerConfig
import com.tidalbyte.stridesync.service.CueService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SourceKind(val label: String) { SIMULATED("Simulated"), LIVE("Live"), REPLAY("Replay") }

enum class Placement(val label: String, val tag: String) {
    POCKET("Pocket", "pocket"),
    LOWER_BACK("Lower back", "lowerback"),
    BELT("Belt clip", "belt"),
}

/** Everything needed to start a session. */
data class SourceSpec(
    val kind: SourceKind,
    val simCadence: Double = WalkerConfig.PARKINSONIAN.cadenceSpm,
    val simEntrains: Boolean = true,
    val replay: ReplayRef? = null,
    /** Live only: also write raw samples to CSV. */
    val record: Boolean = false,
    val placement: Placement = Placement.POCKET,
    /** Follow mode: the beat always matches the walker; no ramp. */
    val followMode: Boolean = false,
    /** About a 1-minute ramp cycle (RampTiming.DEMO) instead of the clinical 45 s holds. */
    val demoTiming: Boolean = true,
)

/**
 * The one SessionController for the process, shared by [CueService] (which runs the session, so it
 * survives screen-off) and the UI (which observes [state]). Its scope belongs to the process, not to any
 * Activity or ViewModel.
 *
 * Starting goes through the service. The UI calls [requestStart], the service enters the foreground and
 * then calls [startSession], so sensors are always registered from inside the foreground service.
 */
object SessionHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val controller = SessionController(scope)

    val state: StateFlow<LiveState> get() = controller.state

    @Volatile private var pending: SourceSpec? = null
    @Volatile private var simSource: SimulatedWalkerSource? = null

    fun requestStart(context: Context, spec: SourceSpec) {
        pending = spec
        ContextCompat.startForegroundService(context, Intent(context, CueService::class.java).setAction(CueService.ACTION_START))
    }

    fun requestStop(context: Context) {
        context.startService(Intent(context, CueService::class.java).setAction(CueService.ACTION_STOP))
    }

    fun setSimCadence(spm: Double) {
        simSource?.setCadence(spm)
    }

    fun setCueSound(sound: CueSound) = controller.setCueSound(sound)

    // ── Called by CueService ──

    internal fun takePending(): SourceSpec? = pending.also { pending = null }

    internal fun startSession(context: Context, spec: SourceSpec) {
        val source: SensorSource = when (spec.kind) {
            SourceKind.SIMULATED -> {
                var cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = spec.simCadence, entrains = spec.simEntrains)
                // In Follow mode the walker leads: it changes pace deliberately and is only weakly pulled by the
                // beat. A strongly entraining walker would stay locked to the beat, and the slider would barely
                // move anything.
                if (spec.followMode) cfg = cfg.copy(entrainStrength = 0.3, phaseCorrection = 0.05)
                SimulatedWalkerSource(cfg).also { simSource = it }
            }
            SourceKind.LIVE -> LiveAccelSource(context)
            SourceKind.REPLAY -> ReplaySource(context, requireNotNull(spec.replay) { "no replay selected" })
        }
        val recordTo = if (spec.kind == SourceKind.LIVE && spec.record) newRecordingFile(context, spec.placement) else null
        controller.start(source, recordTo, followOnly = spec.followMode, demoTiming = spec.demoTiming)
    }

    internal fun stopSession() {
        controller.stop()
        simSource = null
    }

    private fun newRecordingFile(context: Context, placement: Placement): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(ReplaySource.recordingsDir(context), "walk_${stamp}_${placement.tag}.csv")
    }
}
