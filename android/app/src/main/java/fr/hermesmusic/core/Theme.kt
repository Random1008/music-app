package fr.hermesmusic.core

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design « Nocturne ».
 *
 * Les couleurs sont exposées par des accesseurs qui lisent un état Compose :
 * changer le thème ou l'accent suffit à recomposer tous les écrans qui
 * utilisent `Nocturne.*`, sans avoir à réécrire chaque vue. C'est le prix
 * minimal pour avoir un thème clair sur une base écrite en sombre.
 */
object Nocturne {

    /** Accents proposés. Le premier est celui de la maquette validée. */
    val ACCENTS: List<Pair<String, Color>> = listOf(
        "Violet" to Color(0xFF7C5CFF),
        "Cyan" to Color(0xFF35C2D6),
        "Vert" to Color(0xFF42C08A),
        "Ambre" to Color(0xFFE0A64B),
        "Rose" to Color(0xFFE0609B),
    )

    private var darkState by mutableStateOf(true)
    private var accentState by mutableStateOf(0)

    var dark: Boolean
        get() = darkState
        set(value) { darkState = value }

    var accentIndex: Int
        get() = accentState
        set(value) { accentState = value.coerceIn(0, ACCENTS.lastIndex) }

    val accentName: String get() = ACCENTS[accentIndex].first

    // --- couleurs de base -------------------------------------------------
    val Bg: Color get() = if (dark) Color(0xFF08090F) else Color(0xFFF5F5F8)
    val Surface: Color get() = if (dark) Color(0xFF1F2130) else Color(0xFFFFFFFF)
    val Surface2: Color get() = if (dark) Color(0xFF171926) else Color(0xFFEAEAF0)
    val Ink: Color get() = if (dark) Color(0xFFE9E9ED) else Color(0xFF15161E)
    val Dim: Color get() = if (dark) Color(0xFF9397AB) else Color(0xFF565A6B)
    val Dim2: Color get() = if (dark) Color(0xFF75798C) else Color(0xFF84889A)
    val Accent: Color get() = ACCENTS[accentIndex].second

    /** Texte posé sur l'accent : les accents sont clairs, le texte doit être sombre. */
    val OnAccent: Color get() = Color(0xFF0E0F18)

    /** Trait ou séparateur discret. */
    val Hairline: Color get() = if (dark) Color(0x24E9E9ED) else Color(0x1F15161E)

    /** Voile posé sur une pochette en cours de lecture. */
    val Scrim: Color get() = if (dark) Color(0x6608090F) else Color(0x33FFFFFF)
}

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
    // Lire ces états ici force la recomposition du thème quand l'utilisateur
    // change de mode ou d'accent (et donc de tout ce qui en dépend).
    val dark = Nocturne.dark
    val accent = Nocturne.Accent
    isSystemInDarkTheme()

    val scheme = if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = Nocturne.OnAccent,
            background = Nocturne.Bg,
            onBackground = Nocturne.Ink,
            surface = Nocturne.Surface,
            onSurface = Nocturne.Ink,
            surfaceVariant = Nocturne.Surface2,
            onSurfaceVariant = Nocturne.Dim,
            outline = Nocturne.Hairline,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = Nocturne.OnAccent,
            background = Nocturne.Bg,
            onBackground = Nocturne.Ink,
            surface = Nocturne.Surface,
            onSurface = Nocturne.Ink,
            surfaceVariant = Nocturne.Surface2,
            onSurfaceVariant = Nocturne.Dim,
            outline = Nocturne.Hairline,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
