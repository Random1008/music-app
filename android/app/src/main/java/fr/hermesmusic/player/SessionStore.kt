package fr.hermesmusic.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Un morceau tel que persisté pour la reprise de session. */
@Serializable
data class SavedTrack(
    val id: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val streamUrl: String,
)

/** File de lecture + position, écrites sur disque pour survivre à un arrêt du processus. */
@Serializable
data class SavedSession(
    val tracks: List<SavedTrack> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0L,
)

/**
 * Reprise de session : Android peut tuer le processus à tout moment. Sans ce
 * fichier, rouvrir l'application repart d'une file vide et la musique en cours
 * est perdue — même si le service a survécu.
 *
 * Volontairement un simple fichier JSON : quelques kilo-octets, pas de base de
 * données à gérer pour une seule valeur.
 */
class SessionStore(private val context: Context) {

    private val file = File(context.filesDir, "player_session.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun load(): SavedSession? = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) return@runCatching null
            val text = file.readText()
            if (text.isBlank()) null else json.decodeFromString<SavedSession>(text)
        }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }
    }

    suspend fun save(session: SavedSession) = withContext(Dispatchers.IO) {
        runCatching {
            file.writeText(json.encodeToString(session))
        }
        Unit
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
        Unit
    }
}
