package com.eddyizm.tempus.homemusic

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LikedSongsTest {
    @Test fun browserLikesWithoutDownloadedFilesArePlayableNativeSongs() {
        val body = Gson().fromJson("""{"songs":[
            {"ref":"dz:100","title":"New like","artist":"Artist","album_title":"Album",
             "duration_s":214.5,"cover_url":"/api/nd/cover/art","nd_song_id":null},
            {"ref":"dz:200","title":"Library like","nd_song_id":"song200","nd_album_id":"album2",
             "nd_artist_id":"artist2","track_no":3,"disc_no":1,"cover_url":"/api/nd/cover/art%3A2?size=1000"}
        ]}""", HubLikedSongs::class.java)
        val songs = body.children("https://music.example/")
        assertEquals(listOf("hub:dz:100", "song200"), songs.map { it.id })
        assertEquals("New like", songs[0].title)
        assertEquals("Album", songs[0].album)
        assertEquals(214, songs[0].duration)
        assertEquals("https://music.example/api/nd/cover/art", songs[0].coverArtId)
        assertEquals("album2", songs[1].albumId)
        assertEquals("artist2", songs[1].artistId)
        assertEquals("art:2", songs[1].coverArtId)
        assertEquals(3, songs[1].track)
        assertEquals(1, songs[1].discNumber)
        assertTrue(songs[0].starred!!.after(songs[1].starred))
    }

    @Test fun successfulHubListIncludingEmptyNeverUsesNavidromeFallback() {
        for (liked in listOf(listOf("hub:dz:100"), emptyList())) {
            var delivered: List<String>? = null
            loadLikedSongs(1, { 1 }, { liked }, { error("Must use the Hub list") }) { delivered = it }
            assertEquals(liked, delivered)
        }
    }

    @Test fun unavailableHubUsesExistingNavidromeList() {
        var delivered: List<String>? = null
        loadLikedSongs(1, { 1 }, { throw IOException() }, { listOf("nd1") }) { delivered = it }
        assertEquals(listOf("nd1"), delivered)
    }

    @Test fun accountChangesDiscardSuccessAndDoNotStartFallbackForOldAccount() {
        var generation = 1L
        loadLikedSongs(1, { generation }, { generation++; listOf("old") },
            { error("Old account fallback") }) { fail("Old account response") }
        generation = 1
        loadLikedSongs(1, { generation }, { generation++; throw IOException() },
            { error("Old account fallback") }) { fail("Old account response") }
        generation = 1
        loadLikedSongs(1, { generation }, { throw IOException() },
            { generation++; listOf("old") }) { fail("Old account response") }
    }
}
