package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Child
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlayEventsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- PlayTracker: start / skip / finish ------------------------------------------------

    private data class Emitted(val kind: String, val id: String, val positionMs: Long?)

    private val emitted = mutableListOf<Emitted>()
    private val tracker = PlayTracker { kind, track, positionMs, _ -> emitted += Emitted(kind, track.mediaId, positionMs) }

    private fun song(id: String, durationMs: Long = 200_000) = PlayTracker.Track(id, durationMs, "album")

    @Test
    fun aTrackPlayedToItsEndIsAFinishAtItsLength() {
        tracker.playing(song("a"), 0, 0)
        tracker.leave("a", 199_500, 199_500, ended = true)
        assertEquals(listOf(Emitted("start", "a", 0), Emitted("finish", "a", 200_000)), emitted)
    }

    @Test
    fun leavingEarlyIsASkipWithItsSecond() {
        tracker.playing(song("a"), 0, 0)
        tracker.leave("a", 12_000, 12_000, ended = false)
        assertEquals(Emitted("skip", "a", 12_000), emitted.last())
    }

    @Test
    fun leavingAfterNinetyPercentIsAFinish() {
        tracker.playing(song("a"), 0, 0)
        tracker.leave("a", 181_000, 181_000, ended = false)
        assertEquals("finish", emitted.last().kind)
    }

    @Test
    fun pauseAndSeekAreNoEventsAndThePositionFollowsThem() {
        tracker.playing(song("a"), 0, 0)
        tracker.paused(10_000, 10_000)
        tracker.playing(song("a"), 10_000, 60_000) // resumed after a minute of pause
        tracker.moved(40_000, 65_000) // seek forward
        // the player no longer knows where "a" was (the queue was replaced): played 5 s since the seek
        tracker.leave("a", null, 70_000, ended = false)
        assertEquals(listOf(Emitted("start", "a", 0), Emitted("skip", "a", 45_000)), emitted)
    }

    @Test
    fun anotherTrackPlayingClosesTheOpenOne() {
        tracker.playing(song("a"), 0, 0)
        tracker.playing(song("b"), 0, 3_000)
        assertEquals(
            listOf(Emitted("start", "a", 0), Emitted("skip", "a", 3_000), Emitted("start", "b", 0)),
            emitted,
        )
    }

    @Test
    fun aTrackThatNeverSoundedLeavesNothing() {
        tracker.leave("a", 0, 0, ended = false)
        tracker.paused(0, 0)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun theSameTrackAgainAfterItsEndStartsAgain() {
        tracker.playing(song("a"), 0, 0)
        tracker.playing(song("a"), 5_000, 5_000) // buffering → playing: still the same listen
        tracker.leave("a", null, 200_000, ended = true) // repeat one
        tracker.playing(song("a"), 0, 200_000)
        assertEquals(listOf("start", "finish", "start"), emitted.map { it.kind })
    }

    @Test
    fun withoutALengthOnlyTheEndIsAFinish() {
        tracker.playing(song("a", durationMs = 0), 0, 0)
        tracker.leave("a", 500_000, 500_000, ended = false)
        tracker.playing(song("b", durationMs = 0), 0, 0)
        tracker.leave("b", 500_000, 500_000, ended = true)
        assertEquals(listOf("start", "skip", "start", "finish"), emitted.map { it.kind })
        assertEquals(500_000L, emitted.last().positionMs)
    }

    @Test
    fun aLengthLearnedWhilePlayingCounts() {
        tracker.playing(song("a", durationMs = 0), 0, 0)
        tracker.playing(song("a", durationMs = 100_000), 1_000, 1_000)
        tracker.leave("a", 95_000, 95_000, ended = false)
        assertEquals("finish", emitted.last().kind)
    }

    @Test
    fun listeningTimeExcludesPausesAndSeeksAndCountsReplay() {
        val samples = mutableListOf<Pair<String, Long>>()
        val ids = mutableListOf<String>()
        val t = PlayTracker { kind, track, _, _ ->
            samples += kind to track.listenedMs
            ids += track.playbackId
        }
        t.playing(song("a"), 0, 0)
        t.paused(10_000, 10_000)
        t.playing(song("a"), 10_000, 60_000)
        t.moved(100_000, 65_000)
        t.moved(0, 70_000)
        t.checkpoint(75_000)
        t.leave("a", 5_000, 80_000, ended = false)
        assertEquals(listOf(0L, 25_000L, 30_000L), samples.map { it.second })
        assertEquals(1, ids.distinct().size)
        t.playing(song("a"), 0, 81_000)
        assertEquals(2, ids.distinct().size)
    }

    @Test
    fun resumedAttemptKeepsItsIdAndCumulativeTimeWithoutAnotherStart() {
        val emitted = mutableListOf<Pair<String, Long>>()
        val tracker = PlayTracker { kind, track, _, _ -> emitted += kind to track.listenedMs }
        val restored = song("a")
        tracker.restore(restored, "prior-attempt", 12_000)
        tracker.playing(restored, 12_000, 100_000)
        tracker.checkpoint(105_000)
        tracker.leave("a", 17_000, 106_000, ended = false)
        assertEquals("prior-attempt", restored.playbackId)
        assertEquals(listOf("progress" to 17_000L, "skip" to 18_000L), emitted)
    }

    // --- PlayEvent: the Hub's PlayEventIn --------------------------------------------------

    @Test
    fun aCatalogTrackIsARefAndASongANavidromeId() {
        val hub = PlayEvent.of("skip", "hub:dz:42", "app/1.5.0", 0, positionMs = 12_500, durationMs = 200_000, context = "search")
        assertEquals("dz:42", hub.ref)
        assertNull(hub.ndSongId)
        val json = JsonParser.parseString(HubClient.gson.toJson(hub)).asJsonObject
        assertEquals("1970-01-01T00:00:00.000Z", json["ts"].asString)
        assertEquals(12.5, json["position_s"].asDouble, 0.0)
        assertEquals(200.0, json["duration_s"].asDouble, 0.0)
        assertEquals("app/1.5.0", json["client"].asString)
        assertTrue(json["event_id"].asString.length in 1..64)

        val nd = PlayEvent.of("like", "abc123", "app/1.5.0", 1_761_134_400_000)
        assertEquals("abc123", nd.ndSongId)
        assertNull(nd.ref)
        assertEquals("2025-10-22T12:00:00.000Z", nd.ts)
        val ndJson = JsonParser.parseString(HubClient.gson.toJson(nd)).asJsonObject
        assertTrue(!ndJson.has("ref") && !ndJson.has("position_s"))
    }

    @Test
    fun thePageContextHoldsForItsOwnQueueOnly() {
        val item = HubClient.gson.fromJson("""{"ref": "dz:1", "context": "mix:7"}""", HubTracks.Item::class.java)
        assertEquals("mix:7", item.context)

        val queue = listOf(Child("nd-1"), Child("hub:dz:1"))
        HubPlays.expectQueue(queue, mapOf("hub:dz:1" to "mix:7"))
        HubPlays.onQueueStarted(queue)
        assertEquals("mix:7", HubPlays.contextOf("hub:dz:1"))
        assertEquals("other", HubPlays.contextOf("nd-1"))

        // a queue started in the app itself (an album page) has no page context
        HubPlays.onQueueStarted(listOf(Child("hub:dz:1")))
        assertEquals("other", HubPlays.contextOf("hub:dz:1"))
    }

    // --- PlayEventStore and PlaySender: the queue on the phone ---------------------------------

    private fun event(i: Int) = PlayEvent("e$i", PlayEvent.iso(i.toLong()), "start", ndSongId = "s$i", client = "app/test")

    @Test
    fun theQueueOutlivesTheProcessWithoutDroppingOldEvents() {
        val file = tmp.root.resolve("events.json")
        val store = PlayEventStore(file)
        (1..4).forEach { store.add(event(it)) }
        store.remove(listOf(event(3)))

        val again = PlayEventStore(file)
        assertEquals(listOf("e1", "e2", "e4"), again.head(10).map { it.eventId })
        assertEquals("s4", again.head(10).last().ndSongId)
    }

    @Test
    fun onlyOlderCheckpointsOfTheSameAttemptAreCompacted() {
        val store = PlayEventStore(tmp.root.resolve("compaction.json"))
        fun progress(id: String, attempt: String, ms: Long) = PlayEvent(id, PlayEvent.iso(0), "progress",
            ndSongId = "song", client = "app/test", playbackId = attempt, deviceId = "device", listenedMs = ms)
        store.add(event(1))
        store.add(progress("p1", "a", 10))
        store.add(progress("p2", "b", 20))
        store.add(progress("p3", "a", 30))
        assertEquals(listOf("e1", "p2", "p3"), PlayEventStore(tmp.root.resolve("compaction.json")).head(10).map { it.eventId })
    }

    @Test
    fun persistenceFailureRetriesInOrderWithoutDuplicatingStoredEvents() {
        val store = PlayEventStore(tmp.root.resolve("retry.json"))
        var failSnapshot = true
        val writer = DurableEventWriter { event ->
            store.add(event)
            if (failSnapshot) throw java.io.IOException("snapshot unavailable")
        }
        writer.enqueue(event(1))
        writer.enqueue(event(2))
        assertTrue(!writer.drain())
        assertEquals(listOf("e1"), store.head(10).map { it.eventId })
        failSnapshot = false
        assertTrue(writer.drain())
        assertEquals(listOf("e1", "e2"), PlayEventStore(tmp.root.resolve("retry.json")).head(10).map { it.eventId })
    }

    @Test
    fun aBrokenFileIsNotSilentlyOverwritten() {
        val file = tmp.root.resolve("events.json").apply { writeText("{not json") }
        val store = PlayEventStore(file)
        org.junit.Assert.assertThrows(Exception::class.java) { store.add(event(1)) }
        assertEquals("{not json", file.readText())
    }

    private fun storeWith(n: Int) = PlayEventStore(tmp.root.resolve("q.json")).apply { (1..n).forEach { add(event(it)) } }

    @Test
    fun everythingGoesInBatches() {
        val store = storeWith(12)
        val batches = mutableListOf<Int>()
        val sender = PlaySender(store, { batches += it.size; PlaySender.Outcome.SENT }, batch = 5)
        assertNull(sender.flush())
        assertEquals(listOf(5, 5, 2), batches)
        assertEquals(0, store.size())
    }

    @Test
    fun aFailedBatchStaysAndIsTriedLaterEachTime() {
        val store = storeWith(3)
        var outcome = PlaySender.Outcome.RETRY
        var sent = 0
        val sender = PlaySender(store, { sent++; outcome })
        assertEquals(30_000L, sender.flush())
        assertEquals(60_000L, sender.flush())
        assertEquals(120_000L, sender.flush())
        assertEquals(3, store.size())

        outcome = PlaySender.Outcome.SENT
        assertNull(sender.flush())
        assertEquals(0, store.size())
        assertEquals(4, sent)
        // a new failure starts from the first delay again
        store.add(event(9))
        outcome = PlaySender.Outcome.RETRY
        assertEquals(30_000L, sender.flush())
    }

    @Test
    fun theDelayStopsGrowingAtHalfAnHour() {
        assertEquals(PlaySender.MAX_RETRY_MS, PlaySender.retryDelay(50))
    }

    @Test
    fun aBatchTheHubRefusedIsKept() {
        val store = storeWith(2)
        assertEquals(30_000L, PlaySender(store, { PlaySender.Outcome.REFUSED }).flush())
        assertEquals(2, store.size())
    }

    @Test
    fun oversizedBatchesAreSplitAndOversizedSingleEventIsRetained() {
        val store = storeWith(5)
        val attempts = mutableListOf<Int>()
        val sender = PlaySender(store, { events ->
            attempts += events.size
            if (events.size > 2) PlaySender.Outcome.TOO_LARGE else PlaySender.Outcome.SENT
        })
        assertNull(sender.flush())
        assertEquals(listOf(5, 2, 2, 1), attempts)
        store.add(event(9))
        assertEquals(PlaySender.CAPABILITY_RETRY_MS, PlaySender(store, { PlaySender.Outcome.TOO_LARGE }).flush())
        assertEquals(1, store.size())
        assertTrue(store.head(1).single().rejected!!.contains("413"))
    }

    @Test
    fun quarantinedEventsSurviveRestartWithoutBlockingOthers() {
        val file = tmp.root.resolve("quarantine.json")
        val store = PlayEventStore(file)
        store.add(event(1))
        store.add(event(2))
        store.quarantine(mapOf("e1" to "invalid"))
        val again = PlayEventStore(file)
        assertEquals("invalid", again.head(1).single().rejected)
        assertEquals(PlaySender.CAPABILITY_RETRY_MS, PlaySender(again, { PlaySender.Outcome.SENT }).flush())
        assertEquals(listOf("e1"), again.head(10).map { it.eventId })
    }

    @Test
    fun aHubWithoutTheEndpointKeepsTheEventsAndIsAskedRarely() {
        val store = storeWith(2)
        assertEquals(PlaySender.NO_ENDPOINT_DELAY_MS, PlaySender(store, { PlaySender.Outcome.NO_ENDPOINT }).flush())
        assertEquals(2, store.size())
    }

    @Test
    fun whatWasSentBeforeAFailureIsNotSentAgain() {
        val store = storeWith(7)
        val seen = mutableListOf<String>()
        var calls = 0
        val sender = PlaySender(store, { batch ->
            calls++
            if (calls == 2) PlaySender.Outcome.RETRY else { seen += batch.map { it.eventId }; PlaySender.Outcome.SENT }
        }, batch = 5)
        assertEquals(30_000L, sender.flush())
        assertEquals(2, store.size())
        assertNull(sender.flush())
        assertEquals(7, seen.toSet().size)
        assertEquals(7, seen.size)
    }
}
