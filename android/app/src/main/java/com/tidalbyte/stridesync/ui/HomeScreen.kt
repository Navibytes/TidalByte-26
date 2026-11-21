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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import com.tidalbyte.stridesync.session.CueMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text("StrideSync", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Prototype — not a medical device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Source: Simulated walker", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Walker cadence: %.0f spm".format(vm.simCadence), style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = vm.simCadence,
                        onValueChange = vm::updateSimCadence,
                        valueRange = 40f..140f,
                    )
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Cue", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        CueMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = vm.cueMode == mode,
                                onClick = { vm.updateCueMode(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, CueMode.entries.size),
                            ) { Text(if (mode == CueMode.FOLLOW) "Follow cadence" else "Fixed tempo") }
                        }
                    }
                    if (vm.cueMode == CueMode.FIXED) {
                        Spacer(Modifier.height(8.dp))
                        Text("Cue tempo: %.0f bpm".format(vm.fixedBpm), style = MaterialTheme.typography.bodyMedium)
                        Slider(value = vm.fixedBpm, onValueChange = vm::updateFixedBpm, valueRange = 40f..140f)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
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
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        Stat("CV", state.cv?.let { "%.1f %%".format(it * 100) } ?: "—")
                        Stat("Steps", "${state.stepCount}")
                        Stat("Elapsed", formatElapsed(state.elapsedSec))
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = { if (state.running) vm.stop() else vm.start() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                colors = if (state.running) {
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                } else {
                    ButtonDefaults.buttonColors()
                },
            ) {
                Text(if (state.running) "Stop" else "Start", fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun BigReadout(value: String, label: String, pulseKey: Long, pulseColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 64.sp, fontWeight = FontWeight.Bold)
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

private fun formatElapsed(sec: Double): String {
    val s = sec.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
