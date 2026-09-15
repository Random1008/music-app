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
}

@Serializable
data class QueryResult(
    val Items: List<JfItem> = emptyList(),
    val TotalRecordCount: Int = 0,
)
