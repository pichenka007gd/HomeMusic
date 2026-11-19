package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.FavoriteRegistry
import com.eddyizm.tempus.util.FavoriteRegistry.Kind
import com.eddyizm.tempus.subsonic.models.Child
import com.google.gson.Gson
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TypedFavoritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val events = mutableListOf<PlayEvent>()

    @Before fun init() {
        FavoriteRegistry.onChange = null
        FavoriteRegistry.onSongDecision = null
        FavoriteRegistry.onDecision = null
        FavoriteRegistry.clear()
        FavoriteRegistry.onDecision = FavoriteRegistry.DecisionListener { kind, id, starred, eventId, reverts, chain ->
            val type = when (kind) { Kind.SONG -> "track"; Kind.ALBUM -> "album"; Kind.ARTIST -> "artist" }
            events += PlayEvent.favorite(type, id, starred, eventId, reverts, "app/test", 0, type, compensationChain = chain)
        }
    }

    @After fun clear() {
        FavoriteRegistry.onDecision = null
        FavoriteRegistry.clear()
        HubPlays.onQueueStarted(null)
    }

    @Test fun allKindsHaveTheirOwnIdsAndRetriesAcceptWithoutAnotherLike() {
        Kind.values().forEach { kind ->
            val first = FavoriteRegistry.set(kind, "1", true)
            val retry = FavoriteRegistry.set(kind, "1", true)
            assertEquals(first.eventId, retry.eventId)
            FavoriteRegistry.accept(retry)
            FavoriteRegistry.strike(first)
        }
        assertEquals(listOf("track", "album", "artist"), events.map { it.targetType })
        assertEquals("1", events[0].ndSongId)
        assertEquals("1", events[1].ndAlbumId)
        assertEquals("1", events[2].ndArtistId)
        assertNull(events[1].ndSongId)
        assertNull(events[2].ndAlbumId)
    }

    @Test fun refusalCompensatesOnlyItsDecisionOnceAndNotANewerLike() {
        val first = FavoriteRegistry.set(Kind.ARTIST, "a", true)
        FavoriteRegistry.set(Kind.ARTIST, "a", false)
        val current = FavoriteRegistry.set(Kind.ARTIST, "a", true)
        FavoriteRegistry.strike(first)
        FavoriteRegistry.withdraw(first)
        assertEquals(4, events.size)
        assertEquals(first.eventId, events.last().revertsEventId)
        assertNotEquals(current.eventId, events.last().revertsEventId)
        assertTrue(FavoriteRegistry.resolve(Kind.ARTIST, "a", false))
    }

    @Test fun withdrawalOfARetryLeavesTheDecisionButFinalRefusalCompensatesIt() {
        val first = FavoriteRegistry.set(Kind.ALBUM, "a", true)
        val retry = FavoriteRegistry.set(Kind.ALBUM, "a", true)
        FavoriteRegistry.withdraw(retry)
        assertEquals(1, events.size)
        FavoriteRegistry.strike(first)
        assertEquals(2, events.size)
        assertEquals(first.eventId, events.last().revertsEventId)
    }

    @Test fun lateAcceptanceRetractsTheCompensationWithoutANewIndependentLike() {
        val first = FavoriteRegistry.set(Kind.ALBUM, "a", true)
        val retry = FavoriteRegistry.set(Kind.ALBUM, "a", true)
        FavoriteRegistry.strike(retry)
        val compensation = events.last()
        FavoriteRegistry.set(Kind.ALBUM, "a", false)
        FavoriteRegistry.accept(first)
        assertEquals(compensation.eventId, events.last().revertsEventId)
        assertTrue(events.last().compensationChain)
        assertEquals("like", events.last().kind)
        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "a", true))
        assertEquals(2, events.count { it.revertsEventId == null })
    }

    @Test fun supersedeCompensatesPendingOppositesButNeverAcceptedOnes() {
        val accepted = FavoriteRegistry.set(Kind.SONG, "1", true)
        FavoriteRegistry.accept(accepted)
        val pending = FavoriteRegistry.set(Kind.SONG, "1", false)
        FavoriteRegistry.supersede(Kind.SONG, "1", true)
        assertEquals(pending.eventId, events.last().revertsEventId)
        assertEquals(3, events.size)
        assertTrue(FavoriteRegistry.resolve(Kind.SONG, "1", false))
    }

    @Test fun staleCallbacksAfterSignOutDoNotWriteIntoTheNewSession() {
        val stale = FavoriteRegistry.set(Kind.ALBUM, "a", true)
        FavoriteRegistry.clear()
        FavoriteRegistry.set(Kind.ALBUM, "a", true)
        FavoriteRegistry.strike(stale)
        FavoriteRegistry.accept(stale)
        assertEquals(2, events.size)
    }

    @Test fun catalogAliasIsTransferredWithoutStartingANewLikeSeries() {
        val first = FavoriteRegistry.set(Kind.SONG, "hub:dz:1", true)
        FavoriteRegistry.accept(first)
        FavoriteRegistry.alias(Kind.SONG, "hub:dz:1", "nd-1")
        val retry = FavoriteRegistry.set(Kind.SONG, "nd-1", true)
        FavoriteRegistry.accept(retry)
        assertEquals(first.eventId, retry.eventId)
        assertEquals(1, events.size)
        assertTrue(FavoriteRegistry.resolve(Kind.SONG, "nd-1", false))
        FavoriteRegistry.set(Kind.SONG, "nd-1", false)
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:1", true))
    }

    @Test fun persistedRetryRestoresItsIdSilentlyAndCannotReplaceANewerDecision() {
        val original = FavoriteRegistry.set(Kind.ARTIST, "a", true)
        val id = original.eventId
        FavoriteRegistry.clear()
        assertTrue(FavoriteRegistry.restore(Kind.ARTIST, "a", true, id))
        val retry = FavoriteRegistry.set(Kind.ARTIST, "a", true)
        assertEquals(id, retry.eventId)
        assertEquals(1, events.size)
        FavoriteRegistry.strike(retry)
        assertEquals(id, events.last().revertsEventId)
        FavoriteRegistry.set(Kind.ARTIST, "a", false)
        assertFalse(FavoriteRegistry.restore(Kind.ARTIST, "a", true, id))
        assertFalse(FavoriteRegistry.resolve(Kind.ARTIST, "a", true))
    }

    @Test fun ownTypedSnapshotWinsOverImportAndNdAndCannotCrossSessions() {
        val snapshot = Gson().fromJson("""{
          "song_ids":["same"],"album_ids":["same"],"artist_ids":["same"],"refs":["dz:1"],
          "subjects":[
            {"target_type":"album","liked":true,"nd_id":"same","provenance":"import"},
            {"target_type":"album","liked":false,"aliases":["nd:same","ref:dz:1"],"provenance":"own"},
            {"target_type":"track","liked":false,"ref":"dz:1","provenance":"own"}
          ]} """, HubFavoriteSnapshot::class.java)
        val generation = FavoriteRegistry.generation()
        FavoriteRegistry.snapshot(snapshot.states(), generation)
        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "same", true))
        assertTrue(FavoriteRegistry.resolve(Kind.SONG, "same", false))
        assertTrue(FavoriteRegistry.resolve(Kind.ARTIST, "same", false))
        assertFalse(FavoriteRegistry.resolve(Kind.ALBUM, "hub:dz:1", true))
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:1", true))
        FavoriteRegistry.set(Kind.ALBUM, "same", true)
        FavoriteRegistry.snapshot(snapshot.states(), generation)
        assertTrue(FavoriteRegistry.resolve(Kind.ALBUM, "same", false))
        FavoriteRegistry.clear()
        FavoriteRegistry.snapshot(snapshot.states(), generation)
        assertFalse(FavoriteRegistry.resolve(Kind.ARTIST, "same", false))
        assertTrue(events.all { it.targetType == "album" })
    }

    @Test fun pendingCatalogUnlikeAndFailureCompensateTheirOwnCommandIds() {
        val like = CatalogFavoriteDecision("hub:dz:1", false, true)
        like.accepted()
        val unlike = CatalogFavoriteDecision("hub:dz:1", true, false)
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:1", true))
        assertTrue(unlike.failed())
        assertEquals(unlike.eventId, events.last().revertsEventId)
        val later = CatalogFavoriteDecision("hub:dz:1", true, false)
        later.accepted()
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:1", true))
        val failure = CatalogFavoriteDecision("hub:dz:2", false, true)
        val newer = CatalogFavoriteDecision("hub:dz:2", true, false)
        newer.accepted()
        assertFalse(failure.failed())
        assertEquals(failure.eventId, events.last().revertsEventId)
    }

    @Test fun catalogFailureNotifiesObserversAndRestoresThePreviousHeart() {
        var updates = 0
        val listener = Runnable { updates++ }
        FavoriteRegistry.addChangeListener(listener)
        try {
            val decision = CatalogFavoriteDecision("hub:dz:9", false, true)
            assertTrue(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:9", false))
            assertFalse(decision.failed())
            assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:9", false))
            assertEquals(decision.eventId, events.last().revertsEventId)
            assertEquals(2, updates)
        } finally {
            FavoriteRegistry.removeChangeListener(listener)
        }
    }

    @Test fun catalogCommandsUseDownloadForLikeAndStarForUnlikeWithTheDecisionId() {
        val like = catalogFavoriteRequest("dz:9", true, "like-id", "time", "app/test", "search")
        assertEquals("/api/downloads", like.first)
        assertEquals(listOf("dz:9"), like.second["track_refs"])
        assertEquals(true, like.second["star"])
        assertEquals("like-id", like.second["favorite_event_id"])
        val unlike = catalogFavoriteRequest("dz:9", false, "unlike-id", "time", "app/test", "search")
        assertEquals("/api/star", unlike.first)
        assertEquals(listOf("dz:9"), unlike.second["refs"])
        assertEquals(true, unlike.second["unstar"])
        assertEquals("unlike-id", unlike.second["event_id"])
    }

    @Test fun twoFailedCatalogTapsReturnToTheirOriginalBaseline() {
        val like = CatalogFavoriteDecision("hub:dz:10", false, true)
        val unlike = CatalogFavoriteDecision("hub:dz:10", true, false)
        assertFalse(like.failed())
        assertFalse(unlike.failed())
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:10", true))
        assertEquals(setOf(like.eventId, unlike.eventId), events.mapNotNull { it.revertsEventId }.toSet())
    }

    @Test fun restoredCatalogDecisionsRecoverLatestUiWithoutNewTelemetry() {
        CatalogFavoriteDecision("hub:dz:11", false, true, "like", "time", restore = true)
        CatalogFavoriteDecision("hub:dz:11", true, false, "unlike", "time", restore = true)
        assertFalse(FavoriteRegistry.resolve(Kind.SONG, "hub:dz:11", true))
        assertTrue(events.isEmpty())
    }

    @Test fun catalogTransportRetryKeepsTheDecisionAndDoesNotRetryRefusals() {
        val request = catalogFavoriteRequest("dz:9", true, "same-id", "time", "app/test", "search")
        val sent = mutableListOf<Pair<String, Map<String, Any?>>>()
        retryCatalogTransport {
            sent += request
            if (sent.size == 1) throw java.io.IOException("connection reset")
        }
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        var attempts = 0
        try {
            retryCatalogTransport { attempts++; throw HubException("refused", 503) }
            fail("expected refusal")
        } catch (_: HubException) {
            assertEquals(1, attempts)
        }
    }

    @Test fun persistedLegacyAndTypedEventsDrainByCapabilitiesAndAckOnlySentIds() {
        val file = tmp.root.resolve("outbox.json")
        file.writeText("""[{"event_id":"legacy","ts":"2026-10-26T00:00:00Z","kind":"start","nd_song_id":"1","client":"app/old"}]""")
        var store = PlayEventStore(file)
        val original = PlayEvent.favorite("album", "a", true, "album", null, "app/test", 0, "album", mapOf("name" to "Album"))
        store.add(original)
        store.add(PlayEvent.favorite("track", "1", false, "undo", "old", "app/test", 0, "queue"))
        store.add(PlayEvent.favorite("track", "1", true, "chain", "undo", "app/test", 0, "queue", compensationChain = true))
        store.add(PlayEvent.of("finish", "1", "app/test", 1))
        store = PlayEventStore(file)
        var typed = false
        var chains = false
        val batches = mutableListOf<List<String>>()
        val sender = PlaySender(store, { batches += it.map { e -> e.eventId }; PlaySender.Outcome.SENT },
            batch = 1, typedFavorites = { typed }, compensationChains = { chains })
        assertEquals(PlaySender.CAPABILITY_RETRY_MS, sender.flush())
        assertEquals(2, batches.size)
        assertEquals(3, store.size())
        assertEquals("Album", store.head(1).single().targetData?.get("name"))
        typed = true
        assertEquals(PlaySender.CAPABILITY_RETRY_MS, sender.flush())
        assertEquals(listOf("chain"), store.head(5).map { it.eventId })
        chains = true
        assertNull(sender.flush())
        assertEquals(0, store.size())
        assertEquals(listOf("legacy", "album", "undo", "chain").toSet(), batches.flatten().filter { it.length < 10 }.toSet())
    }

    @Test fun waveHeldEventsDoNotBlockTracksOrGetAcknowledgedByFilteredRequest() {
        val wave = WaveSession("s", null, null, null)
        repeat(501) { i -> wave.onEvent(PlayEvent.favorite("album", "a", true, "a$i", null, "app/test", 0, wave.context)) }
        val playback = PlayEvent.of("start", "1", "app/test", 0, context = wave.context)
        wave.onEvent(playback)
        val filtered = wave.snapshot(emptyList()).compatible(false, false)
        assertEquals(listOf(playback.eventId), filtered.played.map { it.eventId })
        // A deserialized acknowledgement must still match by id, not object identity.
        val copy = Gson().fromJson(Gson().toJson(filtered), WaveNextIn::class.java)
        wave.sent(copy)
        assertEquals(500, wave.request(emptyList()).played.size)
        assertTrue(wave.request(emptyList()).played.none { it.eventId == playback.eventId })
    }

    @Test fun chainMarkerIsKeptOnDiskButNeverSentAsAnApiField() {
        val event = PlayEvent.favorite("track", "1", true, "chain", "undo", "app/test", 0, "queue", compensationChain = true)
        assertFalse(event.supported(true, false))
        val payload = HubClient.eventPayload(listOf(event)).single()
        assertFalse(payload.has("_compensation_chain"))
        assertEquals("undo", payload["reverts_event_id"].asString)
    }

    @Test fun nativeSourcesAndContinuousKeepBridgeContextsScoped() {
        assertEquals("liked", NativeContexts.songList(Constants.MEDIA_STARRED))
        assertEquals("album", NativeContexts.songList(Constants.MEDIA_FROM_ALBUM))
        assertEquals("artist", NativeContexts.songList(Constants.MEDIA_BY_ARTIST))
        assertEquals("mix:7", NativeContexts.queue("queue", "mix:7"))
        assertEquals("wave:s", NativeContexts.queue("queue", "wave:s"))
        assertEquals("queue", NativeContexts.queue("queue", "liked"))
        assertEquals("album", NativeContexts.queue("album", "mix:7"))
        val queue = listOf(Child("1"))
        for (context in listOf("album", "artist", "playlist", "liked", "queue", "mix:7", "wave:s")) {
            HubPlays.expectNativeQueue(queue, context)
            HubPlays.onQueueStarted(queue)
            assertEquals(context, HubPlays.contextOf("1"))
            HubPlays.continuous(listOf(Child("2")))
            assertEquals("continuous", HubPlays.contextOf("2"))
            assertEquals(context, HubPlays.contextOf("1"))
        }
        HubPlays.onQueueStarted(listOf(Child("1")))
        assertEquals("other", HubPlays.contextOf("1"))
    }
}
