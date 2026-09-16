package fr.hermesmusic.core

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.hermesmusic.data.DownloadEntry
import fr.hermesmusic.data.DownloadRequest
import fr.hermesmusic.data.DownloadStore
import fr.hermesmusic.data.Downloader
import fr.hermesmusic.data.MusicRepository
import fr.hermesmusic.data.Session
import fr.hermesmusic.data.SettingsStore
import fr.hermesmusic.network.AuthRequest
import fr.hermesmusic.network.JellyfinApi
import fr.hermesmusic.network.JfItem
import fr.hermesmusic.player.PlaybackReporter
import fr.hermesmusic.player.PlayerConnection
import fr.hermesmusic.player.SavedTrack
import fr.hermesmusic.player.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** Écran empilé par-dessus les onglets. */
sealed interface Detail {
    val id: String
    val title: String

    data class Album(override val id: String, override val title: String) : Detail
    data class Artist(override val id: String, override val title: String) : Detail
    data class Playlist(override val id: String, override val title: String) : Detail

    data object Settings : Detail {
        override val id: String get() = "settings"
        override val title: String get() = "Paramètres"
    }

    data object Downloads : Detail {
        override val id: String get() = "downloads"
        override val title: String get() = "Hors-ligne"
    }
}

/** Joignabilité du serveur Jellyfin (spec §27 : jamais de crash, une erreur claire). */
enum class ServerState { Unknown, Reachable, Unreachable }

/**
 * Conteneur d'injection MANUEL (pas de Hilt/Koin : complexité inutile ici).
 * Expose la session, le client HTTP partagé et le dépôt de données.
 */
class AppGraph(private val context: Context) {

    companion object {
        const val CLIENT = "Hermes Music"
        const val DEVICE = "Android"
        const val VERSION = "0.7.0"

        /** Cadence d'écriture de la reprise de session. */
        private const val SESSION_SAVE_EVERY_MS = 5_000L

        /** Taille maximale de la file restaurée (évite un fichier obèse). */
        private const val SESSION_MAX_TRACKS = 200
    }

    val settings = SettingsStore(context)

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _serverState = MutableStateFlow(ServerState.Unknown)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    /** Écran de détail ouvert, ou null. */
    private val _detail = MutableStateFlow<Detail?>(null)
    val detail: StateFlow<Detail?> = _detail.asStateFlow()

    fun openAlbum(id: String, title: String) { _detail.value = Detail.Album(id, title) }
    fun openArtist(id: String, title: String) { _detail.value = Detail.Artist(id, title) }
    fun openPlaylist(id: String, title: String) { _detail.value = Detail.Playlist(id, title) }
    fun openSettings() { _detail.value = Detail.Settings }
    fun openDownloads() { _detail.value = Detail.Downloads }
    fun closeDetail() { _detail.value = null }

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    /** En-tête d'authentification Jellyfin, ajouté à CHAQUE requête (API et images). */
    private val authInterceptor = Interceptor { chain ->
        val s = _session.value
        val header = buildString {
            append("MediaBrowser ")
            if (s.token.isNotBlank()) append("Token=\"${s.token}\", ")
            append("Client=\"$CLIENT\", Device=\"$DEVICE\", ")
            append("DeviceId=\"${s.deviceId}\", Version=\"$VERSION\"")
        }
        chain.proceed(chain.request().newBuilder().header("Authorization", header).build())
    }

