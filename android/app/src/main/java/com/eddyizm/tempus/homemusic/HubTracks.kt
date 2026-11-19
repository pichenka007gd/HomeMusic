package com.eddyizm.tempus.homemusic

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.ContentMetadata
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.FavoriteRegistry
import com.google.gson.annotations.SerializedName
import java.io.IOException
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.function.Supplier

/**
 * Catalog tracks of the Hub (not in the library yet) in the app's player, so one player plays everything: the
 * page hands them over together with the library songs of the same list (`HomeMusicAndroid.playQueue`).
 * Such a track is a [Child] with the id `hub:<catalog ref>`. It streams from the Hub (`GET /api/listen/stream`,
 * the file a download would take) through [DataSourceFactory], which picks the Hub address and adds the session
 * cookie when the player opens it, so a queue made at home keeps playing outside. ♥ and «Скачать в библиотеку»
 * queue a Hub download (♥ also stars the song in Navidrome once it is imported, silently: no toast or notification).
 */
object HubTracks {
    private const val TAG = "HubTracks"
    const val ID_PREFIX = "hub:"
    private const val SCHEME = "homemusic-hub"

    // The Hub picks the file before the first byte (YouTube ~5–6 s), so the player waits longer than for Navidrome.
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    /** An entry of `playQueue`: a library song (`song_id`) or a catalog track (`ref` and what to show). */
    class Item {
        @SerializedName("song_id") var songId: String? = null
        var ref: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        @SerializedName("cover_url") var coverUrl: String? = null
        @SerializedName("duration_s") var durationS: Double? = null
        /** Where the page played the list from (`album`, `search`, `mix:<id>`…), for the listening data; newer pages only. */
        var context: String? = null
        /** The page's launch of the list (`plays.newLaunch`), the same on every entry; newer pages only. */
        @SerializedName("launch_id") var launchId: String? = null
        @SerializedName("launch_origin") var launchOrigin: String? = null
    }

    // Catalog tracks handed over in this process, for the player screen and the track menu.
    private val known = ConcurrentHashMap<String, Child>()

    @JvmStatic
    fun isHub(id: String?): Boolean = id?.startsWith(ID_PREFIX) == true

    private fun refOf(id: String) = id.removePrefix(ID_PREFIX)

    /** The queue entry of a catalog track. */
    fun child(item: Item): Child? {
        val ref = item.ref?.takeIf { it.isNotBlank() } ?: return null
        val song = Child(ID_PREFIX + ref).apply {
            title = item.title
            artist = item.artist
            album = item.album
            // An absolute address: CustomGlideRequest.createUrl passes it through as is.
            coverArtId = item.coverUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            duration = item.durationS?.toInt()
        }
        known[song.id]?.starred?.let { song.starred = it }
        known[song.id] = song
        return song
    }

    /** The song the player shows for [id]; after an app restart only the id is known. */
    @JvmStatic
    fun song(id: String): Child = known.getOrPut(id) { Child(id) }

    /** Where the player reads a catalog track from; null for any other song. */
    @JvmStatic
    fun streamUri(media: Child): Uri? {
        if (!isHub(media.id)) return null
        return Uri.Builder().scheme(SCHEME).authority("listen").appendQueryParameter("ref", refOf(media.id)).build()
    }

    /** A [streamUri] address (the precache loads these too). */
    @JvmStatic
    fun isStreamUri(uri: Uri): Boolean = uri.scheme == SCHEME

    /**
     * The precache (`QueuePreloader`) loading a catalog track ahead says so: that is no listen yet for the Hub (an
     * economy import downloads a track once listened to); the `start` event of the app is.
     */
    @JvmStatic
    fun prefetchHeaders(uri: Uri): Map<String, String> =
        if (isStreamUri(uri)) mapOf(PREFETCH_HEADER to "1") else emptyMap()

    const val PREFETCH_HEADER = "X-HomeMusic-Prefetch"

    // --- ♥ and download ---------------------------------------------------------------------

