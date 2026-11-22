package com.tidalbyte.stridesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tidalbyte.stridesync.MainViewModel
import com.tidalbyte.stridesync.Tuning
import com.tidalbyte.stridesync.audio.CueSound
import com.tidalbyte.stridesync.cue.CueState
import com.tidalbyte.stridesync.session.LiveState
import com.tidalbyte.stridesync.session.SourceKind
import java.time.LocalTime

@Composable
fun WalkScreen(vm: MainViewModel, state: LiveState, onStart: () -> Unit, onOpenSettings: () -> Unit, onOpenMusic: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        WalkContent(vm, state, onStart, onOpenSettings, onOpenMusic, Modifier.weight(1f))
        // While walking, Stop stays pinned above the nav (the live card can push the page past the fold).
        if (state.running) {
            FilledPill(
                "Stop walk",
                SsIcons.Stop,
                vm::stop,
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                color = Ss.Ink,
                height = 64.dp,
                fontSize = 21f,
            )
        }
    }
}

@Composable
private fun WalkContent(
    vm: MainViewModel,
    state: LiveState,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMusic: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(DemoProfile.initials, 62.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("${greeting()},", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
                Text(DemoProfile.firstName, fontSize = 27.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink)
            }
            CircleIconButton(SsIcons.Settings, "Session settings", onOpenSettings)
        }
        Spacer(Modifier.height(18.dp))

        if (state.running) LiveCard(vm, state) else PaceCard()

        if (!state.running) {
            Spacer(Modifier.height(24.dp))
            SectionTitle("How should the beat move?")
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ModeCard("Ramp", "Speeds up slowly", !vm.followMode, true, { vm.updateFollowMode(false) }, Modifier.weight(1f).fillMaxHeight())
                ModeCard("Follow", "Stays at your pace", vm.followMode, true, { vm.updateFollowMode(true) }, Modifier.weight(1f).fillMaxHeight())
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Beat sound")
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoundTile("Click", SsIcons.Click, vm.cueSound == CueSound.CLICK, true, { vm.updateCueSound(CueSound.CLICK) }, Modifier.weight(1f))
            SoundTile("Woodblock", SsIcons.Woodblock, vm.cueSound == CueSound.WOODBLOCK, true, { vm.updateCueSound(CueSound.WOODBLOCK) }, Modifier.weight(1f))
            SoundTile("Music", SsIcons.Music, vm.cueSound == CueSound.MUSIC, vm.song != null, { vm.updateCueSound(CueSound.MUSIC) }, Modifier.weight(1f))
        }
        val song = vm.song
        if (vm.cueSound == CueSound.MUSIC && song != null) {
            Spacer(Modifier.height(12.dp))
            SongCard(
                title = song.shortTitle,
                subtitle = "%.0f bpm · from your music".format(song.bpm),
                playing = state.running && state.cueBpm != null,
                onChange = onOpenMusic,
            )
        }

        Spacer(Modifier.height(if (state.running) 18.dp else 26.dp))
        if (!state.running) {
            FilledPill("Start walk", SsIcons.Play, onStart, Modifier.fillMaxWidth(), enabled = vm.canStart, height = 68.dp, fontSize = 21f)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            startHint(vm, state),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Ss.Muted,
            textAlign = TextAlign.Center,
            lineHeight = 21.sp,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${sourceLine(vm)} · Prototype — not a medical device.",
            modifier = Modifier.fillMaxWidth(),
            fontSize = 12.5.sp,
            color = Ss.Faint,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun PaceCard() {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ss.Card)
            .padding(start = 20.dp, end = 18.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Your natural pace", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            Text(
                "%.1f".format(DemoProfile.naturalPace),
                fontSize = 54.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Ss.Ink,
                letterSpacing = (-1.5).sp,
                lineHeight = 62.sp,
            )
            Text("steps per minute", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SsIcons.ChevronUp, null, tint = Ss.Red, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(
                    "%.1f since %s".format(DemoProfile.naturalPaceChange, DemoProfile.shortDate(DemoProfile.startDate)),
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ss.Red,
                )
            }
        }
        Sparkline(
            DemoProfile.walks.map { it.naturalSpm }.windowed(3) { it.average() }, // a 3-walk average: the trend
            Modifier
                .padding(bottom = 6.dp)
                .size(width = 112.dp, height = 58.dp),
        )
    }
}