    /** Un SEUL client HTTP, partagé par Retrofit, Coil et le lecteur audio. */
    val okHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)
            .addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
            .build()
    }

    private var apiBase: String? = null
    private var apiInstance: JellyfinApi? = null

    /** Retrofit est reconstruit uniquement quand l'URL du serveur change. */
    @Synchronized
    fun api(): JellyfinApi {
        val serverUrl = _session.value.serverUrl
        require(serverUrl.isNotBlank()) { "Serveur non configuré" }
        val normalized = if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/"
        if (apiInstance == null || apiBase != normalized) {
            apiInstance = Retrofit.Builder()
                .baseUrl(normalized)
                .client(okHttp)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(JellyfinApi::class.java)
            apiBase = normalized
        }
        return apiInstance!!
    }

    val repo: MusicRepository by lazy {
        MusicRepository(api(), session = { _session.value })
    }

    val downloads = DownloadStore(context)
    private val downloader by lazy { Downloader(downloads, okHttp) }
    val downloadProgress = downloader.progress
    val downloadEntries = downloads.entries

    private val sessionStore = SessionStore(context)

    /**
     * Lecteur : le player vit dans [fr.hermesmusic.player.PlaybackService].
     * Ici on ne garde qu'une télécommande (MediaController) — l'interface ne
     * possède jamais le lecteur, donc la musique survit aux changements d'écran,
     * à la mise en arrière-plan et au verrouillage du téléphone.
     */
    val player: PlayerConnection by lazy {
        PlayerConnection(context) { p ->
            MediaItem.Builder()
                .setMediaId(p.id)
                .setUri(p.streamUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(p.title)
                        .setArtist(p.artist)
                        .setArtworkUri(p.artworkUrl?.toUri())
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build()
                )
                .build()
        }
    }

    /** Portée applicative : travail de fond (déclaration des lectures, téléchargements). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Portée du fil principal : tout ce qui touche au lecteur (Media3 l'exige). */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val reporter by lazy {
        PlaybackReporter(api = { api() }, state = player.state)
    }

    /**
     * Démarre la liaison au service de lecture, restaure la session précédente,
     * puis déclare les lectures à Jellyfin. Appelé une seule fois.
     */
    fun start() {
        player.connect {
            mainScope.launch {
                val saved = sessionStore.load()
                if (saved != null) {
                    player.restore(
                        tracks = saved.tracks.take(SESSION_MAX_TRACKS).map { payload(it) },
                        startIndex = saved.index.coerceAtLeast(0),
                        positionMs = saved.positionMs,
                    )
                }
            }
        }
        reporter.start(appScope)
        mainScope.launch { persistSession() }
    }

    /**
     * Écrit régulièrement la file et la position sur disque. Sans cela, Android
     * tuant le processus efface la lecture en cours.
     */
    private suspend fun persistSession() {
        var lastId = ""
        var lastWrite = 0L
        player.state.collect { s ->
            if (!s.hasItem) return@collect
            val now = System.currentTimeMillis()
            val itemChanged = s.itemId != lastId
            val due = (now - lastWrite) >= SESSION_SAVE_EVERY_MS
            if (itemChanged || due) {
                lastId = s.itemId
                lastWrite = now
                player.snapshot()?.let { sessionStore.save(it) }
            }
        }
    }

    /** Convertit un élément Jellyfin en charge utile lisible par le lecteur. */
    fun payload(item: JfItem): PlayerConnection.TrackPayload {
        val local = downloads.localFile(item.Id)
        return PlayerConnection.TrackPayload(
            id = item.Id,
            title = item.Name.orEmpty(),
            artist = item.artistLine,
            artworkUrl = repo.imageUrl(item, maxHeight = 600),
            streamUrl = local?.toUri()?.toString() ?: repo.streamUrl(item),
            fromDevice = local != null,
        )
    }

    /** Même chose, à partir d'une entrée de session restaurée. */
    private fun payload(track: SavedTrack): PlayerConnection.TrackPayload {
        val local = downloads.localFile(track.id)
        return PlayerConnection.TrackPayload(
            id = track.id,
            title = track.title,
            artist = track.artist,
            artworkUrl = track.artworkUrl,
            streamUrl = local?.toUri()?.toString() ?: track.streamUrl,
            fromDevice = local != null,
        )
    }

    /** Lance la lecture d'une liste à partir de l'index donné. */
    fun play(items: List<JfItem>, index: Int = 0) {
        if (items.isEmpty()) return
        player.play(items.map { payload(it) }, index)
    }

    /**
     * Lit des morceaux déjà téléchargés, sans passer par Jellyfin : c'est ce qui
     * rend l'écoute possible quand le serveur est injoignable.
     */
    fun playDownloads(entries: List<DownloadEntry>, index: Int = 0) {
        val payloads = entries.mapNotNull { e ->
            val file = downloads.localFile(e.itemId) ?: return@mapNotNull null
            PlayerConnection.TrackPayload(
                id = e.itemId,
                title = e.title,
                artist = e.artist,
                artworkUrl = e.artworkUrl,
                streamUrl = file.toUri().toString(),
                fromDevice = true,
            )
        }
        if (payloads.isEmpty()) return
        player.play(payloads, index.coerceIn(0, payloads.lastIndex))
    }

    /* --- Hors-ligne --- */

    fun isDownloaded(itemId: String): Boolean = downloads.isDownloaded(itemId)

    /** Télécharge une liste de morceaux non encore présents sur l'appareil. */
    fun download(items: List<JfItem>) {
        val requests: List<DownloadRequest> = items
            .filterNot { downloads.isDownloaded(it.Id) }
            .map {
                DownloadRequest(
                    itemId = it.Id,
                    title = it.Name.orEmpty(),
                    artist = it.artistLine,
                    album = it.Album,
                    artworkUrl = repo.imageUrl(it, maxHeight = 600),
                    streamUrl = repo.streamUrl(it),
                )
            }
        if (requests.isEmpty()) return
        appScope.launch { downloader.run(requests) }
    }

    suspend fun removeDownload(itemId: String) = downloads.remove(itemId)
    suspend fun clearDownloads() = downloads.clear()
    fun clearDownloadProgress() = downloader.clearProgress()

    /* --- Réglages --- */

    /** Applique et persiste le thème + l'accent. */
    suspend fun setAppearance(dark: Boolean, accentIndex: Int) {
        Nocturne.dark = dark
        Nocturne.accentIndex = accentIndex
        settings.saveAppearance(dark, accentIndex)
    }

    /* --- Playlists --- */

    suspend fun createPlaylist(name: String): Result<String> =
        runCatching { repo.createPlaylist(name) }

    suspend fun renamePlaylist(playlistId: String, name: String): Result<Unit> =
        runCatching { repo.renamePlaylist(playlistId, name) }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        runCatching { repo.deletePlaylist(playlistId) }

    suspend fun addToPlaylist(playlistId: String, itemIds: List<String>): Result<Unit> =
        runCatching { repo.addToPlaylist(playlistId, itemIds) }

    suspend fun removeFromPlaylist(playlistId: String, entryId: String): Result<Unit> =
        runCatching { repo.removeFromPlaylist(playlistId, entryId) }

    suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int): Result<Unit> =
        runCatching { repo.movePlaylistItem(playlistId, entryId, newIndex) }

    /** Crée une playlist contenant déjà [itemIds] : évite deux gestes à l'utilisateur. */
    suspend fun createPlaylistWith(name: String, itemIds: List<String>): Result<String> =
        runCatching { repo.createPlaylist(name, itemIds) }

    /* --- Session --- */

    /** Recharge état persisté : jeton, URL, appareil, apparence, téléchargements. */
    suspend fun hydrate() {
        val deviceId = settings.ensureDeviceId()
        _session.value = settings.current().copy(deviceId = deviceId)
        val appearance = settings.appearance()
        Nocturne.dark = appearance.dark
        Nocturne.accentIndex = appearance.accentIndex
        downloads.load()
        _ready.value = true
        if (_session.value.isConnected) pingServer()
    }

    /** Teste la joignabilité du serveur ; l'interface propose « Réessayer ». */
    suspend fun pingServer(): Boolean = withContext(Dispatchers.IO) {
        val reachable = runCatching { api().publicInfo() }.isSuccess
        _serverState.value = if (reachable) ServerState.Reachable else ServerState.Unreachable
        reachable
    }

    /** Connexion : vérifie le serveur, s'authentifie, persiste le jeton (jamais le mot de passe). */
    suspend fun connect(serverUrl: String, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = serverUrl.trim().trimEnd('/')
                require(url.startsWith("http")) { "L'adresse doit commencer par http:// ou https://" }
                _session.value = _session.value.copy(serverUrl = url)
                val info = api().publicInfo()
                val result = api().authenticateByName(AuthRequest(username, password))
                val token = result.AccessToken?.takeIf { it.isNotBlank() }
                    ?: error("Le serveur n'a pas renvoyé de jeton")
                val user = result.User ?: error("Le serveur n'a pas renvoyé d'utilisateur")
                settings.saveConnection(url, token, user.Id, user.Name ?: username)
                _session.value = _session.value.copy(
                    token = token,
                    userId = user.Id,
                    userName = user.Name ?: username,
                )
                _serverState.value = ServerState.Reachable
                "Connecté à « ${info.ServerName ?: url} » (Jellyfin ${info.Version ?: "?"})"
            }
        }

    suspend fun logout() {
        player.clear()
        sessionStore.clear()
        settings.clearToken()
        _session.value = _session.value.copy(token = "", userId = "", userName = "")
        _detail.value = null
        _serverState.value = ServerState.Unknown
    }
}
