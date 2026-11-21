package com.tidalbyte.stridesync.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tidalbyte.stridesync.MainViewModel
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.cue.CueEvent
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.session.LiveState
import com.tidalbyte.stridesync.session.Placement
import com.tidalbyte.stridesync.session.SourceKind

@Composable
fun HomeScreen(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Android 13+: ask once for notification permission (the session notification). Start either way.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.start() }
    val onStart = {
        val needAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needAsk) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.start()
    }
    // A finished session may have produced a new recording.
    LaunchedEffect(state.running) { if (!state.running) vm.refreshReplays() }

    Scaffold(
        bottomBar = {
            Button(
                onClick = { if (state.running) vm.stop() else onStart() },
                enabled = state.running || vm.canStart,
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

            SourceCard(vm, state)
            ModeCard(vm, state)
            StatusCard(state)
            if (state.recentEvents.isNotEmpty()) EventsCard(state)

            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cue sound", style = MaterialTheme.typography.bodyMedium)
                    CueSound.entries.forEach { snd ->
                        FilterChip(
                            selected = vm.cueSound == snd,
                            onClick = { vm.updateCueSound(snd) },
                            enabled = snd != CueSound.MUSIC || vm.songInfo != null,
                            label = { Text(snd.label) },
                        )
                    }
                }
                Text(
                    vm.songInfo?.let { "Music: $it. Its tempo follows the cue (the pitch shifts slightly)." }
                        ?: "Music: no song bundled (see tools/prepare_song.py).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceCard(vm: MainViewModel, state: LiveState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Source", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SourceKind.entries.forEachIndexed { i, kind ->
                    SegmentedButton(
                        selected = vm.sourceKind == kind,
                        onClick = { vm.updateSourceKind(kind) },
                        enabled = !state.running,
                        shape = SegmentedButtonDefaults.itemShape(i, SourceKind.entries.size),
                    ) { Text(kind.label) }
                }
            }
            when (vm.sourceKind) {
                SourceKind.SIMULATED -> {
                    Text("Natural cadence: %.0f spm".format(vm.simCadence), style = MaterialTheme.typography.bodyMedium)
                    Slider(value = vm.simCadence, onValueChange = vm::updateSimCadence, valueRange = 40f..140f)
                    LabeledSwitch("Walker follows the cue", vm.walkerEntrains, enabled = !state.running, onChange = vm::updateWalkerEntrains)
                }
                SourceKind.LIVE -> {
                    Text(
                        "Phone in a front trouser pocket or on a belt clip. The screen can be off. " +
                            "Use the phone speaker or wired earbuds, not Bluetooth.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    LabeledSwitch("Record raw samples (CSV)", vm.record, enabled = !state.running, onChange = vm::updateRecord)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Placement", style = MaterialTheme.typography.bodyMedium)
                        Placement.entries.forEach { p ->
                            FilterChip(
                                selected = vm.placement == p,
                                onClick = { vm.updatePlacement(p) },
                                enabled = !state.running,
                                label = { Text(p.label) },
                            )
                        }
                    }
                    state.recordingName?.let {
                        Text("Recording to $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                SourceKind.REPLAY -> {
                    if (vm.replays.isEmpty()) {
                        Text("No recordings yet. Record a Live walk first.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        var open by remember { mutableStateOf(false) }
                        Box {
                            OutlinedButton(onClick = { open = true }, enabled = !state.running) {
                                Text(vm.selectedReplay?.name ?: "Choose a recording")
                            }
                            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                                vm.replays.forEach { r ->
                                    DropdownMenuItem(text = { Text(r.name) }, onClick = { vm.selectReplay(r); open = false })
                                }
                            }
                        }
                        Text(
                            "A recording can't react to the cue, so replay shows real-data detection and locking, not entrainment.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            state.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeCard(vm: MainViewModel, state: LiveState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Mode", style = MaterialTheme.typography.titleSmall)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to "Ramp", true to "Follow").forEachIndexed { i, (follow, label) ->
                    SegmentedButton(
                        selected = vm.followMode == follow,
                        onClick = { vm.updateFollowMode(follow) },
                        enabled = !state.running,
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            Text(
                if (vm.followMode) {
                    "The beat matches your steps as closely as possible. No tempo ramp."
                } else {
                    "Calibrate, lock to your steps, then raise the tempo 2% at a time while you stay in sync."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!vm.followMode) {
                LabeledSwitch("Demo timing (~1 min cycle)", vm.demoTiming, enabled = !state.running, onChange = vm::updateDemoTiming)
            }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
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
                if (state.running) stateLabel(state).uppercase() else "STOPPED",
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
                Stat("Stride CV", state.cv?.let { "%.1f %%".format(it * 100) } ?: "—")
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

private fun stateLabel(s: LiveState): String =
    if (s.followMode && s.cueState == CueState.LOCKING) "Following" else s.cueState.label

private fun stateDetail(s: LiveState): String = when {
    !s.running -> "Press Start, then walk"
    s.cueState == CueState.CALIBRATING -> "Measuring your cadence: ${s.calibrationLeft} steps to go"
    s.cueState == CueState.LOCKING && s.cueBpm == null -> "Finding the beat…"
    s.followMode && s.cueState == CueState.LOCKING -> "Beat matches your cadence"
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
