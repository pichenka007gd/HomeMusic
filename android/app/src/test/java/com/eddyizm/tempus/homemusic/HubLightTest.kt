package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubLightTest {
    private val navidrome = "https://music.example.com/rest/stream?u=test-user&s=abc&t=def&v=1.16.1&c=Tempus"

    @Test
    fun fullCatalogPlaybackNeverReadsOldPreviewCacheEntries() {
        val previous = "homemusic-hub://listen?ref=dz:3135556"
        val full = HubLight.fullTrackKey(previous)
        assertNotEquals(previous, full)
        assertNotEquals(HubLight.key(previous, true), HubLight.key(full, true))
        assertTrue(HubLight.isLightKey(HubLight.key(full, true)))
        assertFalse(HubLight.asked(HubLight.key(full, false), previous, economy = true))
        assertEquals("https://hub.test/api/listen/stream?ref=dz%3A1&full=1",
            HubLight.streamUrl("https://hub.test", "dz:1", light = false, fullOnly = true))
        assertEquals("https://hub.test/api/listen/stream?ref=dz%3A1&q=mobile&full=1",
            HubLight.streamUrl("https://hub.test", "dz:1", light = true, fullOnly = true))
    }

    @Test
    fun theLightVariantIsAnotherCacheEntry() {
        val original = "homemusic-hub://listen?ref=dz:3135556"
        assertEquals(original, HubLight.key(original, light = false))
        assertEquals("$original|mobile", HubLight.key(original, light = true))
        assertNotEquals(HubLight.key(original, false), HubLight.key(original, true))
        assertFalse(HubLight.isLightKey(original))
        assertTrue(HubLight.isLightKey(HubLight.key(original, true)))
        assertFalse(HubLight.isLightKey(null))
        // a song of the library: the key Tempus builds for it, with the same suffix
        assertTrue(HubLight.isLightKey(HubLight.key("srv|song1|0|raw|", true)))
    }

    @Test
    fun theHttpSourceFollowsTheCacheKeyOfItsRequest() {
        val address = "$navidrome&id=abc123"
        // the key of the streaming cache says it, whatever the network is now (it may have changed since the key was made)
        assertTrue(HubLight.asked("srv|abc123|0|raw|0|mobile", address, economy = false))
        assertFalse(HubLight.asked("srv|abc123|0|raw|0", address, economy = true))
        // no cache key, or the address itself (the download cache's default): the network decides
        assertTrue(HubLight.asked(null, address, economy = true))
        assertFalse(HubLight.asked(null, address, economy = false))
        assertTrue(HubLight.asked(address, address, economy = true))
        assertFalse(HubLight.asked(address, address, economy = false))
    }

    @Test
    fun theLightVariantIsAskedOnlyWhenSavingAndWhenTheOriginalIsNotCachedWhole() {
        assertTrue(HubLight.useLight(economy = true, eligible = true, originalCached = false))
        // not saving (Wi-Fi, an unmetered plan, the setting off): the file as it is
        assertFalse(HubLight.useLight(economy = false, eligible = true, originalCached = false))
        // a stream the Hub cannot lighten (radio, the user's own transcode)
        assertFalse(HubLight.useLight(economy = true, eligible = false, originalCached = false))
        // the whole original is in the cache already: it plays from there, nothing is loaded
        assertFalse(HubLight.useLight(economy = true, eligible = true, originalCached = true))
    }

    @Test
    fun theHubAddressAsksForTheLightVariantWithQ() {
        assertEquals(
            "https://hub.example.com/api/listen/stream?ref=dz%3A3135556&q=mobile",
            HubLight.streamUrl("https://hub.example.com", "dz:3135556", light = true),
        )
        // without q the Hub answers as it always did (older Hubs know no q either)
        assertEquals(
            "https://hub.example.com/api/listen/stream?ref=dz%3A3135556",
            HubLight.streamUrl("https://hub.example.com", "dz:3135556", light = false),
        )
        assertEquals(
            "http://192.0.2.5:8080/api/listen/stream?ref=nd%3Aabc123&q=mobile",
            HubLight.streamUrl("http://192.0.2.5:8080", HubLight.libraryRef("abc123"), light = true),
        )
    }

    @Test
    fun aSongOfTheLibraryIsRecognisedByItsNavidromeAddress() {
        assertEquals("abc123", HubLight.libraryId("$navidrome&maxBitRate=0&format=raw&id=abc123"))
        // the server decides (nothing set): no maxBitRate and no format at all
        assertEquals("abc123", HubLight.libraryId("$navidrome&id=abc123"))
        assertEquals("abc123", HubLight.libraryId("https://music.example.com/rest/stream.view?u=r&id=abc123"))
        assertEquals("a b", HubLight.libraryId("$navidrome&id=a%20b"))
    }

    @Test
    fun aTranscodeTheUserSetOrAnythingElseIsLeftAlone() {
        // Tempus' own transcoding (a bitrate or format the user chose) is theirs
        assertNull(HubLight.libraryId("$navidrome&maxBitRate=128&format=mp3&id=abc123"))
        assertNull(HubLight.libraryId("$navidrome&maxBitRate=0&format=opus&id=abc123"))
        // a stream from the middle (a transcode's offset)
        assertNull(HubLight.libraryId("$navidrome&timeOffset=30&id=abc123"))
        assertEquals("abc123", HubLight.libraryId("$navidrome&timeOffset=0&id=abc123"))
        // a download for «Скачанное» is the original
        assertNull(HubLight.libraryId("https://music.example.com/rest/download?u=r&id=abc123"))
        assertNull(HubLight.libraryId("$navidrome"))
        assertNull(HubLight.libraryId("homemusic-hub://listen?ref=dz:1"))
        assertNull(HubLight.libraryId("file:///storage/a.mp3"))
        assertNull(HubLight.libraryId(null))
        assertNull(HubLight.libraryId("not a url"))
    }

    @Test
    fun theHubSaysWhichVariantItAnswered() {
        assertEquals("light", HubLight.variantOf(mapOf("x-homemusic-variant" to listOf("light"))))
        assertEquals("original-pending", HubLight.variantOf(mapOf("X-HomeMusic-Variant" to listOf(" original-pending "))))
        assertEquals(HubLight.PENDING, HubLight.variantOf(mapOf("X-HomeMusic-Variant" to listOf("original-pending"))))
        assertNull(HubLight.variantOf(mapOf("Content-Type" to listOf("audio/mpeg"))))
        assertNull(HubLight.variantOf(emptyMap()))
    }

    @Test
    fun aHeadOfTheLightVariantIsSizedByItsOwnRate() {
        assertEquals(320, HubLight.headKbps(320, light = false))
        assertEquals(160, HubLight.headKbps(320, light = true))
        assertEquals(160, HubLight.headKbps(0, light = true)) // the song does not say
        assertEquals(128, HubLight.headKbps(128, light = true)) // lighter than the copy: as it is
        // so the head of a light stream is half the bytes of the heavy one
        val heavy = QueueLoading.headBytes(HubLight.headKbps(320, false), economy = true)
        val light = QueueLoading.headBytes(HubLight.headKbps(320, true), economy = true)
        assertTrue(light < heavy)
    }
}
