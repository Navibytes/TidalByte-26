package com.tidalbyte.stridesync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tidalbyte.stridesync.MainViewModel
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.cue.CueEvent
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.session.LiveState

@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            Button(
                onClick = { if (state.running) vm.stop() else vm.start() },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .height(72.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (state.running) "Stop" else "Start", fontSize = 24.sp)
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text("StrideSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Prototype — not a medical device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SourceCard(vm)
            StatusCard(state)
            if (state.recentEvents.isNotEmpty()) EventsCard(state)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Cue sound", style = MaterialTheme.typography.bodyMedium)
                CueSound.entries.forEach { snd ->
                    FilterChip(
                        selected = vm.cueSound == snd,
                        onClick = { vm.updateCueSound(snd) },
                        label = { Text(snd.label) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceCard(vm: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Source: Simulated walker", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text("Natural cadence: %.0f spm".format(vm.simCadence), style = MaterialTheme.typography.bodyMedium)
            Slider(value = vm.simCadence, onValueChange = vm::updateSimCadence, valueRange = 40f..140f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Walker follows the cue",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = vm.walkerEntrains, onCheckedChange = vm::updateWalkerEntrains)
            }
        }
    }
}

@Composable
private fun StatusCard(state: LiveState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (state.running) state.cueState.label.uppercase() else "STOPPED",
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.running) stateColor(state.cueState) else MaterialTheme.colorScheme.outline,
            )
            Text(stateDetail(state), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                BigReadout(
                    value = state.spm?.let { "%.0f".format(it) } ?: "—",
                    label = "steps / min",
                    pulseKey = state.stepCount.toLong(),
                    pulseColor = MaterialTheme.colorScheme.primary,
                )
                BigReadout(
                    value = state.cueBpm?.let { "%.0f".format(it) } ?: "—",
                    label = "cue bpm",
                    pulseKey = state.beatCount,
                    pulseColor = MaterialTheme.colorScheme.tertiary,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Baseline", state.baselineSpm?.let { "%.0f".format(it) } ?: "—")
                Stat("Cap", state.maxBpm?.let { "%.0f".format(it) } ?: "—")
                Stat("Ramp", state.baselineSpm?.let { b -> state.cueBpm?.let { formatRampPct((it / b - 1) * 100) } } ?: "—")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("In sync", state.syncPct?.let { "%.0f %%".format(it * 100) } ?: "—")
                Stat("Mean async", state.meanAbsAsyncMs?.let { "%.0f ms".format(it) } ?: "—")
                Stat("CV", state.cv?.let { "%.1f %%".format(it * 100) } ?: "—")
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Stat("Steps", "${state.stepCount}")
                Stat("Last step", state.lastErrorMs?.let { "%+.0f ms".format(it) } ?: "—")
                Stat("Elapsed", formatElapsed(state.elapsedSec))
            }
        }
    }
}

@Composable
private fun EventsCard(state: LiveState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Recent changes", style = MaterialTheme.typography.titleSmall)
            state.recentEvents.forEach { e -> EventRow(e, state.sessionStartNs) }
        }
    }
}

@Composable
private fun EventRow(e: CueEvent, startNs: Long) {
    val head = if (e.from == e.to) e.to.label else "${e.from.label} → ${e.to.label}"
    Text(
        "%s  %s: %s".format(formatElapsed((e.tNanos - startNs) / 1e9), head, e.reason),
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun stateDetail(s: LiveState): String = when {
    !s.running -> "Press Start, then walk"
    s.cueState == CueState.CALIBRATING -> "Measuring baseline: ${s.calibrationLeft} steps to go"
    s.cueState == CueState.LOCKING && s.cueBpm == null -> "Finding the beat…"
    s.cueState == CueState.LOCKING -> "Beat follows your steps"
    s.cueState == CueState.RAMPING -> "Ramp step ${s.rampLevel}: holding, then up if in sync"
    s.cueState == CueState.HOLDING -> "Tempo steady"
    s.cueState == CueState.STEPPING_BACK -> "Easing the tempo back down"
    s.cueState == CueState.PAUSED -> "No steps. Beat continues; walk when ready"
    else -> ""
}

@Composable
private fun stateColor(s: CueState): Color = when (s) {
    CueState.CALIBRATING -> MaterialTheme.colorScheme.outline
    CueState.LOCKING -> Color(0xFF42A5F5)
    CueState.RAMPING -> Color(0xFF66BB6A)
    CueState.HOLDING -> Color(0xFF26A69A)
    CueState.STEPPING_BACK -> Color(0xFFFFA726)
    CueState.PAUSED -> Color(0xFFAB47BC)
}

@Composable
private fun BigReadout(value: String, label: String, pulseKey: Long, pulseColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 60.sp, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(pulseKey, pulseColor)
            Spacer(Modifier.size(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A dot that flashes each time [key] changes (a step, or a heard beat). */
@Composable
private fun Pulse(key: Long, color: Color) {
    val alpha = remember { Animatable(0.15f) }
    LaunchedEffect(key) {
        if (key == 0L) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(0.15f, tween(300))
    }
    Box(
        Modifier
            .size(16.dp)
            .alpha(alpha.value)
            .background(color, CircleShape),
    )
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

private fun formatRampPct(pct: Double): String = if (kotlin.math.abs(pct) < 0.5) "0 %" else "%+.0f %%".format(pct)

private fun formatElapsed(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
