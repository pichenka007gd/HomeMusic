package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** INSIGHTS.md 6.3 / 6.5 / 10: where a wave or a list was launched from, as the Hub is told. */
class LaunchOriginTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // --- the start of a wave ---------------------------------------------------------------------

    private fun body(origin: String?, seed: Map<String, Any?>? = null, restartOf: String? = null) =
        WaveSession.startBody(seed, null, origin, restartOf, "app/1.14.0")

    @Test
    fun everyPlaceOfTheAppStartsAWaveWithItsOrigin() {
        val places = listOf(
            WaveOrigin.HOME_CARD, HubWave.menuOrigin(artist = false, fromPlayer = false),
            HubWave.menuOrigin(artist = true, fromPlayer = false), WaveOrigin.RADIO, WaveOrigin.ARTIST_PAGE,
        )
        assertEquals(listOf("home_card", "track_menu", "artist_menu", "radio", "artist_page"), places.map { body(it)["origin"] })
        assertEquals("home_card", body("home_card")["origin"])
        assertEquals("app/1.14.0", body("radio")["client"])
    }

    @Test
    fun theMenuOfThePlayerIsThePlayerWhicheverRowWasTapped() {
        assertEquals("player", HubWave.menuOrigin(artist = false, fromPlayer = true))
        assertEquals("player", HubWave.menuOrigin(artist = true, fromPlayer = true))
        assertEquals("track_menu", HubWave.menuOrigin(artist = false, fromPlayer = false))
        assertEquals("artist_menu", HubWave.menuOrigin(artist = true, fromPlayer = false))
    }

    @Test
    fun theSeedStaysNextToTheOrigin() {
        assertEquals(
            mapOf("seed" to mapOf("nd_artist_id" to "ar1"), "origin" to "artist_page", "client" to "app/1.14.0"),
            body("artist_page", mapOf("nd_artist_id" to "ar1")),
        )
    }

    @Test
    fun aWaveStartedAgainAfter404CarriesRestartAndTheOldSession() {
        val body = body(WaveOrigin.RESTART, mapOf("ref" to "dz:7"), restartOf = "oldsession")
        assertEquals("restart", body["origin"])
        assertEquals("oldsession", body["restart_of"])
        assertEquals(mapOf("ref" to "dz:7"), body["seed"])
    }

    @Test
    fun anOlderHubGetsTheBodyItAlwaysGot() {
        assertEquals(mapOf("seed" to mapOf("ref" to "dz:7")), WaveSession.startBody(mapOf("ref" to "dz:7"), null, null, null, null))
        assertEquals(emptyMap<String, Any>(), WaveSession.startBody(null, null))
    }

    @Test
    fun anOriginTheHubWouldRefuseIsLeftOut() {
        assertFalse(body("Home Card").containsKey("origin"))
        assertFalse(body("x".repeat(33)).containsKey("origin"))
        assertFalse(body("").containsKey("origin"))
    }

    // --- playlists ---------------------------------------------------------------------------------

    @Test
    fun aPlaylistIsPlayedFromItsOwnContext() {
        assertEquals("playlist:pl1", NativeContexts.playlist("pl1"))
        assertEquals("playlist", NativeContexts.playlist(null))
        assertEquals("playlist", NativeContexts.playlist(" "))
        assertEquals("nd_playlist", NativeContexts.originOf("playlist:pl1"))
        assertEquals("nd_playlist", NativeContexts.originOf("playlist"))
    }

    @Test
    fun nativeContextsHaveTheirOrigins() {
        for (c in listOf("album", "artist", "liked", "queue")) assertEquals(c, NativeContexts.originOf(c))
        assertNull(NativeContexts.originOf("continuous"))
        assertNull(NativeContexts.originOf("mix:5"))
    }

    @Test
    fun theQueueStillGoesOnWithAMixOrAWave() {
        assertEquals("mix:5", NativeContexts.queue("queue", "mix:5"))
        assertEquals("wave:x", NativeContexts.queue("queue", "wave:x"))
        assertEquals("playlist:pl1", NativeContexts.queue("playlist:pl1", "mix:5"))
    }

    // --- launch ids ------------------------------------------------------------------------------

    @Test
    fun everyNewQueueHasANewLaunchAndAllItsTracksTheSame() {
        val first = Launch.fresh("album")
        val second = Launch.fresh("album")
        assertNotEquals(first.id, second.id)
        assertTrue(first.id.length <= Launch.MAX_ID)
        assertEquals("album", first.origin)
    }

    @Test
    fun aQueuePlayInsideAMixKeepsTheMixLaunch() {
        val mix = Launch.of("page-launch", "mix_card")!!
        val fresh = Launch.fresh("queue")
        assertSame(mix, NativeContexts.launch("queue", "mix:5", mix, fresh))
        assertSame(mix, NativeContexts.launch("queue", "wave:s1", mix, fresh))
        assertSame(fresh, NativeContexts.launch("queue", "album", mix, fresh))
        assertSame(fresh, NativeContexts.launch("album", "mix:5", mix, fresh))
        assertSame(fresh, NativeContexts.launch("queue", "mix:5", null, fresh))
    }

    @Test
    fun theBridgeKeepsTheLaunchOfThePageAndGivesTheRestOneNewLaunch() {
        val page = HubTracks.Item().apply { songId = "s1"; launchId = "pwa-1"; launchOrigin = "mix_page" }
        val other = HubTracks.Item().apply { ref = "dz:2" }
        val another = HubTracks.Item().apply { songId = "s3" }
        val launches = HubPlayer.launches(listOf(page, other, another), "mix_card")
        assertEquals("pwa-1", launches.getValue("s1").id)
        assertEquals("mix_page", launches.getValue("s1").origin)
        assertEquals("mix_card", launches.getValue("hub:dz:2").origin)
        assertSame(launches.getValue("hub:dz:2"), launches.getValue("s3"))
        assertNotEquals("pwa-1", launches.getValue("s3").id)
    }

    @Test
    fun aCallWithoutALaunchGetsANewOneEveryTime() {
        val item = { HubTracks.Item().apply { songId = "s1" } }
        val a = HubPlayer.launches(listOf(item()), null).getValue("s1")
        val b = HubPlayer.launches(listOf(item()), null).getValue("s1")
        assertNotEquals(a.id, b.id)
        assertNull(a.origin)
    }

    @Test
    fun aLaunchTheHubWouldRefuseDoesNotReachTheEvent() {
        val long = HubTracks.Item().apply { songId = "s1"; launchId = "x".repeat(65); launchOrigin = "mix_page" }
        val badOrigin = HubTracks.Item().apply { songId = "s2"; launchId = "pwa-1"; launchOrigin = "Mix Page" }
        val launches = HubPlayer.launches(listOf(long, badOrigin), null)
        assertNotEquals("x".repeat(65), launches.getValue("s1").id)
        assertEquals("pwa-1", launches.getValue("s2").id)
        assertNull(launches.getValue("s2").origin)
    }

    @Test
    fun theTracksOfAWaveShareTheWaveLaunch() {
        val item = WaveTrack().apply { ref = "dz:1" }.item("wave:abc")
        assertEquals("wave:abc", item.launchId)
        assertEquals("wave", item.launchOrigin)
        val mix = WaveTrack().apply { ref = "dz:1" }.item("mix:3")
        assertNull(mix.launchId)
        assertEquals("wave:abc", HubPlayer.launches(listOf(item), null).getValue("hub:dz:1").id)
    }

    // --- events and the file on the phone --------------------------------------------------------

    private fun event(id: String, launchId: String? = null, launchOrigin: String? = null) =
        PlayEvent.of("start", "s1", "app/test", 1_000, context = "album", launchId = launchId, launchOrigin = launchOrigin)
            .let { PlayEvent(id, it.ts, it.kind, ndSongId = it.ndSongId, context = it.context, client = it.client, launchId = launchId, launchOrigin = launchOrigin) }

    @Test
    fun theLaunchTravelsWithEveryEventOfTheTrack() {
        val track = PlayTracker.Track("s1", 100_000, "album", null, Launch.of("l-1", "album"))
        val seen = mutableListOf<Launch?>()
        val tracker = PlayTracker { _, t, _, _ -> seen += t.launch }
        tracker.playing(track, 0, 0)
        tracker.leave("s1", 10_000, 10_000, ended = false)
        assertEquals(listOf("l-1", "l-1"), seen.map { it?.id })
        val e = PlayEvent.of("start", "s1", "app/test", 0, launchId = "l-1", launchOrigin = "album")
        assertEquals("l-1", e.launchId)
        assertEquals("album", e.launchOrigin)
    }

    @Test
    fun theFileOnThePhoneKeepsTheLaunchAndAnOldFileStillReads() {
        val file = tmp.root.resolve("events.json")
        val store = PlayEventStore(file)
        store.add(event("e1", "l-1", "nd_playlist"))
        store.add(event("e2"))
        val again = PlayEventStore(file).head(10)
        assertEquals("l-1", again[0].launchId)
        assertEquals("nd_playlist", again[0].launchOrigin)
        assertNull(again[1].launchId)

        // a file written before this version has no such fields
        val old = tmp.root.resolve("old.json")
        old.writeText(
            """[{"event_id":"o1","ts":"2026-01-01T00:00:00.000Z","kind":"start","nd_song_id":"s9","context":"album","client":"app/1.13.5"}]""",
        )
        val read = PlayEventStore(old).head(10).single()
        assertEquals("o1", read.eventId)
        assertNull(read.launchId)
        assertNull(read.launchOrigin)
    }

    @Test
    fun theHubGetsTheLaunchOnlyWhenItKnowsIt() {
        val e = event("e1", "l-1", "mix_card")
        val with = HubClient.eventPayload(listOf(e), launch = true).single()
        assertEquals("l-1", with.get("launch_id").asString)
        assertEquals("mix_card", with.get("launch_origin").asString)
        val without = HubClient.eventPayload(listOf(e), launch = false).single()
        assertFalse(without.has("launch_id"))
        assertFalse(without.has("launch_origin"))
        // the stored event keeps them for a later, newer Hub
        assertEquals("l-1", e.launchId)
    }

    @Test
    fun anEventWithoutALaunchSendsNeitherField() {
        val payload = HubClient.eventPayload(listOf(event("e1"))).single()
        assertFalse(payload.has("launch_id"))
        assertFalse(payload.has("launch_origin"))
    }
}
