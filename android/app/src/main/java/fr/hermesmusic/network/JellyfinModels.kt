package fr.hermesmusic.network

import kotlinx.serialization.Serializable

/* DTO Jellyfin — les noms de champs sont ceux renvoyés par l'API (PascalCase). */

@Serializable
data class PublicSystemInfo(
    val ServerName: String? = null,
    val Version: String? = null,
    val Id: String? = null,
)

@Serializable
data class AuthRequest(val Username: String, val Pw: String)

@Serializable
data class JfUser(
    val Id: String = "",
    val Name: String? = null,
)

@Serializable
data class AuthResult(
    val User: JfUser? = null,
    val AccessToken: String? = null,
    val ServerId: String? = null,
)

@Serializable
data class JfUserData(
    val IsFavorite: Boolean = false,
    val PlayCount: Int = 0,
    val PlayedPercentage: Double? = null,
    val LastPlayedDate: String? = null,
)

@Serializable
data class JfItem(
    val Id: String = "",
    val Name: String? = null,
    val Type: String? = null,
    val Album: String? = null,
    val AlbumId: String? = null,
    val Artists: List<String> = emptyList(),
    val AlbumArtist: String? = null,
    val ProductionYear: Int? = null,
    val RunTimeTicks: Long? = null,
    val IndexNumber: Int? = null,
    val ChildCount: Int? = null,
    /** Présent uniquement pour un élément lu depuis une playlist (sert à le retirer/réordonner). */
    val PlaylistItemId: String? = null,
    val ImageTags: Map<String, String> = emptyMap(),
    val AlbumPrimaryImageTag: String? = null,
    val UserData: JfUserData? = null,
) {
    val durationSeconds: Int get() = ((RunTimeTicks ?: 0L) / 10_000_000L).toInt()

    val artistLine: String
        get() = when {
            Artists.isNotEmpty() -> Artists.joinToString(", ")
            !AlbumArtist.isNullOrBlank() -> AlbumArtist
            else -> "Artiste inconnu"
        }

    val hasCover: Boolean
        get() = AlbumPrimaryImageTag != null || ImageTags.containsKey("Primary")

    /** Pour un morceau, la pochette est celle de l'album. */
    val coverItemId: String
        get() = if (Type == "Audio") (AlbumId ?: Id) else Id

    val isFavorite: Boolean
        get() = UserData?.IsFavorite == true
}

@Serializable
data class QueryResult(
    val Items: List<JfItem> = emptyList(),
    val TotalRecordCount: Int = 0,
)

/**
 * Corps envoyé à Jellyfin pour déclarer une lecture (/Sessions/Playing,
 * /Sessions/Playing/Progress). Jellyfin reste la source de vérité de
 * l'historique : c'est ce corps qui alimente « Reprendre la lecture »,
 * le compteur d'écoute et la liste des morceaux récemment joués.
 */
@Serializable
data class PlaybackProgressInfo(
    val ItemId: String,
    /** 1 seconde = 10 000 000 ticks (unité .NET utilisée par Jellyfin). */
    val PositionTicks: Long = 0,
    val IsPaused: Boolean = false,
    val IsMuted: Boolean = false,
    val CanSeek: Boolean = true,
    /** Lecture directe du fichier : c'est exactement ce que fait notre flux `static=true`. */
    val PlayMethod: String = "DirectStream",
    val VolumeLevel: Int = 100,
)

/* --- Playlists --- */

@Serializable
data class CreatePlaylistRequest(
    val Name: String,
    val Ids: List<String> = emptyList(),
    val MediaType: String = "Audio",
    val UserId: String? = null,
)

@Serializable
data class CreatePlaylistResult(val Id: String? = null)

/** `POST /Playlists/{id}` : renommer (et/ou réordonner) une playlist. */
@Serializable
data class UpdatePlaylistRequest(
    val Name: String,
    val Ids: List<String> = emptyList(),
    val Users: List<PlaylistUserRef> = emptyList(),
)

@Serializable
data class PlaylistUserRef(val UserId: String, val CanEdit: Boolean = true)
