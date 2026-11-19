package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.ArtistID3
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class HomeRadioArtistsTest {
    private fun artists(vararg ids: String) = ids.map { ArtistID3(id = it, name = "Артист $it") }

    @Test
    fun theStarredComeFirstThenEveryOtherArtistOnce() {
        val got = HomeRadioArtists.order(artists("b", "a", "a"), artists("a", "c", "d", "b", "e") + ArtistID3(name = "Без id"),
            Random(1)).map { it.id }

        assertEquals(setOf("a", "b"), got.take(2).toSet())
        assertEquals(setOf("c", "d", "e"), got.drop(2).toSet())
        assertEquals(5, got.size)
    }

    @Test
    fun withoutStarsOrServerTheRowIsWhatIsKnown() {
        assertEquals(listOf("x"), HomeRadioArtists.order(null, artists("x")).map { it.id })
        assertEquals(listOf("s"), HomeRadioArtists.order(artists("s"), null).map { it.id })
        assertEquals(emptyList<ArtistID3>(), HomeRadioArtists.order(null, null))
    }
}
