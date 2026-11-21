package com.tidalbyte.stridesync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.sensor.SimulatedWalkerSource
import com.tidalbyte.stridesync.sensor.WalkerConfig
import com.tidalbyte.stridesync.session.SessionController

/** Hosts the SessionController for now. Milestone 5 moves it into CueService so it survives screen-off. */
class MainViewModel : ViewModel() {

    val controller = SessionController(viewModelScope)
    val state = controller.state

    var simCadence by mutableFloatStateOf(WalkerConfig.PARKINSONIAN.cadenceSpm.toFloat())
        private set
    var walkerEntrains by mutableStateOf(true)
        private set
    var cueSound by mutableStateOf(CueSound.CLICK)
        private set

    private var simSource: SimulatedWalkerSource? = null

    init {
        controller.setCueSound(cueSound)
    }

    fun start() {
        val cfg = WalkerConfig.PARKINSONIAN.copy(cadenceSpm = simCadence.toDouble(), entrains = walkerEntrains)
        val src = SimulatedWalkerSource(cfg)
        simSource = src
        controller.start(src)
    }

    fun stop() {
        controller.stop()
        simSource = null
    }

    fun updateSimCadence(spm: Float) {
        simCadence = spm
        simSource?.setCadence(spm.toDouble())
    }

    /** Takes effect at the next Start. */
    fun updateWalkerEntrains(on: Boolean) {
        walkerEntrains = on
    }

    fun updateCueSound(sound: CueSound) {
        cueSound = sound
        controller.setCueSound(sound)
    }

    override fun onCleared() {
        controller.stop()
    }
}
