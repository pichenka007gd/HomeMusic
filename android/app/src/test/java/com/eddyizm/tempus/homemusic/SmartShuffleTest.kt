package com.eddyizm.tempus.homemusic

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartShuffleTest {
    private fun rec(ref: String, after: String, slot: Int, song: String? = null) = SmartRec().apply {
        this.ref = ref
        this.after = after
        this.slot = slot
        ndSongId = song
        owned = song != null
        title = "t-$ref"
    }

    private fun event(kind: String, id: String, context: String?) = PlayEvent.of(kind, id, "app/test", 0, context = context)

    private fun ids(slots: List<Slot>) = slots.map { it.mediaId }

    // --- the button ------------------------------------------------------------------------------

    @Test
    fun theButtonGoesOffShuffleSmartOff() {
        assertEquals(ShuffleMode.SHUFFLE, ShuffleMode.OFF.next(smartOffered = true))
        assertEquals(ShuffleMode.SMART, ShuffleMode.SHUFFLE.next(smartOffered = true))
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.next(smartOffered = true))
    }

    @Test
    fun withoutASmartOptionTheButtonKeepsToTwoModes() {
        // an album, the setting off, no Hub: shuffle and off only
        assertEquals(ShuffleMode.SHUFFLE, ShuffleMode.OFF.next(smartOffered = false))
        assertEquals(ShuffleMode.OFF, ShuffleMode.SHUFFLE.next(smartOffered = false))
        assertEquals(ShuffleMode.OFF, ShuffleMode.SMART.next(smartOffered = false))
    }

    @Test
    fun theModeIsReadFromThePlayerAndTheSession() {
        assertEquals(ShuffleMode.OFF, ShuffleMode.of(playerShuffle = false, smartActive = false))
        assertEquals(ShuffleMode.SHUFFLE, ShuffleMode.of(playerShuffle = true, smartActive = false))
        // in the smart shuffle Media3's own shuffle is off, and a stray one does not hide it
        assertEquals(ShuffleMode.SMART, ShuffleMode.of(playerShuffle = false, smartActive = true))
        assertEquals(ShuffleMode.SMART, ShuffleMode.of(playerShuffle = true, smartActive = true))
    }

    // --- where the smart shuffle is offered ----------------------------------------------------

    @Test
    fun itIsOfferedForPlaylistsAndLikedOnly() {
        assertTrue(SmartContexts.offered("liked"))
        assertTrue(SmartContexts.offered("playlist:abc123"))
        assertFalse(SmartContexts.offered("playlist"))
        assertFalse(SmartContexts.offered("playlist:"))
        assertFalse(SmartContexts.offered("album"))
        assertFalse(SmartContexts.offered("mix:7"))
        assertFalse(SmartContexts.offered("wave:x"))
        assertFalse(SmartContexts.offered("artist"))
        assertFalse(SmartContexts.offered("search"))
        assertFalse(SmartContexts.offered("playlist:a b"))
        assertFalse(SmartContexts.offered(null))
    }

    @Test
    fun aPlaylistIdOfOneToSixtyFourCharactersIsOffered() {
        assertTrue(SmartContexts.offered("playlist:" + "a".repeat(1)))
        assertTrue(SmartContexts.offered("playlist:" + "a".repeat(64)))
        assertFalse(SmartContexts.offered("playlist:" + "a".repeat(65)))
    }

    @Test
    fun theListOfAQueueIsTheContextMostOfItsEntriesShare() {
        assertEquals("playlist:p1", SmartContexts.dominant(listOf("playlist:p1", "playlist:p1", "queue")))
        assertEquals("liked", SmartContexts.dominant(listOf("liked", "liked")))
        // a queue of something else with one playlist song in it is not the playlist
        assertNull(SmartContexts.dominant(listOf("album", "album", "playlist:p1")))
        assertNull(SmartContexts.dominant(listOf("playlist:p1", "queue")))
        assertNull(SmartContexts.dominant(emptyList()))
        assertNull(SmartContexts.dominant(listOf(null, null)))
    }

    @Test
    fun aCatalogRefStreamsFromTheHubAndASongIdIsALibrarySong() {
        assertEquals("hub:dz:5", SmartContexts.mediaId("dz:5"))
        assertEquals("song1", SmartContexts.mediaId("song1"))
        assertEquals("dz:5", SmartContexts.hubId("hub:dz:5"))
        assertEquals("song1", SmartContexts.hubId("song1"))
    }

    // --- a plan for the queue ---------------------------------------------------------------------

    @Test
    fun theRecommendationsStandAfterTheOwnSongsTheyName() {
        val recs = listOf(rec("dz:1", "c", 0), rec("dz:2", "f", 1))

        val plan = SmartPlan.build(listOf("a", "b", "c", "d", "e", "f"), listOf("c", "a", "b", "f", "d", "e"), recs)

        assertEquals(listOf("c", "hub:dz:1", "a", "b", "f", "hub:dz:2", "d", "e"), ids(plan))
        assertEquals(listOf(false, true, false, false, false, true, false, false), plan.map { it.rec != null })
    }

    @Test
    fun aLibrarySongRecommendationIsQueuedByItsSongId() {
        val plan = SmartPlan.build(listOf("a"), listOf("a"), listOf(rec("dz:9", "a", 0, song = "nd9")))

        assertEquals(listOf("a", "nd9"), ids(plan))
    }

    @Test
    fun theSongPlayingNowIsNotQueuedAgainButItsRecommendationComesFirst() {
        // started from the player: "b" plays, the Hub put a recommendation after it (a short list)
        val plan = SmartPlan.build(listOf("c", "d"), listOf("b", "d", "c"), listOf(rec("dz:1", "b", 0)), current = "b")

        assertEquals(listOf("hub:dz:1", "d", "c"), ids(plan))
    }

    @Test
    fun aSongThePlayerDoesNotHaveAheadIsNotBroughtBack() {
        // "a" was played, "z" was taken out of the queue meanwhile
        val plan = SmartPlan.build(listOf("b", "c"), listOf("a", "z", "c", "b"), listOf(rec("dz:1", "a", 0), rec("dz:2", "c", 1)))

        assertEquals(listOf("c", "hub:dz:2", "b"), ids(plan))
    }

    @Test
    fun aSongTheUserQueuedMeanwhileGoesLastInTheQueueOrder() {
        val plan = SmartPlan.build(listOf("a", "x", "b", "y"), listOf("b", "a"), emptyList())

        assertEquals(listOf("b", "a", "x", "y"), ids(plan))
    }

    @Test
    fun aCatalogOwnSongIsNamedByItsRefAndQueuedByItsHubId() {
        val plan = SmartPlan.build(listOf("hub:dz:7", "s1"), listOf("s1", "dz:7"), listOf(rec("dz:1", "dz:7", 0)))

        assertEquals(listOf("s1", "hub:dz:7", "hub:dz:1"), ids(plan))
    }

    @Test
    fun aRecommendationWithoutAnIdIsNotQueued() {
        val noId = rec("", "a", 0)
        val plan = SmartPlan.build(listOf("a", "b"), listOf("a", "b"), listOf(noId, rec("dz:2", "b", 1)))

        assertEquals(listOf("a", "b", "hub:dz:2"), ids(plan))
        assertTrue(SmartPlan.insertions(queue("a", "b"), current = 0, recs = listOf(noId)).isEmpty())
        assertTrue(session().register(listOf(noId)).isEmpty())
    }

    @Test
    fun aRecommendationThatNamesNoSongOfTheQueueIsNotInserted() {
        assertTrue(SmartPlan.insertions(queue("a", "b"), current = 0, recs = listOf(rec("dz:1", "zzz", 0))).isEmpty())
        assertTrue(SmartPlan.insertions(queue("a", "b"), current = 0, recs = listOf(SmartRec().apply { ref = "dz:1" })).isEmpty())
    }

    @Test
    fun aRecommendationIsPlacedOnce() {
        val plan = SmartPlan.build(listOf("a", "b"), listOf("a", "b"), listOf(rec("dz:1", "a", 0), rec("dz:1", "b", 1)))

        assertEquals(listOf("a", "hub:dz:1", "b"), ids(plan))
    }

    @Test
    fun theQueueOfAPageAnswerLeavesOutWhatIsNotAheadOfThePlayer() {
        // a random song plays; the page's other songs are ahead: the Hub's order places them, a cut one is not brought back
        val ahead = listOf("b", "c")
        val plan = SmartPlan.build(ahead, listOf("a", "c", "z", "b"), listOf(rec("dz:1", "a", 0), rec("dz:2", "c", 1)), current = "a")

        assertEquals(listOf("hub:dz:1", "c", "hub:dz:2", "b"), ids(plan))
    }

    // --- recommendations that come later --------------------------------------------------------

    private fun queue(vararg spec: String) = spec.map { Entry(it.removePrefix("*"), it.startsWith("*")) }

    @Test
    fun newRecommendationsGoAfterTheirOwnSongsAheadOfThePlayer() {
        val q = queue("a", "b", "c", "*hub:dz:1", "d", "e", "f")

        val at = SmartPlan.insertions(q, current = 1, recs = listOf(rec("dz:2", "f", 1), rec("dz:3", "c", 0)))

        // sorted by place; each index counts the inserts made before it
        assertEquals(listOf(3 to "dz:3", 8 to "dz:2"), at.map { it.first to it.second.ref })
    }

    @Test
    fun aRecommendationAfterASongAlreadyPlayedIsSkipped() {
        val q = queue("a", "b", "c", "d")

        assertTrue(SmartPlan.insertions(q, current = 2, recs = listOf(rec("dz:1", "a", 0))).isEmpty())
        assertEquals(listOf(3), SmartPlan.insertions(q, current = 2, recs = listOf(rec("dz:2", "c", 1))).map { it.first })
    }

    @Test
    fun aRecommendationAlreadyInTheQueueIsNotAddedTwice() {
        val q = queue("a", "b", "*hub:dz:1")

        assertTrue(SmartPlan.insertions(q, current = 0, recs = listOf(rec("dz:1", "b", 0))).isEmpty())
    }

    @Test
    fun aRecommendationOfTheHubIsFoundByItsOwnerEvenWhenTheOwnerIsACatalogTrack() {
        val q = queue("s1", "hub:dz:7", "s2")

        assertEquals(listOf(2), SmartPlan.insertions(q, current = 0, recs = listOf(rec("dz:1", "dz:7", 0))).map { it.first })
    }

    @Test
    fun aRecommendationIsNeverPutAfterAnotherRecommendation() {
        // a rec with the id of the own song's name is not an own song
        val q = queue("a", "*hub:dz:1", "b")

        assertTrue(SmartPlan.insertions(q, current = 0, recs = listOf(rec("dz:2", "dz:1", 0))).isEmpty())
    }

    // --- where the player is -------------------------------------------------------------------------

    private val listed = setOf("a", "b", "hub:dz:7")

    @Test
    fun theAnchorIsTheListSongThePlayerIsAt() {
        assertEquals("b", SmartPlan.anchor(queue("a", "b", "c"), current = 1) { it in listed })
    }

    @Test
    fun aRecommendationThatPlaysHasTheSongBeforeItAsAnchor() {
        assertEquals("a", SmartPlan.anchor(queue("a", "*hub:dz:1", "b"), current = 1) { it in listed })
    }

    @Test
    fun aSongTheUserQueuedOrAcceptedIsNotTheHubsAnchor() {
        // "x" was queued by the user, "nd1" was a recommendation taken in with «+»: the Hub knows neither
        assertEquals("b", SmartPlan.anchor(queue("a", "b", "x", "nd1"), current = 3) { it in listed })
        assertEquals("hub:dz:7", SmartPlan.anchor(queue("hub:dz:7", "nd1"), current = 1) { it in listed })
    }

    @Test
    fun withoutAListSongBehindThePlayerThereIsNoAnchor() {
        assertNull(SmartPlan.anchor(queue("x", "*hub:dz:1"), current = 1) { it in listed })
        assertNull(SmartPlan.anchor(emptyList(), current = 0) { true })
        assertNull(SmartPlan.anchor(queue("a"), current = -1) { true })
    }

    @Test
    fun aCurrentIndexPastTheEndIsTheLastEntry() {
        assertEquals("b", SmartPlan.anchor(queue("a", "b"), current = 5) { it in listed })
    }

    // --- leaving the smart shuffle ----------------------------------------------------------------

    @Test
    fun theOriginalOrderComesBackFromTheCurrentSong() {
        val original = listOf("a", "b", "c", "d", "e")
        val ahead = queue("e", "*hub:dz:1", "b", "d", "*hub:dz:2")

        // "c" plays: what follows it in the list, then the songs before it
        assertEquals(listOf("d", "e", "b"), SmartPlan.restore(original, ahead, anchor = "c"))
    }

    @Test
    fun aRecommendationThatPlaysLeavesTheOrderFromTheSongBeforeIt() {
        val original = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "d", "a"), SmartPlan.restore(original, queue("d", "c", "a"), anchor = "b"))
    }

    @Test
    fun withoutAnAnchorTheListStartsFromItsBeginning() {
        assertEquals(listOf("a", "c"), SmartPlan.restore(listOf("a", "b", "c"), queue("c", "a"), anchor = null))
    }

    @Test
    fun whatTheUserQueuedStaysAfterTheList() {
        val original = listOf("a", "b", "c")

        assertEquals(listOf("b", "c", "x"), SmartPlan.restore(original, queue("x", "b", "c"), anchor = "a"))
    }

    @Test
    fun aCatalogOwnSongIsRestoredByItsQueueId() {
        assertEquals(listOf("s1", "hub:dz:7"), SmartPlan.restore(listOf("s1", "dz:7", "s2"), queue("s1", "hub:dz:7"), anchor = "s2"))
    }

    // --- the session --------------------------------------------------------------------------------

    private fun session() = SmartSession("s1", "playlist:p1", "Мой", listOf("a", "b", "dz:7"))

    @Test
    fun aSessionKnowsItsRecommendationsByTheirQueueIdsBothWays() {
        val s = session()

        val ids = s.register(listOf(rec("dz:1", "a", 0, song = "nd1"), rec("dz:2", "b", 1)))

        assertEquals(listOf("nd1", "hub:dz:2"), ids)
        // the library song may stream from the Hub when Navidrome does not answer
        assertTrue(s.isRec("nd1") && s.isRec("hub:dz:1") && s.isRec("hub:dz:2"))
        assertFalse(s.isRec("a"))
        assertEquals("dz:1", s.refOf("nd1"))
        assertTrue(s.isOwn("a") && s.isOwn("hub:dz:7") && !s.isOwn("nd1"))
    }

    @Test
    fun aRecommendationThatWasAddedIsAnOwnSong() {
        val s = session()
        s.register(listOf(rec("dz:1", "a", 0, song = "nd1")))

        s.accept("nd1")

        assertFalse(s.isRec("nd1"))
        assertFalse(s.isRec("hub:dz:1"))
        assertTrue(s.isOwn("nd1"))
    }

    @Test
    fun aRecommendationTakenInStaysOutOfTheHubsList() {
        val s = session()
        s.register(listOf(rec("dz:1", "a", 0, song = "nd1")))

        s.accept("nd1")

        // it is an own song for the queue, but the Hub never got it in the list
        assertTrue(s.isOwn("nd1"))
        assertFalse(s.isListed("nd1"))
        assertTrue(s.isListed("a") && s.isListed("hub:dz:7"))
        assertFalse(s.isListed(null))
    }

    @Test
    fun acceptingASongThatIsNotARecommendationChangesNothing() {
        val s = session()

        s.accept("a")

        assertTrue(s.isListed("a") && s.isOwn("a") && !s.isRec("a"))
    }

    @Test
    fun anOldHubGetsTheTrackEventsOfTheSessionsAccountOnly() {
        val typed = PlayEvent.of("like", "hub:dz:1", "app/test", 0, context = "smart:s1").let {
            // an album ♥ is a typed event: an old Hub would take it for a track's
            PlayEvent(it.eventId, it.ts, it.kind, it.ref, it.ndSongId, it.positionS, it.durationS, it.context, it.client, targetType = "album")
        }
        val plain = event("skip", "hub:dz:1", "smart:s1")
        val other = PlayEvent.of("skip", "hub:dz:1", "app/test", 0, context = "smart:s1", account = "someone-else")

        val request = SmartNextIn(listOf(typed, plain, other), "a")

        // an old Hub gets track events only, and none of another account's
        assertEquals(listOf(plain.eventId), request.compatible(typedFavorites = false).played.map { it.eventId })
        assertEquals(listOf(typed.eventId, plain.eventId), request.compatible(typedFavorites = true, account = "me").played.map { it.eventId })
        assertEquals("a", request.compatible(typedFavorites = true).at)
    }

    @Test
    fun noMoreThanFiveHundredEventsGoInACall() {
        val many = List(600) { event("skip", "hub:dz:$it", "smart:s1") }

        assertEquals(500, SmartNextIn(many, null).compatible(typedFavorites = true).played.size)
    }

    @Test
    fun aStartOfAnOwnSongOrARecommendationAsksAndOnlyRecommendationEventsAreSent() {
        val s = session()
        s.register(listOf(rec("dz:1", "a", 0)))

        assertTrue(s.onEvent(event("start", "a", "playlist:p1")))
        assertTrue(s.onEvent(event("start", "hub:dz:1", "smart:s1")))
        assertTrue(s.onEvent(event("skip", "hub:dz:1", "smart:s1")))
        assertFalse(s.onEvent(event("progress", "hub:dz:1", "smart:s1")))
        assertFalse(s.onEvent(event("finish", "a", "playlist:p1")))  // an own song's finish: nothing to learn
        assertFalse(s.onEvent(event("start", "z", "album")))        // another list
        assertFalse(s.onEvent(event("start", "z", "smart:other")))

        // own events are not sent: the Hub has them from /api/plays/events
        assertEquals(listOf("start", "skip", "progress"), s.snapshot("a").played.map { it.kind })
    }

    @Test
    fun theHubGetsWhereThePlayerIsAndTheEventsStayUntilItAnswers() {
        val s = session()
        s.onEvent(event("start", "hub:dz:1", "smart:s1"))
        val request = s.snapshot("b")
        s.onEvent(event("finish", "hub:dz:1", "smart:s1"))

        s.sent(request)

        assertEquals("b", request.at)
        assertEquals(listOf("finish"), s.snapshot(null).played.map { it.kind })
    }

    @Test
    fun theBodyOfStartLeavesOutWhatTheHubWouldRefuse() {
        val body = SmartSession.startBody("liked", listOf("a", "b"), "a", " ", "Bad Origin", "app/1.0")

        assertEquals(mapOf("context" to "liked", "songs" to listOf("a", "b"), "current" to "a", "client" to "app/1.0"), body)
        assertEquals("playlist_page", SmartSession.startBody("playlist:p", listOf("a"), null, "Мой", "playlist_page", null)["origin"])
        assertEquals(SmartSession.MAX_SONGS, SmartSession.startBody("liked", List(6000) { "s$it" }, null, null, null, null).let { (it["songs"] as List<*>).size })
    }

    @Test
    fun theHubsAnswerIsRead() {
        val json = """{"session_id":"s9","context":"liked","own":["a","dz:7"],"pending":false,
            "recs":[{"ref":"dz:1","title":"T","artist":"A","owned":true,"nd_song_id":"nd1","after":"a","slot":0,"generator":"ytm_radio"}]}"""

        val out = Gson().fromJson(json, SmartOut::class.java)

        assertEquals("s9", out.sessionId)
        assertEquals(listOf("a", "dz:7"), out.own)
        val r = out.recs!!.single()
        assertEquals("nd1", r.queueId)
        assertEquals("a", r.after)
        assertEquals(0, r.slot)
        assertEquals("added", Gson().fromJson("""{"ok":true,"status":"added","playlist_id":"p"}""", SmartAddOut::class.java).status)
    }
}
