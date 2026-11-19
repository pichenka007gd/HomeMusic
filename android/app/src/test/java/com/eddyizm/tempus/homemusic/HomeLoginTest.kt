package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLoginTest {
    @Test
    fun serverAddressGetsASchemeAndLosesTheSlash() {
        assertEquals("https://203.0.113.10:8444", HomeLogin.normalizeServer(" 203.0.113.10:8444/ "))
        assertEquals("http://192.0.2.10:4533", HomeLogin.normalizeServer("http://192.0.2.10:4533"))
        assertEquals("https://music.example.com", HomeLogin.normalizeServer("music.example.com"))
        // a path is kept: Navidrome may live under one
        assertEquals("https://example.com/navidrome", HomeLogin.normalizeServer("https://example.com/navidrome/"))
    }

    @Test
    fun notAnAddress() {
        assertNull(HomeLogin.normalizeServer(""))
        assertNull(HomeLogin.normalizeServer("   "))
        assertNull(HomeLogin.normalizeServer("мама"))
        assertNull(HomeLogin.normalizeServer("http://"))
    }

    @Test
    fun sameServerIgnoresSchemeDefaultsCaseAndSlash() {
        assertTrue(HomeLogin.sameServer("203.0.113.10:8444", "https://203.0.113.10:8444/"))
        assertTrue(HomeLogin.sameServer("HTTPS://Music.Example.com", "music.example.com"))
        assertFalse(HomeLogin.sameServer("https://203.0.113.10:8444", "https://203.0.113.10:8445"))
        assertFalse(HomeLogin.sameServer("http://192.0.2.10:4533", "https://192.0.2.10:4533"))
        assertFalse(HomeLogin.sameServer(null, "https://a.b"))
        assertFalse(HomeLogin.sameServer("", ""))
    }

    @Test
    fun usernameFromEmail() {
        assertEquals("listener.k", HomeLogin.usernameFromEmail("Listener.K@example.com"))
        assertEquals("test_user-1", HomeLogin.usernameFromEmail("test_user-1@example.com"))
        assertEquals("ab", HomeLogin.usernameFromEmail("a+b@x.y"))
        assertEquals("", HomeLogin.usernameFromEmail(null))
    }
}
