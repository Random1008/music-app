package fr.hermesmusic.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Un morceau présent sur l'appareil.
 *
 * On ne stocke PAS l'URL de flux : elle dépend de l'adresse du serveur, qui
 * peut changer. Seul le nom du fichier est conservé, l'URL est reconstruite
 * au moment de lire.
 */
@Serializable
data class DownloadEntry(
    val itemId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val artworkUrl: String? = null,
    val fileName: String,
    val sizeBytes: Long = 0,
    val addedAt: Long = 0,
)

/** Ce qu'il faut pour télécharger un morceau. */
data class DownloadRequest(
    val itemId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artworkUrl: String?,
    val streamUrl: String,
)

/** Encode/décode l'index — isolé pour être testable sans Android. */
object DownloadIndex {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(entries: List<DownloadEntry>): String = json.encodeToString(entries)

    fun decode(text: String): List<DownloadEntry> =
        runCatching { json.decodeFromString<List<DownloadEntry>>(text) }.getOrDefault(emptyList())
}

/**
 * Fichiers téléchargés sur l'appareil, pour l'écoute hors-ligne.
 *
 * Un simple dossier + un index JSON : Media3 sait lire un `file://`, il n'y a
 * donc pas besoin d'un cache de téléchargement ExoPlayer ni d'une base de
 * données pour gérer quelques dizaines de morceaux.
 */
class DownloadStore(private val context: Context) {

    private val dir = File(context.filesDir, "music")
    private val indexFile = File(context.filesDir, "downloads.json")

    private val _entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    val totalBytes: Long get() = _entries.value.sumOf { it.sizeBytes }

    suspend fun load() = withContext(Dispatchers.IO) {
        if (!dir.exists()) dir.mkdirs()
        val text = runCatching { if (indexFile.exists()) indexFile.readText() else "" }
            .getOrDefault("")
        val decoded = DownloadIndex.decode(text)
        // Un fichier supprimé à la main hors de l'app ne doit pas rester dans l'index.
        val alive = decoded.filter { File(dir, it.fileName).exists() }
        _entries.value = alive
        if (alive.size != decoded.size) persist()
    }

    fun fileFor(itemId: String): File = File(dir, "$itemId.mp3")

    fun localFile(itemId: String): File? {
        val name = _entries.value.firstOrNull { it.itemId == itemId }?.fileName ?: return null
        val f = File(dir, name)
        return if (f.exists()) f else null
    }

    fun isDownloaded(itemId: String): Boolean =
        _entries.value.any { it.itemId == itemId } && localFile(itemId) != null

    suspend fun add(entry: DownloadEntry) {
        _entries.value = _entries.value.filterNot { it.itemId == entry.itemId } + entry
        persist()
    }

    suspend fun remove(itemId: String) {
        _entries.value.firstOrNull { it.itemId == itemId }?.let { e ->
            withContext(Dispatchers.IO) { runCatching { File(dir, e.fileName).delete() } }
        }
        _entries.value = _entries.value.filterNot { it.itemId == itemId }
        persist()
    }

    suspend fun clear() {
        _entries.value.forEach { e ->
            withContext(Dispatchers.IO) { runCatching { File(dir, e.fileName).delete() } }
        }
        _entries.value = emptyList()
        persist()
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching { indexFile.writeText(DownloadIndex.encode(_entries.value)) }
        Unit
    }
}

/**
 * Téléchargement séquentiel des morceaux (un à la fois : un seul utilisateur,
 * et on ne veut pas saturer la liaison vers le serveur).
 */
class Downloader(
    private val store: DownloadStore,
    private val client: OkHttpClient,
) {
    data class Progress(
        val running: Boolean = false,
        val currentTitle: String = "",
        val done: Int = 0,
        val total: Int = 0,
        val failed: Int = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    suspend fun run(requests: List<DownloadRequest>) = withContext(Dispatchers.IO) {
        val pending = requests.filterNot { store.isDownloaded(it.itemId) }
        if (pending.isEmpty()) {
            _progress.value = Progress(running = false)
            return@withContext
        }
        var done = 0
        var failed = 0
        _progress.value = Progress(running = true, total = pending.size)

        for (r in pending) {
            _progress.value = _progress.value.copy(currentTitle = r.title)
            val target = store.fileFor(r.itemId)
            val temp = File(target.parentFile, target.name + ".part")
            val ok = runCatching {
                val request = Request.Builder().url(r.streamUrl).build()
                client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val stream = response.body?.byteStream() ?: error("réponse vide")
                    temp.outputStream().use { out -> stream.copyTo(out) }
                }
                check(temp.length() > 0) { "fichier vide" }
                if (target.exists()) target.delete()
                check(temp.renameTo(target)) { "renommage impossible" }
                true
            }.getOrElse {
                runCatching { temp.delete() }
                false
            }

            if (ok) {
                store.add(
                    DownloadEntry(
                        itemId = r.itemId,
                        title = r.title,
                        artist = r.artist,
                        album = r.album,
                        artworkUrl = r.artworkUrl,
                        fileName = target.name,
                        sizeBytes = target.length(),
                        addedAt = System.currentTimeMillis(),
                    )
                )
                done++
            } else {
                failed++
            }
            _progress.value = _progress.value.copy(done = done, failed = failed)
        }
        _progress.value = Progress(running = false, done = done, total = pending.size, failed = failed)
    }

    /** Efface le bandeau de progression une fois qu'il a été lu. */
    fun clearProgress() {
        _progress.value = Progress()
    }
}
