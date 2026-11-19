package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubTrackInfoTest {
    @Test
    fun namesKnownProvidersAndKeepsUnknownOnes() {
        assertEquals("Hitmos", HubTrackInfo.describe("hitmoz", null))
        assertEquals("newsrc", HubTrackInfo.describe("newsrc", ""))
    }

    @Test
    fun addsOnlyWebAddresses() {
        assertEquals("YouTube\nhttps://youtu.be/x", HubTrackInfo.describe("ytdlp", "https://youtu.be/x"))
        assertEquals("Hitmos", HubTrackInfo.describe("hitmoz", "/song/5"))
    }

    @Test
    fun unknownSourceHidesTheLine() {
        assertNull(HubTrackInfo.describe(null, "https://x"))
        assertNull(HubTrackInfo.describe(" ", null))
    }
}
