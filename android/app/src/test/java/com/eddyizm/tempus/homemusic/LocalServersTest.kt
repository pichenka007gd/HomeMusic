package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalServersTest {
    @Test fun aDelayedOldHubRequestCannotAcquireTheNewServerCredentials() {
        val old = LocalServers.loginTarget("http://a.example:8080", "http://a.example:4533", "http://a.example:4533",
            "http://a.example:8080", null, "listener-a", "example-a")!!
        assertNull(LocalServers.loginTarget(old.base, "http://b.example:4533", "http://b.example:4533",
            "http://b.example:8080", null, "listener-b", "example-b"))
        assertNull(LocalServers.loginTarget(old.base, "http://a.example:4533", "http://b.example:4533",
            "http://a.example:8080", null, "listener-b", "example-b"))
        val otherAccount = LocalServers.loginTarget(old.base, old.music, old.music, old.base, null, "listener-b", "example-b")
        assertNotEquals(old, otherAccount)
        assertFalse(old.toString().contains("example-a"))
    }

    @Test fun aLocalPcGetsTheComposePortsWithoutAnyMaintainerDefault() {
        assertEquals("http://192.0.2.10:4533", LocalServers.music(" 192.0.2.10 "))
        assertEquals("http://music-pc.local:4533", LocalServers.music("music-pc.local"))
        assertEquals("http://music-pc:4533", LocalServers.music("music-pc"))
        assertEquals("http://192.0.2.10:8080", LocalServers.hub("192.0.2.10"))
        assertEquals("http://192.0.2.10:8080", LocalServers.suggestedHub("http://192.0.2.10:4533"))
        assertNull(LocalServers.music(""))
        assertNull(LocalServers.hub(""))
    }

    @Test fun explicitUrlsPortsAndProxyPathsAreKept() {
        assertEquals("http://music-pc:14533", LocalServers.music("music-pc:14533/"))
        assertEquals("https://music.example.com/navidrome", LocalServers.music("https://music.example.com/navidrome/"))
        assertEquals("http://music-pc", LocalServers.hub("http://music-pc/"))
        assertEquals("https://music.example.com:8080", LocalServers.suggestedHub("https://music.example.com/navidrome"))
        assertEquals("http://[2001:db8::1]:4533", LocalServers.music("[2001:db8::1]"))
    }

    @Test fun pastedCredentialsQueriesAndInvalidAddressesAreNotAccepted() {
        val credentialUrl = listOf("http", "user:password@music-pc:4533").joinToString("://")
        assertNull(LocalServers.music(credentialUrl))
        assertNull(LocalServers.music("http://music-pc:4533?token=example"))
        assertNull(LocalServers.music("http://music-pc:4533#secret"))
        assertNull(LocalServers.music("file:///music"))
        assertNull(LocalServers.music("not an address"))
        assertNull(LocalServers.music("http://"))
    }

    @Test fun aHubBindingCannotMatchADifferentMusicServer() {
        assertTrue(LocalServers.same("http://music-pc:4533", "http://music-pc:4533/"))
        assertFalse(LocalServers.same("http://music-pc:4533", "http://other-pc:4533"))
        assertFalse(LocalServers.same("http://music-pc:4533", "http://music-pc:14533"))
        assertFalse(LocalServers.same(null, null))
        assertFalse(LocalServers.same("bad-a", "bad-b"))
    }
}
