package fr.hermesmusic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {

    @Test
    fun `une duree en secondes s affiche en minutes et secondes`() {
        assertEquals("0:00", fmtDuration(0))
        assertEquals("3:42", fmtDuration(222))
        assertEquals("4:07", fmtDuration(247))
        // Au-delà d'une heure on reste en minutes (choix assumé : une piste dure rarement plus).
        assertEquals("61:05", fmtDuration(3665))
    }

    @Test
    fun `une position en millisecondes s affiche de la meme facon`() {
        assertEquals("0:00", fmtMs(0))
        assertEquals("1:42", fmtMs(102_000))
        assertEquals("1:42", fmtMs(102_999))
        // Une position négative (transitoire au démarrage) ne doit pas planter.
        assertEquals("0:00", fmtMs(-5_000))
    }

    @Test
    fun `une taille de fichier s affiche dans l unite lisible`() {
        assertEquals("512 o", fmtBytes(512))
        assertEquals("2 Ko", fmtBytes(2_048))
        assertEquals("24 Mo", fmtBytes(25_165_824))
        assertEquals("1,4 Go", fmtBytes(1_503_238_553))
    }
}
