package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Child
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSelectionTest {
    private val a = Child("a")
    private val b = Child("b")
    private val c = Child("c")

    @Test
    fun toggle_picks_and_drops_a_song_and_reports_each_change() {
        var changes = 0
        val selection = SongSelection().apply { onChange = { changes++ } }

        selection.toggle(b)
        assertTrue(selection.isActive)
        assertTrue(selection.isSelected(Child("b")))

        selection.toggle(b)
        assertFalse(selection.isActive)
        assertEquals(2, changes)
    }

    @Test
    fun pick_follows_the_list_order_once_per_song_and_skips_songs_the_list_lost() {
        val selection = SongSelection()
        selection.toggle(c)
        selection.toggle(a)
        selection.toggle(Child("gone"))

        // Visible rows then the full list, as the bar passes them: a song is in both.
        assertEquals(listOf("a", "c"), selection.pick(listOf(a, b, c, a, c)).map { it.id })
    }

    @Test
    fun toggle_all_picks_every_row_then_drops_them() {
        val selection = SongSelection()
        selection.toggle(a)

        selection.toggleAll(listOf(a, b))
        assertEquals(listOf("a", "b"), selection.pick(listOf(a, b, c)).map { it.id })

        selection.toggleAll(listOf(a, b))
        assertFalse(selection.isActive)
    }

    @Test
    fun toggle_all_leaves_picks_outside_the_rows_alone() {
        val selection = SongSelection()
        selection.toggle(c)
        selection.toggleAll(listOf(a, b))

        selection.toggleAll(listOf(a, b))
        assertEquals(listOf("c"), selection.pick(listOf(a, b, c)).map { it.id })
    }

    @Test
    fun clear_without_picks_reports_nothing() {
        var changes = 0
        val selection = SongSelection().apply { onChange = { changes++ } }

        selection.clear()
        assertEquals(0, changes)
    }

    @Test
    fun the_bar_heart_is_a_bin_on_the_liked_page_and_a_heart_elsewhere() {
        assertEquals(HomeSongSelection.LikeAction.DELETE, HomeSongSelection.LikeAction.of(starredPage = true, allLiked = true))
        assertEquals(HomeSongSelection.LikeAction.UNLIKE, HomeSongSelection.LikeAction.of(starredPage = false, allLiked = true))
        assertEquals(HomeSongSelection.LikeAction.LIKE, HomeSongSelection.LikeAction.of(starredPage = true, allLiked = false))
        assertEquals(HomeSongSelection.LikeAction.LIKE, HomeSongSelection.LikeAction.of(starredPage = false, allLiked = false))
    }
}
