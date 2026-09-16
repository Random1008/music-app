package fr.hermesmusic.network

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.QueryMap

/** API Jellyfin réellement utilisée par l'application (validée sur le prototype web). */
interface JellyfinApi {

    @GET("System/Info/Public")
    suspend fun publicInfo(): PublicSystemInfo

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: AuthRequest): AuthResult

    @GET("Users")
    suspend fun users(): List<JfUser>

    @GET("Items")
    suspend fun items(@QueryMap query: Map<String, String>): QueryResult

    @GET("Artists")
    suspend fun artists(@QueryMap query: Map<String, String>): QueryResult

    @GET("Users/{userId}/Items")
    suspend fun userItems(
        @Path("userId") userId: String,
        @QueryMap query: Map<String, String>,
    ): QueryResult

    @POST("Users/{userId}/FavoriteItems/{itemId}")
    suspend fun addFavorite(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    )

    @DELETE("Users/{userId}/FavoriteItems/{itemId}")
    suspend fun removeFavorite(
        @Path("userId") userId: String,
        @Path("itemId") itemId: String,
    )

    /* --- Déclaration des lectures : sans ces appels, Jellyfin ignore ce que
       l'application joue (pas d'historique, pas de reprise, pas de compteur). --- */

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(@Body body: PlaybackProgressInfo)

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: PlaybackProgressInfo)

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: PlaybackProgressInfo)
}