/** Replaces the pace card while a walk is running: what the beat is doing, and the live numbers. */
@Composable
private fun LiveCard(vm: MainViewModel, state: LiveState) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Ss.Card)
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(11.dp).background(stateColor(state.cueState), CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(stateTitle(state), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(if (state.followMode) "Follow" else "Ramp", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Ss.RedDeep)
            }
            Spacer(Modifier.width(10.dp))
            Text(formatElapsed(state.elapsedSec), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ss.Muted)
        }
        Spacer(Modifier.height(3.dp))
        Text(stateDetail(state), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ss.Muted, lineHeight = 20.sp)
        Spacer(Modifier.height(14.dp))
        Row {
            Readout(state.spm?.let { "%.0f".format(it) } ?: "—", "your steps/min", state.stepCount.toLong(), Ss.Gold, Modifier.weight(1f))
            Readout(
                state.cueBpm?.let { "%.0f".format(it) } ?: "—",
                if (state.cueBpm == null) "beat is quiet" else "beat bpm",
                state.beatCount,
                Ss.Red,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(thickness = 1.dp, color = Ss.CardLine)
        Spacer(Modifier.height(12.dp))
        Row {
            MiniStat("In step", state.syncPct?.let { "%.0f%%".format(it * 100) } ?: "—", Modifier.weight(1f))
            MiniStat("Steps", "${state.stepCount}", Modifier.weight(1f))
            if (state.followMode) {
                MiniStat("Timing", state.meanAbsAsyncMs?.let { "±%.0f ms".format(it) } ?: "—", Modifier.weight(1f))
            } else {
                MiniStat(
                    "Speed-up",
                    state.baselineSpm?.let { b -> state.cueBpm?.let { formatRampPct((it / b - 1) * 100) } } ?: "—",
                    Modifier.weight(1f),
                )
            }
        }
        if (vm.sourceKind == SourceKind.SIMULATED) {
            Spacer(Modifier.height(14.dp))
            Text("Practice walker: %.0f steps/min".format(vm.simCadence), fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            SsSlider(vm.simCadence, vm::updateSimCadence, 40f..140f)
        }
        state.recordingName?.let {
            Spacer(Modifier.height(6.dp))
            Text("Recording raw samples to $it", fontSize = 12.5.sp, color = Ss.Faint)
        }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text("Error: $it", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Ss.Red)
        }
        if (state.recentEvents.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text("What the beat is doing", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            Spacer(Modifier.height(4.dp))
            state.recentEvents.take(3).forEach { e ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(
                        formatElapsed((e.tNanos - state.sessionStartNs) / 1e9),
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Ss.Faint,
                        modifier = Modifier.width(42.dp),
                    )
                    Text(e.reason.replaceFirstChar { it.uppercase() }, fontSize = 13.5.sp, color = Ss.Muted, lineHeight = 18.sp)
                }
            }
        }
    }
}

@Composable
private fun Readout(value: String, label: String, pulseKey: Long, pulseColor: Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 50.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-1).sp, lineHeight = 56.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pulse(pulseKey, pulseColor)
            Spacer(Modifier.width(7.dp))
            Text(label, fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
        Text(label, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
    }
}

private fun greeting(): String = when (LocalTime.now().hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

private fun startHint(vm: MainViewModel, state: LiveState): String = when {
    state.running && state.cueState == CueState.CALIBRATING -> "Keep walking normally. The beat starts once we know your pace."
    state.running -> "Walk with the beat. Tap Stop walk when you're done."
    vm.sourceKind == SourceKind.REPLAY && !vm.canStart -> "Choose a recorded walk in settings (top right) first."
    else -> {
        val steps = when {
            vm.followMode -> Tuning.FOLLOW_CALIBRATION_STEPS
            vm.demoTiming -> Tuning.DEMO_CALIBRATION_STEPS
            else -> Tuning.CALIBRATION_STEPS
        }
        "First, walk about $steps steps with no sound so we can find your pace."
    }
}

private fun sourceLine(vm: MainViewModel): String = when (vm.sourceKind) {
    SourceKind.SIMULATED -> "Practice walker"
    SourceKind.LIVE -> "Phone sensor"
    SourceKind.REPLAY -> "Replay"
}

private fun stateTitle(s: LiveState): String = when {
    s.cueState == CueState.CALIBRATING -> "Finding your pace"
    s.cueState == CueState.LOCKING && s.cueBpm == null -> "Finding the beat"
    s.cueState == CueState.LOCKING && s.followMode -> "Following you"
    s.cueState == CueState.LOCKING -> "Locking on"
    s.cueState == CueState.RAMPING -> "Speeding up"
    s.cueState == CueState.HOLDING -> "Holding steady"
    s.cueState == CueState.STEPPING_BACK -> "Easing back"
    else -> "Paused"
}

private fun stateDetail(s: LiveState): String = when {
    s.cueState == CueState.CALIBRATING -> "Walk normally: ${s.calibrationLeft} more steps"
    s.cueState == CueState.LOCKING && s.cueBpm == null -> "Lining the beat up with your steps…"
    s.cueState == CueState.LOCKING && s.followMode -> "The beat matches your steps"
    s.cueState == CueState.LOCKING -> "The beat follows your steps first"
    s.cueState == CueState.RAMPING -> "Step ${s.rampLevel}: a little faster while you keep in step"
    s.cueState == CueState.HOLDING -> "Keeping this pace for a while"
    s.cueState == CueState.STEPPING_BACK -> "A little slower, to help you keep in step"
    else -> "No steps. The beat keeps going when you're ready"
}

private fun stateColor(s: CueState): Color = when (s) {
    CueState.CALIBRATING -> Ss.StateCalibrating
    CueState.LOCKING -> Ss.StateLocking
    CueState.RAMPING -> Ss.StateRamping
    CueState.HOLDING -> Ss.StateHolding
    CueState.STEPPING_BACK -> Ss.StateSteppingBack
    CueState.PAUSED -> Ss.StatePaused
}

private fun formatRampPct(pct: Double): String = if (kotlin.math.abs(pct) < 0.5) "0%" else "%+.0f%%".format(pct)

internal fun formatElapsed(sec: Double): String {
    val s = sec.toInt().coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
