package fr.hermesmusic.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIndexTest {

    private val sample = DownloadEntry(
        itemId = "abc",
        title = "Titre",
        artist = "Artiste",
        album = "Album",
        artworkUrl = "https://music.test/img",
        fileName = "abc.mp3",
        sizeBytes = 5_432_100,
        addedAt = 1_760_000_000_000,
    )

    @Test
    fun `l index survit a un aller-retour`() {
        val entries = listOf(sample, sample.copy(itemId = "def", fileName = "def.mp3"))
        assertEquals(entries, DownloadIndex.decode(DownloadIndex.encode(entries)))
    }

    @Test
    fun `les champs optionnels n empechent pas la relecture`() {
        val entry = DownloadEntry(itemId = "x", title = "T", artist = "A", fileName = "x.mp3")
        assertEquals(entry, DownloadIndex.decode(DownloadIndex.encode(listOf(entry))).single())
    }

    @Test
    fun `un index vide reste vide`() {
        assertTrue(DownloadIndex.decode(DownloadIndex.encode(emptyList())).isEmpty())
    }

    @Test
    fun `un index corrompu ne fait pas planter l application`() {
        // Cas réel : fichier tronqué après une coupure d'alimentation.
        assertTrue(DownloadIndex.decode("{ceci n'est pas du json").isEmpty())
        assertTrue(DownloadIndex.decode("").isEmpty())
        assertTrue(DownloadIndex.decode("null").isEmpty())
    }
}
