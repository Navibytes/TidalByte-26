package com.tidalbyte.stridesync

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
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

    var sourceKind by mutableStateOf(SourceKind.SIMULATED)
        private set
    var simCadence by mutableFloatStateOf(WalkerConfig.PARKINSONIAN.cadenceSpm.toFloat())
        private set
    var walkerEntrains by mutableStateOf(true)
        private set
    var record by mutableStateOf(true)
        private set
    var placement by mutableStateOf(Placement.POCKET)
        private set
    var replays by mutableStateOf(emptyList<ReplayRef>())
        private set
    var selectedReplay by mutableStateOf<ReplayRef?>(null)
        private set
    var cueSound by mutableStateOf(CueSound.CLICK)
        private set

    val canStart: Boolean get() = sourceKind != SourceKind.REPLAY || selectedReplay != null

    init {
        SessionHub.setCueSound(cueSound)
        refreshReplays()
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

    fun updateCueSound(sound: CueSound) {
        cueSound = sound
        SessionHub.setCueSound(sound)
    }
}
