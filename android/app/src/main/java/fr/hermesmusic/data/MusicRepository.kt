package fr.hermesmusic.data

import fr.hermesmusic.network.CreatePlaylistRequest
import fr.hermesmusic.network.JfItem
import fr.hermesmusic.network.JellyfinApi
import fr.hermesmusic.network.PlaylistUserRef
import fr.hermesmusic.network.QueryResult
import fr.hermesmusic.network.UpdatePlaylistRequest

/**
 * Accès aux données musicales. Jellyfin reste la source de vérité : on ne
 * duplique rien, on ne met rien en cache local à ce stade.
 */
class MusicRepository(
    private val api: JellyfinApi,
    private val session: () -> Session,
) {
    companion object {
        const val FIELDS =
            "ImageTags,AlbumPrimaryImageTag,AlbumArtist,Artists,ArtistItems,Album,AlbumId," +
                "ProductionYear,ChildCount,RunTimeTicks,IndexNumber,UserData,PlaylistItemId"
    }

    private fun uid() = session().userId

    private fun base(extra: Map<String, String> = emptyMap()) = buildMap {
        put("Fields", FIELDS)
        put("UserId", uid())
        putAll(extra)
    }

    suspend fun recentAlbums(limit: Int = 30, startIndex: Int = 0) = api.items(
        base(
            mapOf(
                "IncludeItemTypes" to "MusicAlbum",
                "Recursive" to "true",
                "SortBy" to "DateCreated",
                "SortOrder" to "Descending",
                "Limit" to "$limit",
                "StartIndex" to "$startIndex",
            )
        )
    )

    suspend fun albums(startIndex: Int = 0, limit: Int = 60) = api.items(
        base(
            mapOf(
                "IncludeItemTypes" to "MusicAlbum",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "$limit",
                "StartIndex" to "$startIndex",
            )
        )
    )

    suspend fun artists(startIndex: Int = 0, limit: Int = 60) = api.artists(
        mapOf(
            "Recursive" to "true",
            "SortBy" to "SortName",
            "Limit" to "$limit",
            "StartIndex" to "$startIndex",
            "Fields" to "ImageTags",
        )
    )

    suspend fun recentTracks(limit: Int = 40) = api.items(
        base(
            mapOf(
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "DateCreated",
                "SortOrder" to "Descending",
                "Limit" to "$limit",
            )
        )
    )

    suspend fun tracks(startIndex: Int = 0, limit: Int = 80) = api.items(
        base(
            mapOf(
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "$limit",
                "StartIndex" to "$startIndex",
            )
        )
    )

    /** Morceaux déjà écoutés, du plus récent au plus ancien (historique Jellyfin). */
    suspend fun recentlyPlayed(limit: Int = 20) = api.userItems(
        uid(),
        mapOf(
            "Filters" to "IsPlayed",
            "IncludeItemTypes" to "Audio",
            "Recursive" to "true",
            "SortBy" to "DatePlayed",
            "SortOrder" to "Descending",
            "Limit" to "$limit",
            "Fields" to FIELDS,
        )
    )

    /** Écouté en partie mais pas terminé : sert au bouton « Reprendre la lecture ». */
    suspend fun resumeAudio(limit: Int = 12) = api.resume(
        uid(),
        mapOf(
            "MediaType" to "Audio",
            "Limit" to "$limit",
            "Fields" to FIELDS,
        )
    )

    suspend fun albumTracks(albumId: String) = api.items(
        base(
            mapOf(
                "ParentId" to albumId,
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "ParentIndexNumber,IndexNumber",
            )
        )
    )

    suspend fun artistAlbums(artistId: String) = api.items(
        base(
            mapOf(
                "ArtistIds" to artistId,
                "IncludeItemTypes" to "MusicAlbum",
                "Recursive" to "true",
                "SortBy" to "ProductionYear,SortName",
                "Limit" to "80",
            )
        )
    )

    suspend fun artistTracks(artistId: String) = api.items(
        base(
            mapOf(
                "ArtistIds" to artistId,
                "IncludeItemTypes" to "Audio",
                "Recursive" to "true",
                "SortBy" to "PlayCount,SortName",
                "SortOrder" to "Descending",
                "Limit" to "40",
            )
        )
    )

    suspend fun search(query: String): QueryResult = api.items(
        base(
            mapOf(
                "searchTerm" to query,
                "IncludeItemTypes" to "Audio,MusicAlbum,MusicArtist",
                "Recursive" to "true",
                "Limit" to "40",
            )
        )
    )

    suspend fun favorites(): QueryResult = api.userItems(
        uid(),
        buildMap {
            put("Filters", "IsFavorite")
            put("Recursive", "true")
            put("IncludeItemTypes", "Audio")
            put("SortBy", "SortName")
            put("Limit", "200")
            put("Fields", FIELDS)
        }
    )

    suspend fun playlists(): QueryResult = api.items(
        base(
            mapOf(
                "IncludeItemTypes" to "Playlist",
                "Recursive" to "true",
                "SortBy" to "SortName",
                "Limit" to "100",
            )
        )
    )

    suspend fun playlistItems(playlistId: String): QueryResult = api.playlistItems(
        playlistId,
        mapOf("UserId" to uid(), "Fields" to FIELDS),
    )

    suspend fun item(itemId: String): JfItem =
        itemOrNull(itemId) ?: error("Élément introuvable : $itemId")

    suspend fun itemOrNull(itemId: String): JfItem? =
        runCatching { api.items(base(mapOf("Ids" to itemId))).Items.firstOrNull() }.getOrNull()

    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        if (favorite) api.addFavorite(uid(), itemId) else api.removeFavorite(uid(), itemId)
    }

    /* --- Playlists --- */

    /** Crée une playlist. Jellyfin exige un nom non vide. */
    suspend fun createPlaylist(name: String, itemIds: List<String> = emptyList()): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Le nom de la playlist est vide" }
        val created = api.createPlaylist(
            CreatePlaylistRequest(Name = clean, Ids = itemIds, UserId = uid())
        )
        return created.Id?.takeIf { it.isNotBlank() }
            ?: error("Jellyfin n'a pas renvoyé l'identifiant de la playlist")
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Le nom de la playlist est vide" }
        api.updatePlaylist(
            playlistId,
            UpdatePlaylistRequest(Name = clean, Users = listOf(PlaylistUserRef(uid()))),
        )
    }

    suspend fun deletePlaylist(playlistId: String) = api.deleteItem(playlistId)

    suspend fun addToPlaylist(playlistId: String, itemIds: List<String>) {
        if (itemIds.isEmpty()) return
        api.addToPlaylist(playlistId, itemIds.joinToString(","), uid())
    }

    /** [entryId] est le `PlaylistItemId` renvoyé par [playlistItems], pas l'identifiant du morceau. */
    suspend fun removeFromPlaylist(playlistId: String, entryId: String) =
        api.removeFromPlaylist(playlistId, entryId)

    suspend fun movePlaylistItem(playlistId: String, entryId: String, newIndex: Int) =
        api.movePlaylistItem(playlistId, entryId, newIndex)

    /** URL de pochette, plafonnée (jamais de pleine résolution, cf. perf §28). */
    fun imageUrl(item: JfItem, maxHeight: Int = 300): String? =
        if (!item.hasCover) null
        else "${session().serverUrl}/Items/${item.coverItemId}/Images/Primary?maxHeight=$maxHeight&quality=90"

    /** Flux audio direct : `static=true` renvoie audio/mpeg avec support du Range (seek). */
    fun streamUrl(item: JfItem): String =
        "${session().serverUrl}/Audio/${item.Id}/stream?static=true&UserId=${session().userId}"
}
