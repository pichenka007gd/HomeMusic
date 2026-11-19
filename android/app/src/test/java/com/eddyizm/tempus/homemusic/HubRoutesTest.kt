package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HubRoutesTest {
    @Test
    fun normalizeAddsSchemeAndDropsTrailingParts() {
        assertEquals("https://203.0.113.10:8445", HubConfig.normalize("203.0.113.10:8445/"))
        assertEquals("http://192.0.2.10:8080", HubConfig.normalize(" http://192.0.2.10:8080/#/search "))
        assertNull(HubConfig.normalize(""))
        assertNull(HubConfig.normalize(null))
    }

    @Test
    fun routesMapToNativeTabs() {
        assertEquals(0, HubFragment.sectionOf("/search?q=kino"))
        assertEquals(0, HubFragment.sectionOf("/artist/dz:4505880"))
        assertEquals(0, HubFragment.sectionOf("/album/dz:1"))
        assertEquals(1, HubFragment.sectionOf("/downloads/12"))
        assertEquals(2, HubFragment.sectionOf("/assistant?mode=chat"))
        assertEquals(3, HubFragment.sectionOf("/library/album/3"))
        assertEquals(3, HubFragment.sectionOf("/upload"))
        assertEquals(-1, HubFragment.sectionOf("/more"))
    }

    @Test
    fun routeIsTakenFromTheHash() {
        assertEquals("/assistant/5", HubFragment.routeOf("http://h:8080/?embedded=1#/assistant/5"))
        assertEquals("/", HubFragment.routeOf("http://h:8080/"))
        assertNull(HubFragment.routeOf(null))
    }
}
