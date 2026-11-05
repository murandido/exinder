package app.exinder.ui.theme

import android.graphics.Typeface
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── Palette ────────────────────────────────────────────────────────────────
// The layout is Tinder's; the brand color swaps the pink/orange for brat green.
// The red, blue and yellow buttons match Tinder's so each action is
// recognized instantly.

/** Brat green: logo, keep button, KEEP stamp. */
val Brat = Color(0xFF8ACE00)

/** Light end of the brand gradient. */
val BratLight = Color(0xFFB9F227)

/** Text on top of the green. */
val Ink = Color(0xFF000000)

/** X button and DELETE stamp. */
val Nope = Color(0xFFFF4458)

/** Star button and FAVORITE stamp. */
val StarBlue = Color(0xFF2DB3FF)

/** Rewind button. */
val RewindGold = Color(0xFFF5B748)

/**
 * Deeper green for green *text* on light backgrounds (button labels, titles):
 * brat green on white is about 1.9:1, too faint to read; this is about 5:1.
 */
private val BratDeep = Color(0xFF4A7300)

private val DarkSurface = Color(0xFF111111)

private val LightColors = lightColorScheme(
    primary = BratDeep,
    onPrimary = Color.White,
    secondary = BratLight,
    onSecondary = Ink,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF656E7B),
    surfaceContainer = Color.White,
    // Dialogs and menus use these; Material's defaults are lavender.
    surfaceContainerHigh = Color(0xFFF1F2F4),
    surfaceContainerHighest = Color(0xFFE8EAED),
    error = Nope,
)

private val DarkColors = darkColorScheme(
    primary = Brat,
    onPrimary = Ink,
    secondary = BratLight,
    onSecondary = Ink,
    background = DarkSurface,
    onBackground = Color.White,
    surface = DarkSurface,
    onSurface = Color.White,
    surfaceVariant = Color(0xFF1F2228),
    onSurfaceVariant = Color(0xFFA3AAB5),
    surfaceContainer = Color(0xFF1B1D21),
    surfaceContainerHigh = Color(0xFF22252B),
    surfaceContainerHighest = Color(0xFF2A2D33),
    error = Nope,
)

/**
 * The system's condensed font, the closest to the Arial Narrow on the *brat*
 * cover without bundling a font file in the app.
 */
val Condensed: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

/**
 * App theme. Follows the system's light/dark mode.
 *
 * Screens paint their background with `Modifier.background` instead of `Surface`,
 * so the default text color is set here; otherwise it would be black in dark mode.
 *
 * @param dark dark or light; follows the system by default.
 */
@Composable
fun ExinderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

/**
 * The "exinder" logo, styled like the *brat* cover: black, lowercase, condensed,
 * slightly blurred text on a green block.
 */
@Composable
fun ExinderLogo(modifier: Modifier = Modifier, size: TextUnit = 30.sp) {
    Box(
        modifier
            .background(Brat)
            // Padding proportional to the text size, so it looks the same at every size.
            .padding(horizontal = (size.value * 0.28f).dp, vertical = (size.value * 0.04f).dp),
    ) {
        Text(
            text = "exinder",
            color = Ink,
            fontFamily = Condensed,
            fontWeight = FontWeight.Bold,
            fontSize = size,
            letterSpacing = (-1).sp,
            modifier = Modifier.blur((size.value / 50f).dp, BlurredEdgeTreatment.Unbounded),
        )
    }
}
