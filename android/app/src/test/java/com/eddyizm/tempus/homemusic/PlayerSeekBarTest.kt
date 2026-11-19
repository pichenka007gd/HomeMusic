package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PlayerSeekBarTest {
    private fun channels(c: Int) = listOf((c ushr 16) and 0xFF, (c ushr 8) and 0xFF, c and 0xFF)

    /** The largest per-channel difference between two colors. */
    private fun distance(a: Int, b: Int) = channels(a).zip(channels(b)).maxOf { (x, y) -> abs(x - y) }

    @Test
    fun theLoadedPartStandsOutInTheDarkTheme() {
        // colorOnPrimaryContainer and colorPrimaryContainer of the dark theme; the old loaded color was #E8DEF8
        val played = 0xFFEADDFF.toInt()
        val unplayed = 0xFF4F378B.toInt()
        val buffered = PlayerSeekBar.bufferedColor(played, unplayed)

        assertTrue(distance(0xFFE8DEF8.toInt(), played) < 10) // the bug: all but the played color
        assertTrue(distance(buffered, played) >= 50)
        assertTrue(distance(buffered, unplayed) >= 50)
    }

    @Test
    fun theLoadedPartStandsOutInTheLightTheme() {
        val played = 0xFF21005D.toInt()
        val unplayed = 0xFFEADDFF.toInt()
        val buffered = PlayerSeekBar.bufferedColor(played, unplayed)

        assertTrue(distance(buffered, played) >= 50)
        assertTrue(distance(buffered, unplayed) >= 50)
    }

    @Test
    fun theLoadedColorIsOpaqueAndBetweenTheTwo() {
        val buffered = PlayerSeekBar.bufferedColor(0xFFFFFFFF.toInt(), 0x80000000.toInt())
        assertEquals(0xFF, buffered ushr 24)
        assertEquals(listOf(115, 115, 115), channels(buffered)) // 0.45 of the way from 0 to 255
    }
}
