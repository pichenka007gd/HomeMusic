package com.eddyizm.tempus.homemusic

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.DefaultContentMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

class CacheLengthTest {
    private val unset = C.LENGTH_UNSET.toLong()

    /** An upstream answering a ranged request for the head: [headers] and [length] bytes. */
    private class Upstream(private val headers: Map<String, List<String>>, private val length: Long) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) {}
        override fun open(dataSpec: DataSpec): Long = length
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = null
        override fun getResponseHeaders(): Map<String, List<String>> = headers
        override fun close() {}
    }

    private fun metadata(length: Long): ContentMetadata =
        if (length == unset) DefaultContentMetadata.EMPTY
        else DefaultContentMetadata.EMPTY.copyWithMutationsApplied(
            ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }
        )

    private fun cache(storedLength: Long = unset): Cache = mock {
        on { getContentMetadata(any()) } doReturn metadata(storedLength)
    }

    private fun headSpec(key: String?): DataSpec =
        DataSpec.Builder().setUri(mock<Uri>()).setPosition(0).setLength(655_360).setKey(key).build()

    private fun open(cache: Cache, headers: Map<String, List<String>>, key: String? = "hub:dz:1") =
        CacheLength.Factory({ Upstream(headers, 655_360) }, cache).createDataSource().open(headSpec(key))

    @Test
    fun theTotalIsReadFromContentRange() {
        assertEquals(7_340_032L, CacheLength.total(mapOf("Content-Range" to listOf("bytes 0-655359/7340032"))))
        // header names are case-insensitive (HTTP/2 sends them in lower case)
        assertEquals(7_340_032L, CacheLength.total(mapOf("content-range" to listOf("bytes 0-655359/7340032"))))
    }

    @Test
    fun noTotalWhenTheAnswerDoesNotSayIt() {
        assertEquals(unset, CacheLength.total(mapOf("Content-Range" to listOf("bytes 0-655359/*"))))
        assertEquals(unset, CacheLength.total(mapOf("Content-Length" to listOf("655360"))))
        assertEquals(unset, CacheLength.total(emptyMap()))
    }

    @Test
    fun theHeadOfANextTrackStoresTheFileLength() {
        // The precache's bounded request: CacheDataSource itself stores no length for it, and without one the player
        // finds the track unseekable when it opens it from the cached head.
        val cache = cache()
        val opened = open(cache, mapOf("Content-Range" to listOf("bytes 0-655359/7340032")))

        assertEquals(655_360L, opened)
        val stored = argumentCaptor<ContentMetadataMutations>()
        verify(cache).applyContentMetadataMutations(eq("hub:dz:1"), stored.capture())
        assertEquals(7_340_032L, ContentMetadata.getContentLength(DefaultContentMetadata.EMPTY.copyWithMutationsApplied(stored.firstValue)))
    }

    @Test
    fun aKnownLengthIsLeftAlone() {
        val cache = cache(storedLength = 7_340_032L)
        open(cache, mapOf("Content-Range" to listOf("bytes 0-655359/9999999")))
        verify(cache, never()).applyContentMetadataMutations(any(), any())
    }

    @Test
    fun nothingIsStoredWithoutATotalOrAKey() {
        val cache = cache()
        open(cache, mapOf("Content-Length" to listOf("655360")))
        open(cache, mapOf("Content-Range" to listOf("bytes 0-655359/7340032")), key = null)
        verify(cache, never()).applyContentMetadataMutations(any(), any())
    }

    @Test
    fun knownSaysWhetherTheCacheHasTheLength() {
        assertTrue(CacheLength.known(cache(storedLength = 7_340_032L), "hub:dz:1"))
        assertFalse(CacheLength.known(cache(), "hub:dz:1"))
    }
}
