package com.eddyizm.tempus.util

import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import com.eddyizm.tempus.homemusic.HubTracks

/**
 * Cache key for the streaming cache based on what identifies the audio bytes
 * (server + song id + requested transcode quality) instead of the full URL.
 *
 * The full URL is a bad key: it carries the auth token/salt and whichever of the
 * two server addresses (local vs public) is currently in use, so the same already
 * cached track becomes a cache miss - and a duplicate cache entry - as soon as any
 * of those change. maxBitRate/format stay part of the key because a different
 * transcode really is different audio.
 */
private const val TAG = "StreamingCacheKeyFactory"

@UnstableApi
class StreamingCacheKeyFactory : CacheKeyFactory {
    override fun buildCacheKey(dataSpec: DataSpec): String {
        // HomeMusic: a key that is just the address was made by the download cache above this one (its default); it is no
        // choice of anyone, so the player and the precache end up with the same key for a song
        dataSpec.key?.takeIf { it != dataSpec.uri.toString() }?.let { return it }

        val uri = dataSpec.uri
        val scheme = uri.scheme
        // HomeMusic: a catalog track of the Hub; a light stream (saving traffic) has a key of its own
        if (scheme != "http" && scheme != "https") return HubTracks.variantKey(uri, uri.toString())

        val id = try {
            uri.getQueryParameter("id")
        } catch (exception: UnsupportedOperationException) {
            // opaque uri, cannot be parsed for query parameters
            Log.d(TAG, "Opaque URI, falling back to URL-based cache key", exception)
            null
        } ?: return uri.toString()

        val bitrate = uri.getQueryParameter("maxBitRate").orEmpty()
        val format = uri.getQueryParameter("format").orEmpty()
        val timeOffset = uri.getQueryParameter("timeOffset").orEmpty()
        val server = Preferences.getServerId().orEmpty()

        // HomeMusic: a song the Hub serves lighter on a metered network is another cache entry (`HubTracks.variantKey`)
        return HubTracks.variantKey(uri, "$server|$id|$bitrate|$format|$timeOffset")
    }
}
