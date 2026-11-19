package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Playlist
import org.junit.Assert.assertEquals
import org.junit.Test

class HomePlaylistCardsTest {
    private val playlists = listOf(
        Playlist("1", "Mcr songs"),
        Playlist("2", "Яндекс Музыка"),
        Playlist("3", "Ёлка в дорогу"),
        Playlist("4", null),
    )

    private fun names(query: String) = HomePlaylistCards.filter(playlists, query).map { it.name }

    @Test
    fun blank_query_keeps_every_playlist_in_order() {
        assertEquals(playlists, HomePlaylistCards.filter(playlists, " "))
    }

    @Test
    fun search_matches_like_the_folder_search() {
        assertEquals(listOf("Mcr songs"), names("SONGS mcr"))
        assertEquals(listOf("Ёлка в дорогу"), names("елка"))
        assertEquals(listOf("Яндекс Музыка"), names("muzyka"))
        assertEquals(emptyList<String>(), names("rock"))
    }
}
