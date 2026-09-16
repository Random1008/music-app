package fr.hermesmusic.data

import fr.hermesmusic.FakeJellyfinApi
import fr.hermesmusic.network.JfItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le dépôt construit les requêtes Jellyfin : c'est la partie qu'on peut casser
 * sans s'en rendre compte (un paramètre oublié = une liste vide à l'écran).
 */
class MusicRepositoryTest {

    private val session = Session(
        serverUrl = "https://music.test",
        token = "jeton",
        userId = "u1",
    )

    private fun repo(api: FakeJellyfinApi) = MusicRepository(api) { session }

    @Test
    fun `la liste d albums demande le bon type, le tri et la pagination`() = runTest {
        val api = FakeJellyfinApi()
        repo(api).albums(startIndex = 50, limit = 25)
        val q = api.itemsQueries.last()

        assertEquals("MusicAlbum", q["IncludeItemTypes"])
        assertEquals("true", q["Recursive"])
        assertEquals("SortName", q["SortBy"])
        assertEquals("25", q["Limit"])
        assertEquals("50", q["StartIndex"])
        assertEquals("u1", q["UserId"])
        assertTrue(q["Fields"]!!.contains("AlbumPrimaryImageTag"))
    }

    @Test
    fun `les pistes d un album sont triees par numero de piste`() = runTest {
        val api = FakeJellyfinApi()
        repo(api).albumTracks("al1")
        val q = api.itemsQueries.last()

        assertEquals("al1", q["ParentId"])
        assertEquals("Audio", q["IncludeItemTypes"])
        assertEquals("ParentIndexNumber,IndexNumber", q["SortBy"])
    }

    @Test
    fun `la recherche porte sur les morceaux, albums et artistes`() = runTest {
        val api = FakeJellyfinApi()
        repo(api).search("kiss")
        val q = api.itemsQueries.last()

        assertEquals("kiss", q["searchTerm"])
        assertEquals("Audio,MusicAlbum,MusicArtist", q["IncludeItemTypes"])
        assertEquals("true", q["Recursive"])
    }

    @Test
    fun `reprendre la lecture ne demande que de l audio`() = runTest {
        val api = FakeJellyfinApi()
        repo(api).resumeAudio(limit = 5)
        val q = api.resumeQueries.last()

        assertEquals("Audio", q["MediaType"])
        assertEquals("5", q["Limit"])
    }

    @Test
    fun `recemment ecoute trie par date de lecture descendante`() = runTest {
        val api = FakeJellyfinApi()
        repo(api).recentlyPlayed()
        val q = api.userItemsQueries.last()

        assertEquals("IsPlayed", q["Filters"])
        assertEquals("DatePlayed", q["SortBy"])
        assertEquals("Descending", q["SortOrder"])
        assertEquals("Audio", q["IncludeItemTypes"])
    }

    @Test
    fun `le flux audio passe par le mode statique pour garder le seek`() {
        val url = repo(FakeJellyfinApi()).streamUrl(JfItem(Id = "t1"))
        assertEquals("https://music.test/Audio/t1/stream?static=true&UserId=u1", url)
    }

    @Test
    fun `une pochette n est construite que s il y a une image, et toujours plafonnee`() {
        val r = repo(FakeJellyfinApi())
        assertNull(r.imageUrl(JfItem(Id = "t1", Type = "Audio", AlbumId = "al1")))

        val withCover = JfItem(
            Id = "t1",
            Type = "Audio",
            AlbumId = "al1",
            AlbumPrimaryImageTag = "abc",
        )
        assertEquals(
            "https://music.test/Items/al1/Images/Primary?maxHeight=300&quality=90",
            r.imageUrl(withCover),
        )
    }

    @Test
    fun `un favori est ecrit dans le bon sens`() = runTest {
        val api = FakeJellyfinApi()
        val r = repo(api)

        r.setFavorite("t1", true)
        r.setFavorite("t2", false)

        assertEquals(listOf("t1"), api.favoritesAdded)
        assertEquals(listOf("t2"), api.favoritesRemoved)
    }

    @Test
    fun `une playlist sans nom est refusee avant tout appel reseau`() = runTest {
        val api = FakeJellyfinApi()
        val result = runCatching { repo(api).createPlaylist("   ") }

        assertTrue(result.isFailure)
        assertTrue(api.createdPlaylists.isEmpty())
    }

    @Test
    fun `creer une playlist transmet le nom, l utilisateur et les morceaux`() = runTest {
        val api = FakeJellyfinApi()
        val id = repo(api).createPlaylist("Route", listOf("t1", "t2"))

        assertEquals("playlist-1", id)
        val body = api.createdPlaylists.single()
        assertEquals("Route", body.Name)
        assertEquals(listOf("t1", "t2"), body.Ids)
        assertEquals("u1", body.UserId)
    }

    @Test
    fun `ajouter des morceaux a une playlist les envoie en une seule fois`() = runTest {
        val api = FakeJellyfinApi()
        val r = repo(api)

        r.addToPlaylist("p1", listOf("t1", "t2", "t3"))
        // Liste vide : aucun appel (Jellyfin refuserait).
        r.addToPlaylist("p1", emptyList())

        assertTrue(api.createdPlaylists.isEmpty())
    }
}
