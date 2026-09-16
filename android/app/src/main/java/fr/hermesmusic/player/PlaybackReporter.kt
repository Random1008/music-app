package fr.hermesmusic.player

import fr.hermesmusic.network.JellyfinApi
import fr.hermesmusic.network.PlaybackProgressInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Déclare les lectures à Jellyfin. Jellyfin est la source de vérité de
 * l'historique (§4) : sans ces appels, le serveur ne sait pas ce que
 * l'application joue — donc pas de « Reprendre la lecture », pas de
 * « Récemment écouté », pas de compteur d'écoute.
 *
 * Une erreur de déclaration ne doit JAMAIS interrompre la musique : toutes
 * les requêtes sont encapsulées et leurs échecs ignorés silencieusement.
 */
class PlaybackReporter(
    private val api: () -> JellyfinApi,
    private val state: StateFlow<PlayerUiState>,
    /** Injectable pour pouvoir tester la cadence sans attendre dix secondes. */
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        /** Fréquence des points de reprise : valeur usuelle des clients Jellyfin. */
        const val PROGRESS_EVERY_MS = 10_000L
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            var currentId: String? = null
            var wasPlaying = false
            var lastReportAt = 0L

            state.collect { s ->
                val id = s.itemId
                if (id.isBlank()) return@collect
                val time = now()

                // Morceau différent de celui déclaré : on annonce le démarrage.
                if (id != currentId) {
                    currentId = id
                    wasPlaying = s.isPlaying
                    lastReportAt = time
                    send { it.reportPlaybackStart(info(s)) }
                    return@collect
                }

                val playingChanged = s.isPlaying != wasPlaying
                val due = s.isPlaying && (time - lastReportAt) >= PROGRESS_EVERY_MS
                wasPlaying = s.isPlaying

                // On saute les rapports pendant la mise en mémoire tampon : un
                // état transitoire ne doit pas être déclaré comme une pause.
                if (!s.isBuffering && (playingChanged || due)) {
                    lastReportAt = time
                    send { it.reportPlaybackProgress(info(s)) }
                }
            }
        }
    }

    private fun info(s: PlayerUiState) = PlaybackProgressInfo(
        ItemId = s.itemId,
        PositionTicks = s.positionMs * 10_000L,
        IsPaused = !s.isPlaying,
    )

    private suspend fun send(block: suspend (JellyfinApi) -> Unit) {
        runCatching { block(api()) }
    }
}
