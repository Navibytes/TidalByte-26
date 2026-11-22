package com.tidalbyte.stridesync.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tidalbyte.stridesync.MainViewModel
import com.tidalbyte.stridesync.session.LiveState
import com.tidalbyte.stridesync.session.Placement
import com.tidalbyte.stridesync.session.SourceKind

/** The demo/testing controls that the patient-facing design doesn't show: where the steps come from, the
 *  practice walker, recording, placement and demo timing. Opened from the sliders button on Walk. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(vm: MainViewModel, state: LiveState, onDismiss: () -> Unit) {
    val locked = state.running
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Ss.Bg,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Session settings", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink)
            Text(
                if (locked) "For demos and testing. Most of these lock while a walk is running." else "For demos and testing.",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = Ss.Muted,
            )
            Spacer(Modifier.height(20.dp))

            Label("Where the steps come from")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("Practice walker", vm.sourceKind == SourceKind.SIMULATED, !locked) { vm.updateSourceKind(SourceKind.SIMULATED) }
                ChoicePill("Phone", vm.sourceKind == SourceKind.LIVE, !locked) { vm.updateSourceKind(SourceKind.LIVE) }
                ChoicePill("Replay", vm.sourceKind == SourceKind.REPLAY, !locked) { vm.updateSourceKind(SourceKind.REPLAY) }
            }
            Spacer(Modifier.height(16.dp))

            when (vm.sourceKind) {
                SourceKind.SIMULATED -> {
                    Text("Practice walker pace: %.0f steps/min".format(vm.simCadence), fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
                    SsSlider(vm.simCadence, vm::updateSimCadence, 40f..140f)
                    Spacer(Modifier.height(4.dp))
                    ToggleRow("Walker follows the beat", "A simulated walker who partly keeps time", vm.walkerEntrains, !locked, vm::updateWalkerEntrains)
                }
                SourceKind.LIVE -> {
                    Text(
                        "Best: phone pressed flat or tucked at the lower back. Use the phone speaker or wired earbuds, " +
                            "not Bluetooth. The screen can be off.",
                        fontSize = 14.5.sp,
                        color = Ss.Muted,
                        lineHeight = 20.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Label("Where the phone is")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Placement.entries.forEach { p -> ChoicePill(p.label, vm.placement == p, !locked) { vm.updatePlacement(p) } }
                    }
                    Spacer(Modifier.height(14.dp))
                    ToggleRow("Record raw samples", "Saves a CSV you can replay later", vm.record, !locked, vm::updateRecord)
                }
                SourceKind.REPLAY -> {
                    if (vm.replays.isEmpty()) {
                        Text("No recordings yet. Record a walk with the phone first.", fontSize = 14.5.sp, color = Ss.Muted)
                    } else {
                        Text(
                            "A recording can't react to the beat, so replay shows step detection and locking, not entrainment.",
                            fontSize = 14.sp,
                            color = Ss.Muted,
                            lineHeight = 19.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        vm.replays.forEach { r ->
                            val sel = vm.selectedReplay == r
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .alpha(if (locked && !sel) 0.5f else 1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (sel) Ss.Pink else Color.White)
                                    .border(1.5.dp, if (sel) Ss.PinkBorder else Ss.Border, RoundedCornerShape(14.dp))
                                    .clickable(enabled = !locked) { vm.selectReplay(r) }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(r.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = Ss.Ink, modifier = Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                RadioDot(sel)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            HorizontalDivider(thickness = 1.dp, color = Ss.Line)
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                "Demo timing",
                if (vm.followMode) "Ramp mode only" else "A full ramp cycle in about a minute",
                vm.demoTiming,
                enabled = !locked && !vm.followMode,
                onChange = vm::updateDemoTiming,
            )
            Spacer(Modifier.height(20.dp))
            Text("Prototype — not a medical device.", fontSize = 13.sp, color = Ss.Faint)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
    Spacer(Modifier.height(8.dp))
}
