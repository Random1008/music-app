package fr.hermesmusic.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Design « Nocturne » — valeurs reprises de la maquette validée. */
object Nocturne {
    val Bg = Color(0xFF08090F)
    val Surface = Color(0xFF1F2130)
    val Surface2 = Color(0xFF171926)
    val Ink = Color(0xFFE9E9ED)
    val Dim = Color(0xFF9397AB)
    val Dim2 = Color(0xFF75798C)
    val Accent = Color(0xFF7C5CFF)
}

private val scheme = darkColorScheme(
    primary = Nocturne.Accent,
    onPrimary = Color(0xFF0E0F18),
    background = Nocturne.Bg,
    onBackground = Nocturne.Ink,
    surface = Nocturne.Surface,
    onSurface = Nocturne.Ink,
    surfaceVariant = Nocturne.Surface2,
    onSurfaceVariant = Nocturne.Dim,
    outline = Color(0x24E9E9ED),
)

/** Petit libellé monospace en majuscules (kicker / sur-titre). */
@Composable
fun kicker(): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    letterSpacing = 1.4.sp,
    color = Nocturne.Dim2,
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) {
    // Thème sombre par défaut (le clair viendra plus tard, cf. spec §29)
    isSystemInDarkTheme()
    MaterialTheme(colorScheme = scheme, content = content)
}
