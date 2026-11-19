package com.eddyizm.tempus.homemusic

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveTest {
    private fun track(ref: String, song: String? = null, generator: String? = null) = WaveTrack().apply {
        this.ref = ref
        ndSongId = song
        owned = song != null
        title = "t-$ref"
        artist = "a-$ref"
        this.generator = generator
    }

    private fun event(kind: String, id: String, context: String?) = PlayEvent.of(kind, id, "app/test", 0, context = context)

    private fun session() = WaveSession("w1", "Моя волна", null, "normal")

    @Test
    fun seededActionsUseSupportedIdsAndPreferLibraryArtist() {
        assertEquals(mapOf("ref" to "dz:42"), WaveSeeds.track("hub:dz:42"))
        assertEquals(mapOf("ref" to "dz:42", "by_artist" to "true"), WaveSeeds.artist("hub:dz:42", null))
        assertEquals(mapOf("ref" to "nd:song"), WaveSeeds.track("hub:nd:song"))
        assertEquals(mapOf("ref" to "nd:song", "by_artist" to "true"), WaveSeeds.artist("hub:nd:song", null))
        assertNull(WaveSeeds.track("hub:nd:"))
        assertEquals(mapOf("nd_artist_id" to "a"), WaveSeeds.artist("song", "a"))
        assertEquals(mapOf("nd_song_id" to "song", "by_artist" to "true"), WaveSeeds.artist("song", null))
        assertNull(WaveSeeds.track("hub:file:42"))
        assertNull(WaveSeeds.track(" "))
    }

    // --- the answer goes into the queue ------------------------------------------------------

    @Test
    fun anUnchangedHeadStaysAndOnlyTheRestIsReplaced() {
        val plan = WavePlan.of(listOf("a", "b", "c"), listOf("a", "b", "x", "y", "z"))
        assertEquals(2, plan.keep)
        assertEquals(listOf(2), plan.remove)
        assertEquals(listOf(2, 3, 4), plan.add)
        // the new ones follow the kept head
        assertEquals(6, plan.insertAt(listOf(4, 5, 6), 7))
    }

    @Test
    fun aNewNextTrackReplacesEverythingAhead() {
        val plan = WavePlan.of(listOf("a", "b"), listOf("x", "a", "b"))
        assertEquals(0, plan.keep)
        assertEquals(listOf(0, 1), plan.remove)
        assertEquals(listOf(0, 1, 2), plan.add)
        // where the replaced ones were, not after a track the user put in between
        assertEquals(3, plan.insertAt(listOf(3, 5), 6))
    }

    @Test
    fun nothingAheadAppendsAtTheEnd() {
        val plan = WavePlan.of(emptyList(), listOf("x", "y"))
        assertEquals(listOf(0, 1), plan.add)
        assertEquals(8, plan.insertAt(emptyList(), 8))
    }

    @Test
    fun theSameAnswerChangesNothing() {
        val plan = WavePlan.of(listOf("a", "b", "c"), listOf("a", "b", "c"))
        assertTrue(plan.remove.isEmpty())
        assertTrue(plan.add.isEmpty())
    }

    @Test
    fun anEmptyAnswerKeepsTheQueue() {
        // a ran-out pool must not empty what is queued: the Hub sends the queued ones back while they fit
        val plan = WavePlan.of(listOf("a", "b"), listOf("a", "b"))
        assertTrue(plan.remove.isEmpty())
    }

    @Test
    fun aTrackIsNotAddedTwice() {
        val plan = WavePlan.of(listOf("a"), listOf("a", "x", "a", "x"))
        assertEquals(listOf(1), plan.add)
    }

    // --- the session: which events ask, what is sent -----------------------------------------

    @Test
    fun theStartOfAWaveTrackAsksAndItsEventIsSent() {
        val wave = session()
        val ids = wave.register(listOf(track("dz:1", song = "s1"), track("dz:2")))
        assertEquals(listOf("s1", "hub:dz:1", "hub:dz:2"), ids)
        assertTrue(wave.onEvent(event(PlayEvent.START, "s1", "wave:w1")))
        val request = wave.request(listOf("hub:dz:2"))
        assertEquals(listOf("start"), request.played.map { it.kind })
        assertEquals(listOf("dz:2"), request.queued)
    }

    @Test
    fun skipsAndFinishesGoWithTheNextCallWithoutAskingThemselves() {
        val wave = session()
        wave.register(listOf(track("dz:1"), track("dz:2")))
        assertFalse(wave.onEvent(event(PlayEvent.SKIP, "hub:dz:1", "wave:w1")))
        assertFalse(wave.onEvent(event(PlayEvent.FINISH, "hub:dz:1", "wave:w1")))
        // the start of the next track that follows a skip asks, with the skip in it
        assertTrue(wave.onEvent(event(PlayEvent.START, "hub:dz:2", "wave:w1")))
        assertEquals(listOf("skip", "finish", "start"), wave.request(emptyList()).played.map { it.kind })
    }

    @Test
    fun aLikeOrDislikeOfAWaveTrackAsks() {
        val wave = session()
        wave.register(listOf(track("dz:1")))
        assertTrue(wave.onEvent(event(PlayEvent.LIKE, "hub:dz:1", "wave:w1")))
        assertTrue(wave.onEvent(event(PlayEvent.DISLIKE, "hub:dz:1", "wave:w1")))
        assertFalse(wave.onEvent(event(PlayEvent.UNLIKE, "hub:dz:1", "wave:w1")))
    }

    @Test
    fun eventsOfOtherListsAreNotTheWaves() {
        val wave = session()
        wave.register(listOf(track("dz:1")))
        assertFalse(wave.onEvent(event(PlayEvent.START, "s9", "album")))
        assertFalse(wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:other")))
        assertTrue(wave.request(emptyList()).played.isEmpty())
    }

    @Test
    fun aFailedCallSendsItsEventsAgainWithTheNextOne() {
        val wave = session()
        wave.register(listOf(track("dz:1"), track("dz:2")))
        wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w1"))
        val failed = wave.request(emptyList())
        // no answer: nothing is dropped; a skip comes meanwhile
        wave.onEvent(event(PlayEvent.SKIP, "hub:dz:1", "wave:w1"))
        val again = wave.request(emptyList())
        assertEquals(failed.played.map { it.eventId } + again.played.last().eventId, again.played.map { it.eventId })
        wave.sent(again)
        assertTrue(wave.request(emptyList()).played.isEmpty())
    }

    @Test
    fun eventsThatCameDuringACallStayForTheNextOne() {
        val wave = session()
        wave.register(listOf(track("dz:1"), track("dz:2")))
        wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w1"))
        val request = wave.request(emptyList())
        wave.onEvent(event(PlayEvent.LIKE, "hub:dz:1", "wave:w1"))
        wave.sent(request)
        assertEquals(listOf("like"), wave.request(emptyList()).played.map { it.kind })
    }

    @Test
    fun queuedNamesTheRefsOfTheTracksAheadInOrder() {
        val wave = session()
        wave.register(listOf(track("dz:1", song = "s1"), track("dz:2"), track("nd:s3", song = "s3")))
        // a library song streamed from the Hub (Navidrome did not answer) is still that track
        assertEquals(listOf("nd:s3", "dz:1", "dz:2"), wave.request(listOf("s3", "hub:dz:1", "s9", "hub:dz:2")).queued)
    }

    @Test
    fun aRenewedSessionTakesOverTheTracksWithItsOwnContext() {
        val wave = session()
        wave.register(listOf(track("dz:1")))
        wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w1"))
        wave.failures = 3
        wave.renew("w2")
        assertEquals("wave:w2", wave.context)
        assertTrue(wave.isWave("hub:dz:1"))
        assertTrue(wave.request(emptyList()).played.isEmpty())
        assertEquals(0, wave.failures)
        assertFalse(wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w1")))
        assertTrue(wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w2")))
    }

    @Test
    fun aWaveStartsAgainWithItsSeedAndMode() {
        assertEquals(mapOf("seed" to mapOf("ref" to "dz:7"), "mode" to "discover"),
            WaveSession.startBody(mapOf("ref" to "dz:7", "artist" to "A", "title" to "T"), "discover"))
        assertEquals(mapOf("seed" to mapOf("artist_ref" to "dz:9")), WaveSession.startBody(mapOf("artist_ref" to "dz:9", "artist" to "A"), null))
        assertEquals(mapOf("seed" to mapOf("nd_artist_id" to "ar1")),
            WaveSession.startBody(mapOf("artist_ref" to "dz:9", "artist" to "A", "nd_artist_id" to "ar1"), null))
        assertEquals(emptyMap<String, Any>(), WaveSession.startBody(null, null))
    }

    // --- retries ------------------------------------------------------------------------------

    @Test
    fun failuresAreRetriedLaterAndLaterThenLeftToTheNextTrack() {
        assertEquals(5_000L, WaveRetry.afterFailure(1))
        assertEquals(15_000L, WaveRetry.afterFailure(2))
        assertEquals(300_000L, WaveRetry.afterFailure(10))
        assertNull(WaveRetry.afterFailure(WaveRetry.MAX_FAILURES + 1))
    }

    @Test
    fun aFillingPoolIsAskedSoonAFewTimes() {
        assertEquals(WaveRetry.POLL_MS, WaveRetry.afterPending(1))
        assertEquals(WaveRetry.POLL_MS, WaveRetry.afterPending(15))
        assertEquals(5_000L, WaveRetry.afterPending(16))
    }

    // --- JSON: the page's WaveOut and the body of next ----------------------------------------

    @Test
    fun thePagesWaveOutIsRead() {
        val json = """{"session_id":"abc","title":"Волна по треку «X»","mode":"normal","seed":{"ref":"dz:1","artist":"A","title":"X"},
            "tracks":[{"ref":"dz:1","nd_song_id":"s1","owned":true,"title":"X","artist":"A","cover_url":"http://c","duration_s":200.5,"generator":"seed"},
            {"ref":"dz:2","nd_song_id":null,"owned":false,"title":"Y","generator":"ytm_radio","familiar":false,"sim":null}],"pending":true,"recorded":null}"""
        val out = Gson().fromJson(json, WaveOut::class.java)
        assertEquals("abc", out.sessionId)
        assertTrue(out.pending)
        assertEquals("dz:1", out.seed?.get("ref"))
        val (first, second) = out.tracks!!
        assertEquals("s1", first.songId)
        assertEquals(WaveTrack.SEED, first.generator)
        assertNull(second.songId)
        val item = second.item("wave:abc")
        assertEquals("dz:2", item.ref)
        assertEquals("wave:abc", item.context)
    }

    @Test
    fun aSongIdWithoutOwnedIsNotPlayedFromNavidrome() {
        // nd_song_id of a track not in the library (a stale id) would not play: the Hub streams it
        assertNull(WaveTrack().apply { ref = "dz:1"; ndSongId = "s1"; owned = false }.songId)
    }

    @Test
    fun theNextBodyHasTheEventsAsSentAndTheQueuedRefs() {
        val wave = session()
        wave.register(listOf(track("dz:1"), track("dz:2")))
        wave.onEvent(event(PlayEvent.START, "hub:dz:1", "wave:w1"))
        val body = JsonParser.parseString(Gson().toJson(wave.request(listOf("hub:dz:2")))).asJsonObject
        val played = body.getAsJsonArray("played").single().asJsonObject
        assertEquals("start", played["kind"].asString)
        assertEquals("dz:1", played["ref"].asString)
        assertEquals("wave:w1", played["context"].asString)
        assertTrue(played.has("event_id"))
        assertEquals("dz:2", body.getAsJsonArray("queued").single().asString)
    }

    @Test
    fun theQueueAheadIsSettledOnlyAfterTheAnswerForThePlayingTrack() {
        val wave = session()
        assertFalse(wave.settled("a")) // nothing asked yet: the precache would load heads the wave replaces
        wave.askedFor = "a"
        assertFalse(wave.settled("a")) // asked, no answer
        wave.settledFor = wave.askedFor
        assertTrue(wave.settled("a"))
        assertFalse(wave.settled("b")) // the next track began: its own answer is awaited
        assertFalse(wave.settled(null))
    }

    @Test
    fun aHubThatDoesNotAnswerHoldsTheCacheBackNoLonger() {
        val wave = session()
        wave.failures = 1
        assertTrue(wave.settled("a"))
        wave.failures = 0
        assertFalse(wave.settled("a"))
    }
}
