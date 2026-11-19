package com.eddyizm.tempus.homemusic

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * The light variant of a song's audio for a metered network (`HomeTraffic.economy()`): the Hub's Opus copy of a
 * heavy file (mp3 320, flac → Ogg Opus 160, about half the bytes; a file that is light already comes as it is),
 * asked with `q=mobile` from `GET /api/listen/stream`. It serves catalog tracks (`homemusic-hub:`) and, in place of
 * Navidrome's stream, the songs of the library (`ref=nd:<song>`). The choice is made when a stream is opened, not
 * when the queue is built: the network may change meanwhile. This file is the pure part (tests: `HubLightTest`);
 * the player side is in [HubTracks] and `StreamingCacheKeyFactory`.
 *
 * The bytes of the two variants must never meet in one cache entry: the cache key of the light variant differs
 * ([key]), and the Hub tells which variant it answered with ([VARIANT_HEADER]). `original-pending` means the copy
 * was not ready and the original came instead: it is dropped from the cache entry of the light variant ([PENDING]).
 */
object HubLight {
    const val QUALITY = "mobile"

    /** Appended to the cache key of a light stream (the key is also what the HTTP source is told: `DataSpec.key`). */
    const val KEY_SUFFIX = "|mobile"

    const val VARIANT_HEADER = "X-HomeMusic-Variant"
    const val PENDING = "original-pending"

    /** The Opus copy plays at about this rate: the head of the next track for the precache is sized by it. */
    const val LIGHT_KBPS = 160

    /** A bad copy of the cache entry is dropped this long after its stream closed (the cache writer finishes first). */
    const val EVICT_DELAY_MS = 4_000L

    /** The cache key of a stream: [original] for the file as it is, another one for the light variant. */
    fun key(original: String, light: Boolean): String = if (light) original + KEY_SUFFIX else original

    /** Old catalog entries may contain a 30-second preview. Full-only requests never reuse those bytes. */
    fun fullTrackKey(original: String): String = "$original|full-track-v1"

    fun isLightKey(key: String?): Boolean = key != null && key.endsWith(KEY_SUFFIX)

    /**
     * Whether the HTTP source is asked for the light variant: the cache key of its request says it ([isLightKey]);
     * a request without a cache key (or with the address as the key, the default of a cache with no choice made) has
     * no cache entry to keep apart, so the network decides ([economy]).
     */
    fun asked(key: String?, address: String, economy: Boolean): Boolean =
        if (key == null || key == address) economy else isLightKey(key)

    /**
     * Whether a stream is asked for in the light variant: saving traffic, a stream the Hub can lighten, and the
     * file as it is not in the cache whole already (then it plays from there, nothing is loaded).
     */
    fun useLight(economy: Boolean, eligible: Boolean, originalCached: Boolean): Boolean =
        economy && eligible && !originalCached

    /** `GET /api/listen/stream` of [ref] on the Hub at [base]; [light]: the light variant. */
    fun streamUrl(base: String, ref: String, light: Boolean, fullOnly: Boolean = false): String =
        "$base/api/listen/stream?ref=${URLEncoder.encode(ref, "UTF-8")}" +
            (if (light) "&q=$QUALITY" else "") + (if (fullOnly) "&full=1" else "")

    /** The Hub's ref of a song of the library. */
    fun libraryRef(songId: String): String = "nd:$songId"

    /** The variant a Hub answer says it is (headers by any case), null when it does not say. */
    fun variantOf(headers: Map<String, List<String>>): String? =
        headers.entries.firstOrNull { it.key.equals(VARIANT_HEADER, ignoreCase = true) }?.value?.firstOrNull()?.trim()

    /**
     * The song id of a Navidrome `stream` address the Hub can serve in its place: the file as it is (no transcode
     * set in Tempus: a `maxBitRate`/`format` the user chose is theirs), from the start. Null for any other address.
     */
    fun libraryId(url: String?): String? {
        val uri = try {
            URI(url ?: return null)
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme != "http" && uri.scheme != "https") return null
        val last = uri.path?.substringAfterLast('/') ?: return null
        if (last != "stream" && last != "stream.view") return null
        val query = query(uri.rawQuery)
        if (!QueueLoading.untranscoded(query["format"], query["maxBitRate"])) return null
        if ((query["timeOffset"] ?: "0") != "0") return null
        return query["id"]?.takeIf { it.isNotBlank() }
    }

    private fun query(raw: String?): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in (raw ?: return out).split('&')) {
            val name = pair.substringBefore('=')
            if (name.isEmpty() || name in out) continue
            out[URLDecoder.decode(name, "UTF-8")] = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }
        return out
    }

    /** The bitrate a head of the precache is sized by: the light variant is lighter than what the song says. */
    fun headKbps(kbps: Int, light: Boolean): Int = if (light) (if (kbps in 1 until LIGHT_KBPS) kbps else LIGHT_KBPS) else kbps
}
