package com.eddyizm.tempus.homemusic

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations

/**
 * The whole length of a file of the streaming cache of which only the head is loaded.
 *
 * The precache (`QueuePreloader`) loads a next track's first seconds with a bounded request, and Media3's
 * CacheDataSource stores a file's length only from an unbounded one. The player then opens the track from its cached
 * head with no length, and ExoPlayer finds the track unseekable (Ogg/Opus, FLAC without a seek table and MP3 without
 * a Xing header seek by the length): the player screen has no seek bar until the track is opened again. The ranged
 * answer does say the length (`Content-Range: bytes 0-N/total`); [Factory] stores it the way CacheDataSource would.
 */
@UnstableApi
object CacheLength {
    private const val TAG = "CacheLength"

    /** The file's whole length from a ranged answer's headers; [C.LENGTH_UNSET] when they do not say it. */
    fun total(headers: Map<String, List<String>>): Long {
        // HttpURLConnection keeps its status line under a null key: compared from the side of the name
        val value = headers.entries.firstOrNull { "Content-Range".equals(it.key, ignoreCase = true) }
            ?.value?.firstOrNull() ?: return C.LENGTH_UNSET.toLong()
        return value.substringAfterLast('/', "").trim().toLongOrNull()?.takeIf { it > 0 } ?: C.LENGTH_UNSET.toLong()
    }

    /** The cache knows the whole length of the file under [key]. */
    fun known(cache: Cache, key: String): Boolean =
        ContentMetadata.getContentLength(cache.getContentMetadata(key)) != C.LENGTH_UNSET.toLong()

    /** The upstream of a cache writer: stores in [cache] the length its answers say, under the request's cache key. */
    class Factory(private val upstream: DataSource.Factory, private val cache: Cache) : DataSource.Factory {
        override fun createDataSource(): DataSource = Recording(upstream.createDataSource(), cache)
    }

    // Every DataSource method is passed on by hand: Kotlin's `by` skips the Java default getResponseHeaders.
    private class Recording(private val upstream: DataSource, private val cache: Cache) : DataSource {
        override fun addTransferListener(transferListener: TransferListener) =
            upstream.addTransferListener(transferListener)

        override fun open(dataSpec: DataSpec): Long {
            val length = upstream.open(dataSpec)
            val key = dataSpec.key
            if (key != null) store(key)
            return length
        }

        private fun store(key: String) {
            val total = total(upstream.responseHeaders)
            if (total == C.LENGTH_UNSET.toLong()) return
            try {
                if (!known(cache, key)) {
                    cache.applyContentMetadataMutations(key, ContentMetadataMutations().also {
                        ContentMetadataMutations.setContentLength(it, total)
                    })
                }
            } catch (e: Cache.CacheException) {
                Log.w(TAG, "length of $key not stored: $e")
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun close() = upstream.close()
    }
}
