package com.eddyizm.tempus.homemusic

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import androidx.media3.exoplayer.source.TrackGroupArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueLoadingTest {
    @Test
    fun theNextTrackLoadsOnlyInTheLastSecondsOfThePlayingOne() {
        // the playing track itself (a position in it) loads whole
        assertTrue(QueueLoading.mayLoad(0))
        assertTrue(QueueLoading.mayLoad(150_000_000))
        // the next playlist item: the position is minus the time until it starts
        assertFalse(QueueLoading.mayLoad(-120_000_000))
        assertFalse(QueueLoading.mayLoad(-(QueueLoading.NEXT_LEAD_S + 1) * 1_000_000L))
        assertTrue(QueueLoading.mayLoad(-QueueLoading.NEXT_LEAD_S * 1_000_000L))
        assertTrue(QueueLoading.mayLoad(-5_000_000))
    }

    @Test
    fun whenSavingAYoungTrackLoadsOnlyFortySecondsAhead() {
        val sec = 1_000_000L
        // not saving: as before, the whole track
        assertTrue(QueueLoading.mayLoad(5 * sec, 40 * sec, economy = false))
        assertTrue(QueueLoading.mayLoad(5 * sec, 170 * sec, economy = false))
        // saving, the first seconds: 40 s ahead and no more
        assertTrue(QueueLoading.mayLoad(0, 0, economy = true))
        assertTrue(QueueLoading.mayLoad(5 * sec, 39 * sec, economy = true))
        assertFalse(QueueLoading.mayLoad(5 * sec, 40 * sec, economy = true))
        assertFalse(QueueLoading.mayLoad(29 * sec, 60 * sec, economy = true))
        // past 30 s the track loads whole (to get through a tunnel)
        assertTrue(QueueLoading.mayLoad(30 * sec, 40 * sec, economy = true))
        assertTrue(QueueLoading.mayLoad(120 * sec, 100 * sec, economy = true))
        // a rewind into a track loaded whole earlier changes nothing for the position it came back to
        assertTrue(QueueLoading.mayLoad(35 * sec, 140 * sec, economy = true))
    }

    @Test
    fun whenSavingTheNextTrackStillStartsInItsLeadAndIsHeldTo40Seconds() {
        val sec = 1_000_000L
        // the next playlist item: the position is minus the time until it starts; the lead stays the same
        assertFalse(QueueLoading.mayLoad(-(QueueLoading.NEXT_LEAD_S + 1) * sec, 0, economy = true))
        assertTrue(QueueLoading.mayLoad(-QueueLoading.NEXT_LEAD_S * sec, 0, economy = true))
        // what is loaded ahead counts from the playing position, so 40 s ahead is 20 s of the next track
        assertFalse(QueueLoading.mayLoad(-QueueLoading.NEXT_LEAD_S * sec, 40 * sec, economy = true))
        assertTrue(QueueLoading.mayLoad(-QueueLoading.NEXT_LEAD_S * sec, 39 * sec, economy = true))
    }

    @Test
    fun aHeadIsShorterWhenSaving() {
        val slack = 64 * 1024L
        assertEquals(320_000L / 8 * QueueLoading.ECONOMY_HEAD_S + slack, QueueLoading.headBytes(320, economy = true))
        assertTrue(QueueLoading.headBytes(320, economy = true) < QueueLoading.headBytes(320, economy = false))
        // the 15 s head of a 320 kbps file is what the precache loaded before
        assertEquals(QueueLoading.headBytes(320), QueueLoading.headBytes(320, economy = false))
    }

    @Test
    fun thePlayersCallsReachTheBuffer() {
        // a Java default method left undelegated throws "not implemented" (or loses the buffer's state)
        HomeTraffic.forced = false
        val lc = QueueLoading.loadControl()
        val id = PlayerId.UNSET
        lc.onPrepared(id)
        val track = SinglePeriodTimeline(180_000_000, true, false, false, null, MediaItem.EMPTY)
        val period = MediaSource.MediaPeriodId(track.getUidOfPeriod(0))
        fun params(positionUs: Long) = LoadControl.Parameters(id, track, period,
            positionUs, 0, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        lc.onTracksSelected(params(0), TrackGroupArray.EMPTY, arrayOf())
        assertTrue(lc.shouldContinueLoading(params(0))) // an empty buffer of the playing track
        assertFalse(lc.shouldContinueLoading(params(-60_000_000))) // the next track, a minute ahead
        lc.getAllocator(id)
        lc.getBackBufferDurationUs(id)
        lc.onStopped(id)
        lc.onReleased(id)
    }

    @Test
    fun theBufferStopsAt40SecondsOnlyWhenSaving() {
        val lc = QueueLoading.loadControl()
        val id = PlayerId.UNSET
        lc.onPrepared(id)
        val track = SinglePeriodTimeline(180_000_000, true, false, false, null, MediaItem.EMPTY)
        val period = MediaSource.MediaPeriodId(track.getUidOfPeriod(0))
        fun params(positionUs: Long, bufferedUs: Long) = LoadControl.Parameters(id, track, period,
            positionUs, bufferedUs, 1f, true, false, C.TIME_UNSET, C.TIME_UNSET)
        lc.onTracksSelected(params(0, 0), TrackGroupArray.EMPTY, arrayOf())
        try {
            HomeTraffic.forced = true
            assertTrue(lc.shouldContinueLoading(params(5_000_000, 20_000_000)))
            assertFalse(lc.shouldContinueLoading(params(5_000_000, 45_000_000)))
            assertTrue(lc.shouldContinueLoading(params(31_000_000, 45_000_000))) // past 30 s: the whole track
            HomeTraffic.forced = false
            assertTrue(lc.shouldContinueLoading(params(5_000_000, 45_000_000)))
        } finally {
            HomeTraffic.forced = false
        }
    }

    @Test
    fun aHeadIsTheFirstSecondsOfTheFile() {
        val slack = 64 * 1024L
        assertEquals(128_000L / 8 * QueueLoading.HEAD_S + slack, QueueLoading.headBytes(128))
        assertEquals(1_000_000L / 8 * QueueLoading.HEAD_S + slack, QueueLoading.headBytes(1000)) // FLAC
        // a catalog track's bitrate is not known: 320 kbps, the most a source gives
        assertEquals(QueueLoading.headBytes(320), QueueLoading.headBytes(0))
        assertEquals(256 * 1024L, QueueLoading.headBytes(32)) // never less than a useful start
    }

    @Test
    fun onlyAFileAsIsCanBeContinuedPastItsHead() {
        assertTrue(QueueLoading.untranscoded(null, null)) // the server decides: Navidrome sends the file
        assertTrue(QueueLoading.untranscoded("raw", "0"))
        assertFalse(QueueLoading.untranscoded("mp3", "0"))
        assertFalse(QueueLoading.untranscoded("raw", "192"))
        assertFalse(QueueLoading.untranscoded("opus", "128"))
    }
}
