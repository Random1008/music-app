package fr.hermesmusic.player

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Une entrée de la file de lecture, telle qu'affichée à l'utilisateur. */
data class QueueEntry(
    val index: Int,
    val id: String,
    val title: String,
    val artist: String,
)

/** État du lecteur exposé à l'interface. */
data class PlayerUiState(
    val hasItem: Boolean = false,
    /** Identifiant Jellyfin du morceau en cours (sert aussi à déclarer la lecture). */
    val itemId: String = "",
    val title: String = "",
    val artist: String = "",
    val artworkUrl: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val queueSize: Int = 0,
    val queue: List<QueueEntry> = emptyList(),
)

/**
 * Pont entre l'interface et le lecteur qui vit dans [PlaybackService].
 * L'UI ne fait que refléter l'état : elle ne possède jamais le lecteur.
 */
class PlayerConnection(
    private val context: Context,
    private val toMediaItem: (TrackPayload) -> MediaItem,
) {

    /** Données minimales nécessaires pour construire un élément lisible. */
    data class TrackPayload(
        val id: String,
        val title: String,
        val artist: String,
        val artworkUrl: String?,
        val streamUrl: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var controller: MediaController? = null
    private var ticker: Job? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
        override fun onIsPlayingChanged(isPlaying: Boolean) = refresh()
        override fun onPlaybackStateChanged(playbackState: Int) = refresh()
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) = refresh()
    }

    fun connect() {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controller = runCatching { future.get() }.getOrNull()?.also { it.addListener(listener) }
                refresh()
                startTicker()
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    fun release() {
        ticker?.cancel()
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    /** Remplace la file par [tracks] et démarre à [startIndex]. */
    fun play(tracks: List<TrackPayload>, startIndex: Int = 0) {
        val c = controller ?: return
        if (tracks.isEmpty()) return
        val items = tracks.map(toMediaItem)
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
        c.prepare()
        c.play()
        refresh()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
        refresh()
    }

    fun next() = controller?.seekToNextMediaItem()

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 4_000) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = controller?.seekTo(ms)

    /** Saute directement à l'entrée [index] de la file (clic dans la file). */
    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in 0 until c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
        refresh()
    }

    fun setShuffle(on: Boolean) {
        controller?.shuffleModeEnabled = on
    }

    fun setRepeatMode(mode: Int) {
        controller?.repeatMode = mode
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (true) {
                val c = controller
                if (c != null && c.isPlaying) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                        durationMs = c.duration.takeIf { it > 0 } ?: _state.value.durationMs,
                    )
                }
                delay(500)
            }
        }
    }

    private fun refresh() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val count = c.mediaItemCount
        _state.value = PlayerUiState(
            hasItem = count > 0,
            itemId = c.currentMediaItem?.mediaId.orEmpty(),
            title = md.title?.toString().orEmpty(),
            artist = md.artist?.toString().orEmpty(),
            artworkUrl = md.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: 0L,
            queueSize = count,
            queue = readQueue(c, count),
        )
    }

    private fun readQueue(c: MediaController, count: Int): List<QueueEntry> =
        runCatching {
            (0 until count).map { i ->
                val item = c.getMediaItemAt(i)
                QueueEntry(
                    index = i,
                    id = item.mediaId,
                    title = item.mediaMetadata.title?.toString().orEmpty(),
                    artist = item.mediaMetadata.artist?.toString().orEmpty(),
                )
            }
        }.getOrDefault(emptyList())
}
