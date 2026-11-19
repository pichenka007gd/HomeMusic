package com.eddyizm.tempus.homemusic

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueAheadTest {
    @Test
    fun theQueueIsToppedUpWhileFewerThanAheadTracksAreLeft() {
        val off = Player.REPEAT_MODE_OFF
        assertTrue(QueueAhead.wantsMore(true, off, music = true, upcoming = 0))
        assertTrue(QueueAhead.wantsMore(true, off, music = true, upcoming = QueueAhead.AHEAD - 1))
        assertFalse(QueueAhead.wantsMore(true, off, music = true, upcoming = QueueAhead.AHEAD))
        // continuous play off, a repeating queue (never ends), radio and podcasts: nothing to add
        assertFalse(QueueAhead.wantsMore(false, off, music = true, upcoming = 0))
        assertFalse(QueueAhead.wantsMore(true, Player.REPEAT_MODE_ALL, music = true, upcoming = 0))
        assertFalse(QueueAhead.wantsMore(true, Player.REPEAT_MODE_ONE, music = true, upcoming = 0))
        assertFalse(QueueAhead.wantsMore(true, off, music = false, upcoming = 0))
    }

    @Test
    fun similarSongsAreAskedForTheLastLibrarySongUpToTheCurrentOne() {
        // a mix: library songs and catalog tracks of the Hub, in play order
        val ids = listOf("nd-1", "hub:dz:2", "nd-3", "hub:dz:4", "hub:dz:5", "nd-6")
        assertEquals(2, QueueAhead.seedIndex(ids, 4)) // nd-3: a catalog track is not in Navidrome
        assertEquals(2, QueueAhead.seedIndex(ids, 2))
        assertEquals(5, QueueAhead.seedIndex(ids, 5)) // not a track after the current one
        assertEquals(-1, QueueAhead.seedIndex(listOf("hub:dz:1", "hub:dz:2"), 1)) // none: the current track
        assertEquals(-1, QueueAhead.seedIndex(ids, 6))
    }
}
