package com.tidalbyte.stridesync.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Initials in a red disc with a gold ring, as in the mockups. */
@Composable
fun Avatar(initials: String, size: Dp, modifier: Modifier = Modifier) {
    val ring = size * 0.045f
    val gap = size * 0.055f
    Box(
        modifier
            .size(size)
            .border(ring, Ss.Gold, CircleShape)
            .padding(ring + gap)
            .clip(CircleShape)
            .background(Ss.Red),
        contentAlignment = Alignment.Center,
    ) {
        Text(initials, color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.3f).sp, letterSpacing = 0.5.sp)
    }
}

@Composable
fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, size: Dp = 50.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.dp, Ss.Line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Ss.Ink, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
}

/** A big two-line choice card (Ramp / Follow). Selected = filled red. */
@Composable
fun ModeCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .alpha(if (enabled || selected) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) Ss.Red else Color.White)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, Ss.Border, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (selected) Color.White else Ss.Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            subtitle,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = if (selected) Color.White.copy(alpha = 0.92f) else Ss.Muted,
        )
    }
}

/** An icon-over-label tile (Click / Woodblock / Music). Selected = pink with a red border. */
@Composable
fun SoundTile(label: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(98.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(if (selected) Ss.Pink else Color.White)
            .border(1.5.dp, if (selected) Ss.PinkBorder else Ss.Border, shape)
            .clickable(enabled = enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Ss.Ink, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(9.dp))
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink, maxLines = 1)
    }
}

/** Album-art stand-in: a red tile with equalizer bars, which dance while the song is playing. */
@Composable
fun SongArt(size: Dp, playing: Boolean = false, tone: Int = 0) {
    val phase = if (playing) {
        val t = rememberInfiniteTransition(label = "eq")
        t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "eq").value
    } else {
        0f
    }
    val heights = floatArrayOf(0.42f, 0.72f, 0.55f, 0.95f, 0.62f)
    val pale = Color(0xFFF7C4CC)
    val gold = Color(0xFFEDB24C)
    val colors = listOf(pale, gold, pale, pale, gold)
    val bg = when (tone % 3) {
        0 -> Ss.Red
        1 -> Color(0xFFB8485E)
        else -> Color(0xFFC9784A)
    }
    Canvas(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.24f))
            .background(bg),
    ) {
        val w = this.size.width
        val h = this.size.height
        val bw = w * 0.09f
        val gapW = w * 0.065f
        val total = 5 * bw + 4 * gapW
        var x = (w - total) / 2
        val base = h * 0.8f
        for (i in 0 until 5) {
            val wobble = if (playing) 0.7f + 0.3f * sin(phase * (1 + i % 2) + i * 1.7f) else 1f
            val bh = h * 0.58f * heights[(i + tone) % 5] * wobble
            drawRoundRect(colors[i], topLeft = Offset(x, base - bh), size = Size(bw, bh), cornerRadius = CornerRadius(bw / 2))
            x += bw + gapW
        }
    }
}

/** The beige "current song" card under the Beat sound tiles. */
@Composable
fun SongCard(title: String, subtitle: String, playing: Boolean, onChange: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Ss.Card)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SongArt(52.dp, playing)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ss.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ss.Muted, maxLines = 2, lineHeight = 19.sp)
        }
        if (onChange != null) {
            Spacer(Modifier.width(10.dp))
            OutlinePill("Change", onChange)
        }
    }
}

@Composable
fun OutlinePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.5.dp, Ss.Red, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, fontSize = 15.5.sp, fontWeight = FontWeight.SemiBold, color = Ss.RedDeep)
    }
}

/** A filled pill with an optional leading icon (Share, Start walk). */
@Composable
fun FilledPill(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ss.Red,
    enabled: Boolean = true,
    height: Dp = 46.dp,
    fontSize: Float = 16f,
) {
    Row(
        modifier
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(50))
            .background(color)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size((fontSize * 1.1f).dp))
            Spacer(Modifier.width((fontSize * 0.6f).dp))
        }
        Text(text, fontSize = fontSize.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

/** A selectable pill for small option rows (settings sheet). */
@Composable
fun ChoicePill(text: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier
            .alpha(if (enabled || selected) 1f else 0.5f)
            .clip(shape)
            .background(if (selected) Ss.Red else Color.White)
            .then(if (selected) Modifier else Modifier.border(1.5.dp, Ss.Border, shape))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else Ss.Ink)
    }
}

/** A small red trend line with a gold dot on the latest value. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val min = values.min()
        val span = (values.max() - min).takeIf { it > 0 } ?: 1.0
        val r = 4.5.dp.toPx()
        val pad = r + 1.dp.toPx()
        fun pt(i: Int) = Offset(
            pad + (size.width - 2 * pad) * i / (values.size - 1),
            pad + (size.height - 2 * pad) * (1 - ((values[i] - min) / span).toFloat()),
        )
        val path = Path().apply {
            val p0 = pt(0)
            moveTo(p0.x, p0.y)
            for (i in 1 until values.size) pt(i).let { lineTo(it.x, it.y) }
        }
        drawPath(path, Ss.Red, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(Ss.Gold, r, pt(values.lastIndex))
    }
}

@Composable
fun SsSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = Ss.Red,
            checkedBorderColor = Ss.Red,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = Ss.Border,
            uncheckedBorderColor = Ss.Border,
        ),
    )
}

@Composable
fun SsSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>) {
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = Ss.Red, activeTrackColor = Ss.Red, inactiveTrackColor = Ss.CardLine),
    )
}

/** A label + optional caption on the left, a switch on the right. */
@Composable
fun ToggleRow(title: String, caption: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (enabled) 1f else 0.5f)) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ss.Ink)
            if (caption != null) Text(caption, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = Ss.Muted)
        }
        Spacer(Modifier.width(12.dp))
        SsSwitch(checked, onChange, enabled)
    }
}

/** A dot that flashes each time [key] changes (a step, or a heard beat). */
@Composable
fun Pulse(key: Long, color: Color, size: Dp = 10.dp) {
    val alpha = remember { Animatable(0.2f) }
    LaunchedEffect(key) {
        if (key == 0L) return@LaunchedEffect
        alpha.snapTo(1f)
        alpha.animateTo(0.2f, tween(300))
    }
    Box(
        Modifier
            .size(size)
            .alpha(alpha.value)
            .background(color, CircleShape),
    )
}

/** A selection ring: filled red when selected. */
@Composable
fun RadioDot(selected: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .border(2.dp, if (selected) Ss.Red else Ss.Border, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(12.dp).background(Ss.Red, CircleShape))
    }
}
