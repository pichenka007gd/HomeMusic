package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class HomeTrafficTest {
    @Test
    fun economyNeedsTheSettingAndAMeteredNetwork() {
        assertTrue(HomeTraffic.decide(enabled = true) { true })
        assertFalse(HomeTraffic.decide(enabled = true) { false }) // an unmetered network: nothing to save
        assertFalse(HomeTraffic.decide(enabled = false) { true }) // the user switched it off
    }

    @Test
    fun theNetworkIsNotReadWhenTheSettingIsOff() {
        var asked = false
        HomeTraffic.decide(enabled = false) { asked = true; true }
        assertFalse(asked)
    }

    @Test
    fun whenSavingOnlyOneNextTrackIsPrecached() {
        assertEquals(1, HomeTraffic.precacheCount(3, economy = true))
        assertEquals(3, HomeTraffic.precacheCount(3, economy = false))
        assertEquals(1, HomeTraffic.precacheCount(1, economy = true))
        assertEquals(0, HomeTraffic.precacheCount(0, economy = true)) // switched off stays off
    }

    @Test
    fun theCacheIsAtMostATenthOfTheFreeSpace() {
        val gb = 1024L * 1024 * 1024
        assertEquals(2048, HomeDefaults.cacheSizeFor(30 * gb))
        assertEquals(2048, HomeDefaults.cacheSizeFor(20 * gb))
        assertEquals(1024, HomeDefaults.cacheSizeFor(19 * gb))
        assertEquals(1024, HomeDefaults.cacheSizeFor(10 * gb))
        assertNull(HomeDefaults.cacheSizeFor(9 * gb)) // the small default stays on a nearly full phone
    }

    @Test
    fun theCountCoversTodayAndTheDaysBefore() {
        val noon = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 2, 12, 0, 0) }.timeInMillis
        val keys = HomeTrafficStats.keysOf(noon, 3)
        assertEquals(listOf("20260302", "20260301", "20260228"), keys) // across a month end
        assertEquals(listOf("20260302"), HomeTrafficStats.keysOf(noon, 1))
    }
}
