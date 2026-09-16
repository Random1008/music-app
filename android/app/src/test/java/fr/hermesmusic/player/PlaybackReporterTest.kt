package fr.hermesmusic.player

import fr.hermesmusic.FakeJellyfinApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ce que l'application déclare à Jellyfin décide de tout l'historique : s'il
 * manque un appel, « Reprendre la lecture » reste vide et le compteur d'écoute
 * ne bouge jamais. On teste donc la cadence, sans attendre dix secondes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackReporterTest {

    @Test
    fun `un changement de morceau est declare une seule fois`() = runTest {
        val api = FakeJellyfinApi()
        val state = MutableStateFlow(PlayerUiState())
        var clock = 0L
        val reporter = PlaybackReporter(api = { api }, state = state, now = { clock })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "a", isPlaying = true, positionMs = 1_000)
        advanceUntilIdle()
        assertEquals(1, api.started.size)
        assertEquals("a", api.started.single().ItemId)

        // Même morceau, la position avance : pas de nouveau « démarrage ».
        clock = 2_000
        state.value = state.value.copy(positionMs = 2_000)
        advanceUntilIdle()
        assertEquals(1, api.started.size)

        // Passage au morceau suivant : un nouveau démarrage.
        state.value = PlayerUiState(itemId = "b", isPlaying = true)
        advanceUntilIdle()
        assertEquals(2, api.started.size)
        assertEquals("b", api.started.last().ItemId)

        scope.cancel()
    }

    @Test
    fun `un point de reprise est envoye toutes les dix secondes de lecture`() = runTest {
        val api = FakeJellyfinApi()
        val state = MutableStateFlow(PlayerUiState())
        var clock = 0L
        val reporter = PlaybackReporter(api = { api }, state = state, now = { clock })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "a", isPlaying = true)
        advanceUntilIdle()
        assertEquals(1, api.started.size)
        assertEquals(0, api.progress.size)

        // 5 s plus tard : trop tôt, rien n'est envoyé.
        clock = 5_000
        state.value = state.value.copy(positionMs = 5_000)
        advanceUntilIdle()
        assertEquals(0, api.progress.size)

        // 11 s plus tard : le point de reprise part, avec la bonne conversion en ticks.
        clock = 11_000
        state.value = state.value.copy(positionMs = 12_000)
        advanceUntilIdle()
        assertEquals(1, api.progress.size)
        assertEquals(120_000_000L, api.progress.single().PositionTicks)

        scope.cancel()
    }

    @Test
    fun `une pause est declaree immediatement`() = runTest {
        val api = FakeJellyfinApi()
        val state = MutableStateFlow(PlayerUiState())
        var clock = 0L
        val reporter = PlaybackReporter(api = { api }, state = state, now = { clock })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "a", isPlaying = true, positionMs = 3_000)
        advanceUntilIdle()
        clock = 500
        state.value = state.value.copy(isPlaying = false, positionMs = 3_500)
        advanceUntilIdle()

        assertEquals(1, api.progress.size)
        assertEquals(true, api.progress.single().IsPaused)
        assertEquals("a", api.progress.single().ItemId)

        scope.cancel()
    }

    @Test
    fun `la mise en memoire tampon n est pas declaree comme une pause`() = runTest {
        val api = FakeJellyfinApi()
        val state = MutableStateFlow(PlayerUiState())
        var clock = 0L
        val reporter = PlaybackReporter(api = { api }, state = state, now = { clock })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "a", isPlaying = true)
        advanceUntilIdle()
        clock = 1_000
        state.value = state.value.copy(isPlaying = false, isBuffering = true)
        advanceUntilIdle()

        assertTrue(api.progress.isEmpty())

        scope.cancel()
    }

    @Test
    fun `un item vide n est jamais declare`() = runTest {
        val api = FakeJellyfinApi()
        val state = MutableStateFlow(PlayerUiState())
        val reporter = PlaybackReporter(api = { api }, state = state, now = { 0L })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "", isPlaying = false)
        advanceUntilIdle()

        assertTrue(api.started.isEmpty())
        assertTrue(api.progress.isEmpty())

        scope.cancel()
    }

    @Test
    fun `une erreur reseau ne fait pas remonter d exception`() = runTest {
        // Le serveur disparaît : la déclaration échoue, la musique doit continuer.
        val failing = object : fr.hermesmusic.network.JellyfinApi by FakeJellyfinApi() {
            override suspend fun reportPlaybackStart(
                body: fr.hermesmusic.network.PlaybackProgressInfo,
            ) {
                error("réseau injoignable")
            }
        }
        val state = MutableStateFlow(PlayerUiState())
        val reporter = PlaybackReporter(api = { failing }, state = state, now = { 0L })

        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + Job())
        reporter.start(scope)

        state.value = PlayerUiState(itemId = "a", isPlaying = true)
        advanceUntilIdle()

        // Aucune exception n'a interrompu le collecteur : il est toujours vivant.
        assertTrue(scope.isActive)
        scope.cancel()
    }
}
