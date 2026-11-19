package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Artist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeIndexSearchTest {
    private val artists = listOf(
        Artist("1", "Panic! At The Disco"),
        Artist("2", "Кино"),
        Artist("3", "Ёлка"),
        Artist("4", "Beyoncé"),
        Artist("5", null),
    )

    private fun names(query: String) = HomeIndexSearch.filter(artists, query).map { it.name }

    @Test
    fun blank_query_keeps_the_whole_list() {
        assertEquals(artists, HomeIndexSearch.filter(artists, "  "))
    }

    @Test
    fun every_word_must_match_anywhere_in_the_name_ignoring_case_and_punctuation() {
        assertEquals(listOf("Panic! At The Disco"), names("disco PANIC"))
        assertEquals(listOf("Panic! At The Disco"), names("panic!"))
        assertEquals(emptyList<String>(), names("panic rock"))
    }

    @Test
    fun accents_and_yo_do_not_matter() {
        assertEquals(listOf("Beyoncé"), names("beyonce"))
        assertEquals(listOf("Ёлка"), names("елка"))
        assertTrue(HomeIndexSearch.matches("Йорш", "йо"))
    }

    @Test
    fun latin_letters_find_a_cyrillic_name() {
        assertEquals(listOf("Кино"), names("kino"))
        assertFalse(HomeIndexSearch.matches("Кино", "kono"))
    }
}
