package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubTracksTest {
    // What the PWA sends to HomeMusicAndroid.playQueue (web/src/lib/player.ts, playTracks).
    private val json = """[
        {"song_id": "nd-1"},
        {"ref": "dz:3135556", "title": "Chop Suey!", "artist": "System of a Down", "album": "Toxicity",
         "cover_url": "https://cdn-images.dzcdn.net/images/cover/1.jpg", "duration_s": 210.4},
        {"ref": "hm:7", "title": "Своё", "artist": "Я", "album": null, "cover_url": null, "duration_s": null}
    ]"""

    private fun items() = HubClient.gson.fromJson(json, Array<HubTracks.Item>::class.java).toList()

    @Test
    fun queueItemsAreParsedFromThePageJson() {
        val items = items()
        assertEquals("nd-1", items[0].songId)
        assertNull(items[0].ref)
        assertEquals("dz:3135556", items[1].ref)
        assertEquals("Toxicity", items[1].album)
        assertEquals(210.4, items[1].durationS!!, 0.001)
    }

    @Test
    fun catalogTrackBecomesAHubSong() {
        val song = HubTracks.child(items()[1])!!
        assertEquals("hub:dz:3135556", song.id)
        assertTrue(HubTracks.isHub(song.id))
        assertEquals("Chop Suey!", song.title)
        assertEquals("System of a Down", song.artist)
        assertEquals(210, song.duration)
        // the cover's own address: CustomGlideRequest.createUrl passes http(s) through
        assertEquals("https://cdn-images.dzcdn.net/images/cover/1.jpg", song.coverArtId)
        // the player screen finds it by id
        assertEquals("Chop Suey!", HubTracks.song("hub:dz:3135556").title)
    }

    @Test
    fun catalogTrackWithoutCoverOrDuration() {
        val song = HubTracks.child(items()[2])!!
        assertEquals("hub:hm:7", song.id)
        assertNull(song.coverArtId)
        assertNull(song.duration)
    }

    @Test
    fun itemWithoutRefIsNotACatalogTrack() {
        assertNull(HubTracks.child(items()[0]))
        assertNull(HubTracks.child(HubTracks.Item().apply { ref = " " }))
    }

    @Test
    fun onlyHubIdsAreHub() {
        assertFalse(HubTracks.isHub("nd-1"))
        assertFalse(HubTracks.isHub(null))
        assertFalse(HubTracks.isHub("dz:1"))
        assertTrue(HubTracks.isHub("hub:dz:1"))
    }

    @Test
    fun relativeCoverIsDropped() {
        val song = HubTracks.child(HubTracks.Item().apply { ref = "dz:9"; coverUrl = "/api/cover/9" })!!
        assertNull(song.coverArtId)
    }
}
