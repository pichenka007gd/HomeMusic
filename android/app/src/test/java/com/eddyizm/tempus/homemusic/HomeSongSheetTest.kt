package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeSongSheetTest {
    @Test
    fun remove_from_liked_shows_only_for_a_liked_song_opened_from_the_liked_page() {
        assertTrue(HomeSongSheet.showsRemoveFromLiked(fromLiked = true, liked = true, songId = "nd1"))
        assertFalse(HomeSongSheet.showsRemoveFromLiked(fromLiked = false, liked = true, songId = "nd1"))
        // Unliked with the sheet's ♥ meanwhile, or from the registry.
        assertFalse(HomeSongSheet.showsRemoveFromLiked(fromLiked = true, liked = false, songId = "nd1"))
        assertTrue(HomeSongSheet.showsRemoveFromLiked(fromLiked = true, liked = true, songId = "hub:dz:1"))
    }
}
