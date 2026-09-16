package fr.hermesmusic.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.palette.graphics.Palette
import fr.hermesmusic.core.AppGraph
import fr.hermesmusic.core.Nocturne
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Collections

/**
 * Couleur dominante d'une pochette.
 *
 * On télécharge nous-mêmes l'image (via le client OkHttp partagé, donc avec
 * l'authentification Jellyfin) plutôt que de passer par Coil : on évite de
 * dépendre de ses internes, et on contrôle la réduction d'échelle — une palette
 * n'a pas besoin de plus de quelques centaines de pixels.
 *
 * Résultat mémorisé par URL : un morceau de 4 minutes ne recalcule pas sa
 * palette à chaque recomposition.
 */
object CoverColors {

    private const val MAX_CACHE = 64
    private const val MAX_SIDE = 96

    private val cache: MutableMap<String, Int> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Int>(MAX_CACHE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>): Boolean =
                size > MAX_CACHE
        }
    )

    /** Couleur déjà connue, sans requête (évite un flash au premier affichage). */
    fun cached(url: String): Int? = cache[url]

    suspend fun load(client: OkHttpClient, url: String): Int? = withContext(Dispatchers.IO) {
        cache[url]?.let { return@withContext it }

        val color = runCatching {
            val response = client.newCall(Request.Builder().url(url).build()).execute()
            response.use {
                check(it.isSuccessful) { "HTTP ${it.code}" }
                val bytes = it.body?.bytes() ?: error("réponse vide")
                val bitmap = downscale(bytes)
                try {
                    val palette = Palette.from(bitmap).clearFilters().generate()
                    // « Vibrant » donne la couleur signature de la pochette ; on
                    // retombe sur Muted puis sur la dominante si l'image est terne.
                    palette.vibrantSwatch?.rgb
                        ?: palette.mutedSwatch?.rgb
                        ?: palette.dominantSwatch?.rgb
                } finally {
                    bitmap.recycle()
                }
            }
        }.getOrNull()

        if (color != null) cache[url] = color
        color
    }

    private fun downscale(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        var sample = 1
        while (longest / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: error("décodage impossible")
    }
}

/** Mélange linéaire de deux couleurs (t = 0 → [from], t = 1 → [to]). */
private fun blend(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = 1f,
)

/** Ramène une couleur trop claire sous un plafond de luminosité. */
private fun toneDown(color: Color, maxLuma: Float = 0.42f): Color {
    val luma = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    if (luma <= maxLuma || luma <= 0f) return color
    val factor = maxLuma / luma
    return Color(color.red * factor, color.green * factor, color.blue * factor, alpha = 1f)
}

/**
 * Fond dégradé tiré de la pochette : le haut prend la couleur de l'image, puis
 * tout redescend vers le fond de l'application. C'est le repère visuel le plus
 * reconnaissable des applications de streaming — ici, assagi pour rester dans
 * l'identité Nocturne (couleur plafonnée en luminosité, jamais criarde).
 *
 * Si l'extraction échoue (pas de pochette, image illisible), on garde un
 * dégradé neutre : jamais de fond vide ni d'erreur visible.
 */
@Composable
fun rememberArtworkBrush(graph: AppGraph, coverUrl: String?, tint: Float = 0.40f): Brush {
    val neutral = if (Nocturne.dark) Color(0xFF1B1D2E) else Nocturne.Surface2

    var color by remember(coverUrl) {
        mutableStateOf(coverUrl?.let { CoverColors.cached(it) }?.let { Color(it) })
    }

    LaunchedEffect(coverUrl) {
        if (coverUrl == null || color != null) return@LaunchedEffect
        CoverColors.load(graph.okHttp, coverUrl)?.let { color = Color(it) }
    }

    val top = toneDown(blend(color ?: neutral, Nocturne.Bg, tint))
    return Brush.verticalGradient(
        0f to top,
        0.5f to blend(top, Nocturne.Bg, 0.72f),
        1f to Nocturne.Bg,
    )
}
