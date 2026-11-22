package com.tidalbyte.stridesync.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Walk history. FAKE data (see [DemoProfile]); the app doesn't store sessions yet. */
@Composable
fun HistoryScreen() {
    val p = DemoProfile
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
    ) {
        Spacer(Modifier.height(22.dp))
        Text("History", fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-0.5).sp)
        Text("Your last ${p.weeks} weeks of walks", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
        Spacer(Modifier.height(18.dp))

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Ss.Card)
                .padding(20.dp),
        ) {
            Text("Natural pace, each walk", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.1f".format(p.naturalPace), fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink, letterSpacing = (-1).sp)
                Spacer(Modifier.width(6.dp))
                Text("steps/min", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Ss.Muted, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(SsIcons.ChevronUp, null, tint = Ss.Red, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(
                    "%.1f since %s".format(p.naturalPaceChange, p.shortDate(p.startDate)),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = Ss.Red,
                )
            }
            Spacer(Modifier.height(16.dp))
            PaceBars(p.walks.map { it.naturalSpm }, Modifier.fillMaxWidth().height(110.dp))
            Spacer(Modifier.height(6.dp))
            Row {
                Text(p.shortDate(p.startDate), fontSize = 13.sp, color = Ss.Faint, modifier = Modifier.weight(1f))
                Text("Today", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ss.Faint)
            }
        }

        Spacer(Modifier.height(24.dp))
        SectionTitle("Walks")
        Spacer(Modifier.height(10.dp))
        p.walks.asReversed().forEach { w ->
            WalkRow(w)
            Spacer(Modifier.height(10.dp))
        }
        Text("Demo data. Prototype — not a medical device.", fontSize = 12.5.sp, color = Ss.Faint)
        Spacer(Modifier.height(20.dp))
    }
}

/** One rounded bar per walk; the latest (today) in full red, the rest lighter. */
@Composable
private fun PaceBars(values: List<Double>, modifier: Modifier) {
    Canvas(modifier) {
        val lo = values.min() - 3
        val hi = values.max() + 0.5
        val gap = 2.dp.toPx() * 2
        val bw = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (size.height * ((v - lo) / (hi - lo))).toFloat()
            val x = i * (bw + gap)
            drawRoundRect(
                color = if (i == values.lastIndex) Ss.Red else Ss.Red.copy(alpha = 0.3f),
                topLeft = Offset(x, size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

@Composable
private fun WalkRow(w: DemoProfile.Walk) {
    val today = w.daysAgo == 0
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (today) Ss.Pink else Color.White)
            .border(1.5.dp, if (today) Ss.PinkBorder else Ss.Border, RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(DemoProfile.dayLabel(w), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            Text(
                "${DemoProfile.clock(w.time)} · ${DemoProfile.hoursMinutes(w.durationSec)} · ${"%,d".format(w.steps)} steps",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = Ss.Muted,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Natural %.1f → %.1f with the beat".format(w.naturalSpm, w.withBeatSpm),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Ss.Ink,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("%.0f%%".format(w.inStep * 100), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Ss.Ink)
            Text("in step", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
        }
    }
}
