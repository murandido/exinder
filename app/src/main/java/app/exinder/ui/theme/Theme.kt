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

// ─── Paleta ─────────────────────────────────────────────────────────────────
// O layout é o do Tinder; a cor da marca troca o rosa/laranja pelo verde brat.
// Vermelho, azul e amarelo dos botões ficam iguais aos do Tinder para que cada
// ação seja reconhecida na hora.

/** Verde brat: logo, botão manter, carimbo MANTÉM. */
val Brat = Color(0xFF8ACE00)

/** Ponta clara do gradiente da marca. */
val BratLight = Color(0xFFB9F227)

/** Texto sobre o verde. */
val Ink = Color(0xFF000000)

/** Botão X e carimbo APAGA. */
val Nope = Color(0xFFFF4458)

/** Botão estrela e carimbo FAVORITA. */
val StarBlue = Color(0xFF2DB3FF)

/** Botão rebobinar. */
val RewindGold = Color(0xFFF5B748)

private val DarkSurface = Color(0xFF111111)

private val LightColors = lightColorScheme(
    primary = Brat,
    onPrimary = Ink,
    secondary = BratLight,
    onSecondary = Ink,
    background = Color.White,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1F2F4),
    onSurfaceVariant = Color(0xFF656E7B),
    surfaceContainer = Color.White,
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
    error = Nope,
)

/**
 * Fonte condensada do sistema, a mais próxima da Arial Narrow da capa do
 * *brat* sem embutir arquivo de fonte no app.
 */
val Condensed: FontFamily = FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))

/**
 * Tema do app. Segue o modo claro/escuro do sistema.
 *
 * As telas pintam o fundo com `Modifier.background` em vez de `Surface`, então
 * a cor padrão do texto é definida aqui; sem isso ela ficaria preta no modo escuro.
 *
 * @param dark escuro ou claro; por padrão segue o sistema.
 */
@Composable
fun ExinderTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

/**
 * O logo "exinder", como a capa do *brat*: texto preto, minúsculo, condensado
 * e levemente desfocado, sobre um bloco verde.
 */
@Composable
fun ExinderLogo(modifier: Modifier = Modifier, size: TextUnit = 30.sp) {
    Box(
        modifier
            .background(Brat)
            // Respiro proporcional ao tamanho do texto, igual em todos os tamanhos.
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
