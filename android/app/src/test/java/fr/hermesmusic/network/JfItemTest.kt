package fr.hermesmusic.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Logique de lecture des DTO Jellyfin : aucune dépendance Android, testable sur la JVM. */
class JfItemTest {

    @Test
    fun `la duree se convertit depuis les ticks`() {
        // Jellyfin compte en ticks .NET : 1 seconde = 10 000 000.
        assertEquals(222, JfItem(RunTimeTicks = 2_220_000_000L).durationSeconds)
        assertEquals(0, JfItem().durationSeconds)
    }

    @Test
    fun `l artiste tombe en repli sur l album-artiste puis sur un libelle neutre`() {
        assertEquals("A, B", JfItem(Artists = listOf("A", "B")).artistLine)
        assertEquals("Solo", JfItem(AlbumArtist = "Solo").artistLine)
        assertEquals("Artiste inconnu", JfItem().artistLine)
    }

    @Test
    fun `la pochette d un morceau est celle de son album`() {
        val track = JfItem(Id = "t1", Type = "Audio", AlbumId = "al1")
        assertEquals("al1", track.coverItemId)
        // Un album sans AlbumId (cas normal) garde son propre identifiant.
        assertEquals("al2", JfItem(Id = "al2", Type = "MusicAlbum").coverItemId)
        // Un morceau orphelin ne casse rien.
        assertEquals("t2", JfItem(Id = "t2", Type = "Audio").coverItemId)
    }

    @Test
    fun `la pochette n est annoncee que si le serveur a une image`() {
        assertFalse(JfItem().hasCover)
        assertTrue(JfItem(ImageTags = mapOf("Primary" to "abc")).hasCover)
        assertTrue(JfItem(AlbumPrimaryImageTag = "abc").hasCover)
    }

    @Test
    fun `le favori se lit dans les donnees utilisateur`() {
        assertFalse(JfItem().isFavorite)
        assertTrue(JfItem(UserData = JfUserData(IsFavorite = true)).isFavorite)
    }
}
