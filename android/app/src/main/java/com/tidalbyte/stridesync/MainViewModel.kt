package com.tidalbyte.stridesync

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.sensor.ReplayRef
import com.tidalbyte.stridesync.sensor.ReplaySource
import com.tidalbyte.stridesync.sensor.WalkerConfig
import com.tidalbyte.stridesync.session.Placement
import com.tidalbyte.stridesync.session.SessionHub
import com.tidalbyte.stridesync.session.SourceKind
import com.tidalbyte.stridesync.session.SourceSpec

/**
 * UI selections plus pass-through to [SessionHub]. The session itself runs in CueService, so it outlives
 * this ViewModel, and onCleared deliberately doesn't stop it.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    val state = SessionHub.state

    var sourceKind by mutableStateOf(SourceKind.LIVE) // the phone's own sensor; the practice walker is in settings
        private set
    var simCadence by mutableFloatStateOf(WalkerConfig.PARKINSONIAN.cadenceSpm.toFloat())
        private set
    var walkerEntrains by mutableStateOf(true)
        private set
    var record by mutableStateOf(true)
        private set
    var placement by mutableStateOf(Placement.LOWER_BACK) // the recommended spot (best sync in tests)
        private set
    var replays by mutableStateOf(emptyList<ReplayRef>())
        private set
    var selectedReplay by mutableStateOf<ReplayRef?>(null)
        private set
    var cueSound by mutableStateOf(CueSound.CLICK)
        private set
    var followMode by mutableStateOf(false)
        private set
    var demoTiming by mutableStateOf(true)
        private set

    /** The bundled song once loaded; null means the Music cue is unavailable. */
    var song by mutableStateOf<SongMeta?>(null)
        private set

    /** FAKE (Profile): nothing is uploaded; milestone 6 was skipped. */
    var shareWithTherapist by mutableStateOf(true)
        private set

    /** Profile → Text size. Extra large is the size the design was drawn at. */
    var textSize by mutableStateOf(TextSize.EXTRA_LARGE)
        private set

    val canStart: Boolean get() = sourceKind != SourceKind.REPLAY || selectedReplay != null

    init {
        SessionHub.setCueSound(cueSound)
        refreshReplays()
        viewModelScope.launch {
            val song = withContext(Dispatchers.IO) { SessionHub.loadSong(getApplication()) }
            this@MainViewModel.song = song?.let { SongMeta(it.title, it.bpm) }
        }
    }

    fun refreshReplays() {
        replays = ReplaySource.list(getApplication())
        if (selectedReplay == null || selectedReplay !in replays) selectedReplay = replays.firstOrNull()
    }

    fun start() {
        if (!canStart) return
        SessionHub.requestStart(
            getApplication(),
            SourceSpec(
                kind = sourceKind,
                simCadence = simCadence.toDouble(),
                simEntrains = walkerEntrains,
                replay = selectedReplay,
                record = record,
                placement = placement,
                followMode = followMode,
                demoTiming = demoTiming,
            ),
        )
    }

    fun stop() = SessionHub.requestStop(getApplication())

    fun updateSourceKind(kind: SourceKind) {
        sourceKind = kind
        if (kind == SourceKind.REPLAY) refreshReplays()
    }

    fun updateSimCadence(spm: Float) {
        simCadence = spm
        SessionHub.setSimCadence(spm.toDouble())
    }

    /** Takes effect at the next Start. */
    fun updateWalkerEntrains(on: Boolean) {
        walkerEntrains = on
    }

    fun updateRecord(on: Boolean) {
        record = on
    }

    fun updatePlacement(p: Placement) {
        placement = p
    }

    fun selectReplay(r: ReplayRef) {
        selectedReplay = r
    }

    /** Takes effect at the next Start. */
    fun updateFollowMode(on: Boolean) {
        followMode = on
    }

    /** Takes effect at the next Start. */
    fun updateDemoTiming(on: Boolean) {
        demoTiming = on
    }

    fun updateCueSound(sound: CueSound) {
        cueSound = sound
        SessionHub.setCueSound(sound)
    }

    fun updateShareWithTherapist(on: Boolean) {
        shareWithTherapist = on
    }

    fun cycleTextSize() {
        textSize = TextSize.entries[(textSize.ordinal + 1) % TextSize.entries.size]
    }
}

data class SongMeta(val title: String, val bpm: Double) {
    /** "Stayin' Alive (Instrumental)" → "Stayin' Alive", to fit one line. */
    val shortTitle: String get() = title.substringBefore(" (").ifBlank { title }
}

/** Multiplies the system font scale. The design's sizes are "Extra large"; smaller steps shrink from there. */
enum class TextSize(val label: String, val scale: Float) {
    NORMAL("Normal", 0.88f),
    LARGE("Large", 0.94f),
    EXTRA_LARGE("Extra large", 1f),
}
