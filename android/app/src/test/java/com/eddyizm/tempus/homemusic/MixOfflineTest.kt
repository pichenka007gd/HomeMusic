package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.homemusic.MixOffline.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MixOfflineTest {
    private fun plan(state: State, mix: Long, songs: List<String>, onPhone: Set<String> = emptySet(), liked: Set<String> = emptySet()) =
        MixOffline.plan(state, mix, songs, onPhone, liked)

    @Test
    fun aFirstMixIsDownloadedWhole() {
        val p = plan(State(), 1, listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), p.download)
        assertTrue(p.remove.isEmpty())
    }

    @Test
    fun whatIsOnThePhoneIsNotDownloadedAndNotOwned() {
        // "b" the user downloaded himself: it stays his, a new mix will not remove it
        val p = plan(State(), 1, listOf("a", "b"), onPhone = setOf("b"))
        assertEquals(listOf("a"), p.download)
        assertFalse("b" in p.next.owned)
    }

    @Test
    fun aNewMixTakesAwayOnlyWhatTheLastOneBroughtAndNobodyTouched() {
        val state = State(1, owned = setOf("a", "b", "c", "d"), tried = setOf("a", "b", "c", "d"))
        // "b" stays in the new mix, "c" the user liked, "e" is new
        val p = plan(state, 2, listOf("b", "e"), onPhone = setOf("a", "b", "c", "d"), liked = setOf("c"))
        assertEquals(listOf("a", "d"), p.remove) // not "b" (still in the mix) and not "c" (liked)
        assertEquals(listOf("e"), p.download)
        assertEquals(setOf("b"), p.next.owned) // "c" is the user's from now on
    }

    @Test
    fun aSongTheUserRemovedIsNotBroughtBackInTheSameMix() {
        val state = State(5, owned = setOf("a"), tried = setOf("a", "b"))
        // "a" and "b" were asked for earlier today and are gone from the phone: left alone until tomorrow's mix
        val same = plan(state, 5, listOf("a", "b", "c"), onPhone = emptySet())
        assertEquals(listOf("c"), same.download)
        val next = plan(state, 6, listOf("a", "b", "c"), onPhone = emptySet())
        assertEquals(listOf("a", "b", "c"), next.download)
    }

    @Test
    fun anInterruptedRunGoesOnWithWhatIsLeft() {
        // the run asked for "a" and "b" and was stopped: only they are tried
        val state = State(7, owned = setOf("a", "b"), tried = setOf("a", "b"))
        val p = plan(state, 7, listOf("a", "b", "c", "d"), onPhone = setOf("a", "b"))
        assertEquals(listOf("c", "d"), p.download)
        assertEquals(setOf("a", "b"), p.next.owned)
    }

    @Test
    fun theStateSurvivesTheStore() {
        val state = State(42, setOf("x1", "x2"), setOf("x1", "x2", "x3"))
        assertEquals(state, State.decode(state.encode()))
        assertEquals(State(), State.decode(null))
        assertEquals(State(), State.decode("garbage"))
        assertEquals(State(9), State.decode(State(9).encode()))
    }

    @Test
    fun theLastTwoGigabytesAreLeftFree() {
        val gb = 1024L * 1024 * 1024
        assertTrue(MixOffline.fits(3 * gb, 8 * 1024 * 1024))
        assertFalse(MixOffline.fits(2 * gb + 1024 * 1024, 8 * 1024 * 1024))
        assertFalse(MixOffline.fits(gb, 0))
    }
}
