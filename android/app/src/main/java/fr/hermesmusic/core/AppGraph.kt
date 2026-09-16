package fr.hermesmusic.core

import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import fr.hermesmusic.data.MusicRepository
import fr.hermesmusic.data.Session
import fr.hermesmusic.data.SettingsStore
import fr.hermesmusic.network.AuthRequest
import fr.hermesmusic.network.JellyfinApi
import fr.hermesmusic.network.JfItem
import fr.hermesmusic.player.PlayerConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Conteneur d'injection MANUEL (pas de Hilt/Koin : complexité inutile ici).
 * Expose la session, le client HTTP partagé et le dépôt de données.
 */
class AppGraph(private val context: Context) {

    companion object {
        const val CLIENT = "Hermes Music"
        const val DEVICE = "Android"
        const val VERSION = "0.3.0"
    }

    val settings = SettingsStore(context)

    private val _session = MutableStateFlow(Session())
    val session: StateFlow<Session> = _session.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

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

    /** Convertit un élément Jellyfin en charge utile lisible par le lecteur. */
    fun payload(item: JfItem) = PlayerConnection.TrackPayload(
        id = item.Id,
        title = item.Name.orEmpty(),
        artist = item.artistLine,
        artworkUrl = repo.imageUrl(item, maxHeight = 600),
        streamUrl = repo.streamUrl(item),
    )

    /** Lance la lecture d'une liste à partir de l'index donné. */
    fun play(items: List<JfItem>, index: Int = 0) {
        if (items.isEmpty()) return
        player.play(items.map { payload(it) }, index)
    }

    /** Recharge la session depuis le disque (jeton, URL, identifiant d'appareil). */
    suspend fun hydrate() {
        val deviceId = settings.ensureDeviceId()
        _session.value = settings.current().copy(deviceId = deviceId)
        _ready.value = true
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
                "Connecté à « ${info.ServerName ?: url} » (Jellyfin ${info.Version ?: "?"})"
            }
        }

    suspend fun logout() {
        settings.clearToken()
        _session.value = _session.value.copy(token = "", userId = "", userName = "")
    }
}
