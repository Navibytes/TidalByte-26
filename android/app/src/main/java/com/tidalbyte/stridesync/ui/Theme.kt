@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.tidalbyte.stridesync.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.tidalbyte.stridesync.R

/** The palette, sampled from the design mockups: warm off-white, cranberry red, a gold accent. Light only. */
object Ss {
    val Bg = Color(0xFFFBFAF6)
    val Card = Color(0xFFF1EFE8)
    val CardLine = Color(0xFFE3DFD5)
    val Red = Color(0xFFD33A50)
    val RedDeep = Color(0xFFA62A3D)
    val Pink = Color(0xFFFDE4E8)
    val PinkBorder = Color(0xFFE07A8A)
    val Ink = Color(0xFF221618)
    val Muted = Color(0xFF5A4E50)
    val Faint = Color(0xFF8E8486)
    val Line = Color(0xFFEAE7DF)
    val Border = Color(0xFFE4E0D7)
    val Gold = Color(0xFFD9A441)

    // Session-state dots (live card). Each state also has a text label, so color is never the only cue.
    val StateCalibrating = Color(0xFF8E8486)
    val StateLocking = Color(0xFF3B7DD8)
    val StateRamping = Color(0xFF2E9E62)
    val StateHolding = Color(0xFF2A9D8F)
    val StateSteppingBack = Color(0xFFE08A1E)
    val StatePaused = Color(0xFF8A63C9)
}

/** Figtree (SIL OFL 1.1, assets/licenses/Figtree-OFL.txt): one variable font; each weight sets its wght axis.
 *  Bundled so the app looks the same whatever system font the phone uses. */
val Figtree = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold).map {
        // The file's default instance is Light, so each weight must set the wght axis explicitly.
        Font(R.font.figtree, it, variationSettings = FontVariation.Settings(FontVariation.weight(it.weight)))
    },
)

private fun Typography.withFont(f: FontFamily) = Typography(
    displayLarge = displayLarge.copy(fontFamily = f),
    displayMedium = displayMedium.copy(fontFamily = f),
    displaySmall = displaySmall.copy(fontFamily = f),
    headlineLarge = headlineLarge.copy(fontFamily = f),
    headlineMedium = headlineMedium.copy(fontFamily = f),
    headlineSmall = headlineSmall.copy(fontFamily = f),
    titleLarge = titleLarge.copy(fontFamily = f),
    titleMedium = titleMedium.copy(fontFamily = f),
    titleSmall = titleSmall.copy(fontFamily = f),
    bodyLarge = bodyLarge.copy(fontFamily = f),
    bodyMedium = bodyMedium.copy(fontFamily = f),
    bodySmall = bodySmall.copy(fontFamily = f),
    labelLarge = labelLarge.copy(fontFamily = f),
    labelMedium = labelMedium.copy(fontFamily = f),
    labelSmall = labelSmall.copy(fontFamily = f),
)

private val Scheme = lightColorScheme(
    primary = Ss.Red,
    onPrimary = Color.White,
    secondary = Ss.Red,
    tertiary = Ss.Gold,
    background = Ss.Bg,
    onBackground = Ss.Ink,
    surface = Ss.Bg,
    onSurface = Ss.Ink,
    surfaceVariant = Ss.Card,
    onSurfaceVariant = Ss.Muted,
    surfaceContainer = Color.White,
    surfaceContainerLow = Ss.Bg,
    surfaceContainerHigh = Color.White,
    outline = Ss.Border,
    outlineVariant = Ss.Line,
    error = Ss.Red,
)

/** [textScale] is the Profile → Text size setting, applied on top of the system font scale. */
@Composable
fun StrideSyncTheme(textScale: Float = 1f, content: @Composable () -> Unit) {
    val d = LocalDensity.current
    MaterialTheme(colorScheme = Scheme, typography = Typography().withFont(Figtree)) {
        CompositionLocalProvider(LocalDensity provides Density(d.density, d.fontScale * textScale), content = content)
    }
}
