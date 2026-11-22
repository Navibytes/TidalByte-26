package com.tidalbyte.stridesync.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tidalbyte.stridesync.MainViewModel
import java.text.NumberFormat
import java.util.Locale

@Composable
fun ProfileScreen(vm: MainViewModel, onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val p = DemoProfile
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            var menu by remember { mutableStateOf(false) }
            Box {
                CircleIconButton(SsIcons.More, "More", { menu = true }, size = 46.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Session settings") }, onClick = { menu = false; onOpenSettings() })
                    DropdownMenuItem(
                        text = { Text("About StrideSync") },
                        onClick = {
                            menu = false
                            Toast.makeText(context, "StrideSync prototype — not a medical device.", Toast.LENGTH_LONG).show()
                        },
                    )
                }
            }
        }
        Avatar(p.initials, 96.dp)
        Spacer(Modifier.height(14.dp))
        Text(p.fullName, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-0.5).sp)
        Text(
            "Walking with StrideSync since ${p.shortDate(p.startDate)}",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Ss.Red,
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CountStat("${p.walks.size}", "Walks")
            VerticalDivider(Modifier.height(38.dp).padding(horizontal = 14.dp), thickness = 1.dp, color = Ss.Border)
            CountStat("${p.weeks}", "Weeks")
            Spacer(Modifier.weight(1f))
            FilledPill(
                "Share",
                SsIcons.Share,
                {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, p.shareText())
                    }
                    context.startActivity(Intent.createChooser(send, "Share your progress"))
                },
                height = 48.dp,
            )
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(thickness = 1.dp, color = Ss.Line)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricTile("Avg pace", "%.1f".format(p.avgPace), "steps/min", onOpenHistory, Modifier.weight(1f))
            MetricTile(
                "Total steps",
                NumberFormat.getIntegerInstance(Locale.US).format(p.totalSteps),
                "in ${p.walks.size} walks",
                onOpenHistory,
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("All-time", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
        Spacer(Modifier.height(6.dp))
        AllTimeRow("Natural pace", "%.1f".format(p.naturalPace), delta = "%.1f".format(p.naturalPaceChange))
        AllTimeRow("Best pace with the beat", "%.1f".format(p.bestWithBeat))
        AllTimeRow("Average in step", "%.0f%%".format(p.avgInStep * 100))
        AllTimeRow("Time walking", p.hoursMinutes(p.totalSec))
        AllTimeRow("Longest walk", p.hoursMinutes(p.longestSec), last = true)
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Ss.Card)
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
            ToggleRow(
                "Share with my therapist",
                if (vm.shareWithTherapist) "Your walks are shared" else "Your walks stay on this phone",
                vm.shareWithTherapist,
                onChange = vm::updateShareWithTherapist,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm.cycleTextSize() }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Text size", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink, modifier = Modifier.weight(1f))
                Text(vm.textSize.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
                Spacer(Modifier.width(6.dp))
                Icon(SsIcons.ChevronRight, null, tint = Ss.Muted, modifier = Modifier.size(20.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Prototype — not a medical device. Walk history is demo data.",
            fontSize = 12.5.sp,
            color = Ss.Faint,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CountStat(value: String, label: String) {
    Column {
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink)
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
    }
}

@Composable
private fun MetricTile(title: String, value: String, unit: String, onClick: () -> Unit, modifier: Modifier) {
    Column(
        modifier
            .height(156.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Ss.Card)
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Ss.Ink, modifier = Modifier.weight(1f))
            Icon(SsIcons.ArrowRight, null, tint = Ss.Ink, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(value, fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-1).sp)
        Text(unit, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
    }
}

@Composable
private fun AllTimeRow(label: String, value: String, delta: String? = null, last: Boolean = false) {
    Column {
        Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 16.sp, color = Ss.Ink, modifier = Modifier.weight(1f))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            if (delta != null) {
                Spacer(Modifier.width(6.dp))
                Icon(SsIcons.TriangleUp, null, tint = Ss.Red, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(2.dp))
                Text(delta, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Red)
            }
        }
        if (!last) HorizontalDivider(thickness = 1.dp, color = Ss.Line)
    }
}