    /** A pending catalog heart is a normal toggle; the command shares the telemetry decision id. */
    @JvmStatic
    fun like(context: Context, media: Child) {
        setLiked(context, media, !FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, media.id, media.starred != null))
    }

    internal fun setLiked(context: Context, media: Child, starred: Boolean) {
        val before = FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, media.id, media.starred != null)
        if (before == starred) return
        if (!CatalogFavorites.submit(refOf(media.id), before, starred)) {
            Toast.makeText(context, context.getString(R.string.homemusic_hub_action_failed, ""), Toast.LENGTH_LONG).show()
        }
    }

    /** «Скачать в библиотеку» on a catalog track. */
    @JvmStatic
    fun download(context: Context, media: Child) = queueDownload(context.applicationContext, media.id)

    private fun queueDownload(app: Context, id: String) {
        val ref = refOf(id)
        HomeMusic.io.execute {
            val result = runCatching { HubClient.downloadTrack(ref, false) }
            HomeMusic.main.post {
                result.onSuccess { batch ->
                    // ♥ shows only as the ♥: where the file comes from is the Hub's business (no download status)
                    val text = if (batch.queued == 0 && !batch.expanding && batch.alreadyOwned > 0) R.string.homemusic_hub_already_owned
                        else R.string.homemusic_hub_download_queued
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                    // The page marks the row «качается» (and ♥) as if the tap had been there.
                    HubWebHolder.dispatchEvent(DOWNLOAD_EVENT, mapOf("ref" to ref, "star" to false))
                }.onFailure { e ->
                    Log.w(TAG, "download $ref", e)
                    Toast.makeText(app, app.getString(R.string.homemusic_hub_action_failed, e.message ?: ""), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** Sent to the page when a catalog track was queued for download from the app's player. */
    const val DOWNLOAD_EVENT = "hm:hub-download"

    /** Keep an open native heart in sync with decisions and asynchronous rollbacks. */
    @JvmStatic
    fun observeHeart(button: CompoundButton, media: Supplier<Child?>) {
        val refresh = Runnable {
            button.post {
                if (button.isAttachedToWindow) media.get()?.let {
                    button.isChecked = FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, it.id, it.starred != null)
                }
            }
        }
        button.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                FavoriteRegistry.addChangeListener(refresh)
                refresh.run()
            }
            override fun onViewDetachedFromWindow(v: View) = FavoriteRegistry.removeChangeListener(refresh)
        })
        if (button.isAttachedToWindow) FavoriteRegistry.addChangeListener(refresh)
    }

    /** ♥ from the notification or another controller (MediaSession rating). */
    @JvmStatic
    fun rate(context: Context, mediaId: String, isStarring: Boolean) {
        HomeMusic.main.post { setLiked(context, song(mediaId), isStarring) }
    }

    /**
     * The track menu of a catalog track: only what makes sense before it is in the library (queue actions,
     * ♥, «Скачать в библиотеку»). Runs after the menu is set up.
     */
    @JvmStatic
    fun adaptSheet(root: View, media: Child, dismiss: Runnable) {
        if (!isHub(media.id)) return
        for (id in intArrayOf(
            R.id.play_radio_text_view, R.id.rate_text_view, R.id.remove_text_view, R.id.add_to_playlist_text_view,
            R.id.go_to_album_text_view, R.id.go_to_artist_text_view, R.id.share_text_view,
            R.id.remove_from_playlist_text_view, R.id.asset_link_chip_group,
        )) root.findViewById<View>(id)?.visibility = View.GONE
        root.findViewById<View>(R.id.button_favorite)?.setOnClickListener {
            like(it.context, media)
            (it as? CompoundButton)?.isChecked = FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, media.id, media.starred != null)
        }
        root.findViewById<View>(R.id.button_favorite)?.setOnLongClickListener(null)
        root.findViewById<TextView>(R.id.download_text_view)?.apply {
            visibility = View.VISIBLE
            setText(R.string.homemusic_hub_download_track)
            setOnClickListener {
                download(it.context, media)
                dismiss.run()
            }
        }
    }

    // --- streaming --------------------------------------------------------------------------

    /**
     * The cache key of a stream for the streaming cache (`StreamingCacheKeyFactory`), [original] being the key of
     * the file as it is. When saving traffic ([HomeTraffic.economy]) a catalog track and a song of the library are
     * asked from the Hub in the light variant ([HubLight]) and get another key, unless the file as it is lies in
     * the cache whole already: then it plays from there.
     */
    @JvmStatic
    fun variantKey(uri: Uri, original: String): String {
        val eligible = isStreamUri(uri) || HubLight.libraryId(uri.toString()) != null
        if (!eligible) return original
        val fullKey = if (isStreamUri(uri)) HubLight.fullTrackKey(original) else original
        val economy = HomeTraffic.economy()
        val cached = economy && originalCached(fullKey)
        val light = HubLight.useLight(economy, true, cached)
        Log.d(TAG, "variant of ${uri.lastPathSegment}: economy=$economy originalCached=$cached -> light=$light")
        return HubLight.key(fullKey, light)
    }

    /** The file is in the streaming cache whole. */
    private fun originalCached(key: String): Boolean = try {
        val cache = DownloadUtil.getStreamingCacheForPreload(App.getContext())
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(key))
        length != C.LENGTH_UNSET.toLong() && cache.isCached(key, 0, length)
    } catch (e: Exception) {
        false
    }

    // Cache keys of light streams the Hub answered with the original (the copy was not ready), and how many of
    // their streams are open: such an entry must not stay in the cache of the light variant.
    private val badKeys = ConcurrentHashMap<String, Int>()

    private fun badOpened(key: String) {
        badKeys.merge(key, 1, Int::plus)
    }

    private fun badClosed(key: String) {
        badKeys.merge(key, -1, Int::plus)
        HomeMusic.main.postDelayed({ HomeMusic.io.execute { evictIfBad(key) } }, HubLight.EVICT_DELAY_MS)
    }

    private fun evictIfBad(key: String) {
        if ((badKeys[key] ?: 0) > 0) return // another stream of it is open: its close comes later
        badKeys.remove(key)
        try {
            DownloadUtil.getStreamingCacheForPreload(App.getContext()).removeResource(key)
            Log.d(TAG, "dropped the original stored as a light stream: $key")
        } catch (e: Exception) {
            Log.w(TAG, "could not drop $key", e)
        }
    }

    /** Wraps the player's HTTP source: `homemusic-hub:` addresses go to the Hub, the rest to [upstream]. */
    @UnstableApi
    class DataSourceFactory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = HubAwareDataSource(upstream.createDataSource())
    }

    @UnstableApi
    private class HubAwareDataSource(private val other: DataSource) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var current: DataSource? = null
        private var badKey: String? = null

        override fun addTransferListener(transferListener: TransferListener) {
            other.addTransferListener(transferListener)
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            // The cache key says which variant the cache entry is (`variantKey`); with no cache, the network decides.
            val light = HubLight.asked(dataSpec.key, dataSpec.uri.toString(), HomeTraffic.economy())
            Log.d(TAG, "open ${dataSpec.uri.lastPathSegment} key=${dataSpec.key} light=$light at ${dataSpec.position}")
            val catalog = dataSpec.uri.scheme == SCHEME
            val libraryId = if (!catalog && light) HubLight.libraryId(dataSpec.uri.toString()) else null
            if (!catalog && libraryId == null) {
                current = other
                return other.open(dataSpec)
            }
            val hub = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
                .setReadTimeoutMs(READ_TIMEOUT_MS)
                .setAllowCrossProtocolRedirects(true)
                .createDataSource()
            listeners.forEach(hub::addTransferListener)
            current = hub
            val ref = libraryId?.let(HubLight::libraryRef) ?: dataSpec.uri.getQueryParameter("ref").orEmpty()
            return try {
                val length = try {
                    hub.open(resolve(dataSpec, ref, light))
                } catch (e: HttpDataSource.InvalidResponseCodeException) {
                    if (catalog && e.responseCode == 503) {
                        throw IOException(App.getContext().getString(R.string.homemusic_full_track_unavailable), e)
                    }
                    if (e.responseCode != 401) throw e
                    hub.close()
                    HubClient.ensureSession()
                    hub.open(resolve(dataSpec, ref, light))
                }
                if (catalog && hub.responseHeaders.entries.any {
                    it.key.equals("X-HomeMusic-Listen-Kind", ignoreCase = true) && it.value.firstOrNull() == "preview"
                }) throw IOException(App.getContext().getString(R.string.homemusic_full_track_unavailable))
                if (light && HubLight.variantOf(hub.responseHeaders) == HubLight.PENDING) markBad(dataSpec)
                length
            } catch (e: IOException) {
                // The Hub does not give a song Navidrome has: Navidrome's own file (kept in the cache under the key
                // of the light variant for now, `markBad` drops it after).
                if (libraryId == null) { hub.close(); current = null; throw e }
                Log.w(TAG, "the Hub does not give $ref, Navidrome does: $e")
                hub.close()
                current = other
                markBad(dataSpec)
                other.open(dataSpec)
            }
        }

        /** The answer is the original in place of the light copy: its bytes must not stay under the key of the light variant. */
        private fun markBad(dataSpec: DataSpec) {
            val key = dataSpec.key ?: return
            badKey = key
            badOpened(key)
        }

        /** The Hub address in use now and the session cookie for it (both may change while the queue lives). */
        private fun resolve(dataSpec: DataSpec, ref: String, light: Boolean): DataSpec {
            val url = HubLight.streamUrl(HubClient.baseUrl(), ref, light, fullOnly = dataSpec.uri.scheme == SCHEME)
            val headers = HashMap(dataSpec.httpRequestHeaders)
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.let { headers["Cookie"] = it }
            return dataSpec.buildUpon().setUri(url).setHttpRequestHeaders(headers).build()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: throw IllegalStateException("not opened")

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
                badKey?.let { badClosed(it) }
                badKey = null
            }
        }
    }
}
