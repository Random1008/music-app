package fr.hermesmusic

import fr.hermesmusic.network.AuthRequest
import fr.hermesmusic.network.AuthResult
import fr.hermesmusic.network.CreatePlaylistRequest
import fr.hermesmusic.network.CreatePlaylistResult
import fr.hermesmusic.network.JellyfinApi
import fr.hermesmusic.network.JfUser
import fr.hermesmusic.network.PlaybackProgressInfo
import fr.hermesmusic.network.PublicSystemInfo
import fr.hermesmusic.network.QueryResult
import fr.hermesmusic.network.UpdatePlaylistRequest

/**
 * Fausse API Jellyfin pour les tests JVM : elle enregistre ce qui lui est
 * demandé au lieu de parler au réseau. Un seul exemplaire pour tous les tests
 * (l'interface a beaucoup de méthodes, on ne veut pas la réimplémenter partout).
 */
class FakeJellyfinApi(
    var itemsResponse: QueryResult = QueryResult(),
    var resumeResponse: QueryResult = QueryResult(),
) : JellyfinApi {

    val itemsQueries = mutableListOf<Map<String, String>>()
    val artistsQueries = mutableListOf<Map<String, String>>()
    val userItemsQueries = mutableListOf<Map<String, String>>()
    val resumeQueries = mutableListOf<Map<String, String>>()

    val started = mutableListOf<PlaybackProgressInfo>()
    val progress = mutableListOf<PlaybackProgressInfo>()
    val stopped = mutableListOf<PlaybackProgressInfo>()

    val favoritesAdded = mutableListOf<String>()
    val favoritesRemoved = mutableListOf<String>()

    val createdPlaylists = mutableListOf<CreatePlaylistRequest>()
    val renamedPlaylists = mutableListOf<Pair<String, String>>()
    val deletedItems = mutableListOf<String>()
    val movedItems = mutableListOf<Triple<String, String, Int>>()

    override suspend fun publicInfo() = PublicSystemInfo(ServerName = "Fake", Version = "12.0.0")

    override suspend fun authenticateByName(body: AuthRequest): AuthResult = AuthResult()

    override suspend fun users(): List<JfUser> = emptyList()

    override suspend fun items(query: Map<String, String>): QueryResult {
        itemsQueries += query
        return itemsResponse
    }

    override suspend fun artists(query: Map<String, String>): QueryResult {
        artistsQueries += query
        return itemsResponse
    }

    override suspend fun userItems(userId: String, query: Map<String, String>): QueryResult {
        userItemsQueries += query
        return itemsResponse
    }

    override suspend fun resume(userId: String, query: Map<String, String>): QueryResult {
        resumeQueries += query
        return resumeResponse
    }

    override suspend fun addFavorite(userId: String, itemId: String) {
        favoritesAdded += itemId
    }

    override suspend fun removeFavorite(userId: String, itemId: String) {
        favoritesRemoved += itemId
    }

    override suspend fun reportPlaybackStart(body: PlaybackProgressInfo) {
        started += body
    }

    override suspend fun reportPlaybackProgress(body: PlaybackProgressInfo) {
        progress += body
    }

    override suspend fun reportPlaybackStopped(body: PlaybackProgressInfo) {
        stopped += body
    }

    override suspend fun createPlaylist(body: CreatePlaylistRequest): CreatePlaylistResult {
        createdPlaylists += body
        return CreatePlaylistResult(Id = "playlist-" + createdPlaylists.size)
    }

    override suspend fun updatePlaylist(playlistId: String, body: UpdatePlaylistRequest) {
        renamedPlaylists += playlistId to body.Name
    }

    override suspend fun playlistItems(
        playlistId: String,
        query: Map<String, String>,
    ): QueryResult = itemsResponse

    override suspend fun addToPlaylist(playlistId: String, ids: String, userId: String) = Unit

    override suspend fun removeFromPlaylist(playlistId: String, entryIds: String) = Unit

    override suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) {
        movedItems += Triple(playlistId, entryId, newIndex)
    }

    override suspend fun deleteItem(itemId: String) {
        deletedItems += itemId
    }
}
