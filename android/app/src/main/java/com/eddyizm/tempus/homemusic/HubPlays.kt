package com.eddyizm.tempus.homemusic

import android.os.SystemClock
import android.os.Handler
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.FavoriteRegistry
import java.io.File
import java.io.IOException
import java.util.WeakHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The app's part of the Hub's listening data (MIXES.md 3.2): `start` / `skip` / `finish` of every music track of
 * the player (Navidrome songs and catalog tracks of the Hub), ♥ as `like` / `unlike`. Events wait on the phone
 * ([PlayEventStore]) and go to `POST /api/plays/events` in batches a few seconds after the last one, again
 * later when that fails, and from [HubSyncWorker] every 15 minutes.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubPlays {
    private const val TAG = "HubPlays"
    private const val FLUSH_DELAY_MS = 5_000L
    const val OTHER = "other"

    @JvmStatic
    val client: String get() = "app/${BuildConfig.VERSION_NAME}".take(32)

    // App.getInstance() may be a bare App without a base context; getContext() is the application
    private val store by lazy { PlayEventStore(File(App.getContext().filesDir, "homemusic_play_events.json")) }
    private val sender by lazy { PlaySender(store, ::post, typedFavorites = HubClient::supportsTypedFavorites,
        compensationChains = HubClient::supportsCompensationChains, account = CatalogFavorites::currentAccount,
        checkpoints = HubClient::supportsListeningCheckpoints) }

    // one thread: the file and the sending keep their order
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var next: ScheduledFuture<*>? = null
    private var persistRetry: ScheduledFuture<*>? = null
    private val durableWriter by lazy { DurableEventWriter { event -> store.add(event); persistOpen(event) } }

    @Volatile
    private var initialized = false

    /** Hooks the ♥ and sends what is left from before; safe to call many times. */
    @JvmStatic
    fun init() {
        if (!HubConfig.isEnabled() || initialized) return
        initialized = true
        FavoriteRegistry.onDecision = FavoriteRegistry.DecisionListener { kind, id, starred, eventId, reverts, chain ->
            val type = when (kind) {
                FavoriteRegistry.Kind.SONG -> "track"
                FavoriteRegistry.Kind.ALBUM -> "album"
                FavoriteRegistry.Kind.ARTIST -> "artist"
            }
            val account = if (kind == FavoriteRegistry.Kind.SONG && HubTracks.isHub(id)) CatalogFavorites.currentAccount() ?: return@DecisionListener else null
            record(PlayEvent.favorite(type, id, starred, eventId, reverts, client, System.currentTimeMillis(),
                if (kind == FavoriteRegistry.Kind.SONG) contextOf(id) else type, compensationChain = chain, account = account,
                launchId = if (kind == FavoriteRegistry.Kind.SONG) launchOf(id)?.id else null,
                launchOrigin = if (kind == FavoriteRegistry.Kind.SONG) launchOf(id)?.origin else null))
        }
        loadRecoveries()
        worker.execute { schedule(0) }
    }

    @Volatile private var lastFavoritesAt = Long.MIN_VALUE
    @Volatile private var lastFavoritesSession = Long.MIN_VALUE

    @JvmStatic
    fun refreshFavorites() {
        if (!HubConfig.isEnabled()) return
        val session = FavoriteRegistry.generation()
        worker.execute {
            val now = SystemClock.elapsedRealtime()
            if (lastFavoritesSession == session && lastFavoritesAt != Long.MIN_VALUE && now - lastFavoritesAt < 30_000L) return@execute
            runCatching {
                val snapshot = HubClient.favorites()
                FavoriteRegistry.snapshot(snapshot.states(), session)
                lastFavoritesAt = now
                lastFavoritesSession = session
            }.onFailure { Log.d(TAG, "favorite snapshot unavailable: ${it.javaClass.simpleName}") }
        }
    }

    @JvmStatic
    fun record(event: PlayEvent) {
        if (!HubConfig.isEnabled()) return
        if (event.kind !in setOf("progress", "interrupted", "error")) {
            HubWave.onEvent(event)
            HubSmart.onEvent(event)
        }
        worker.execute {
            durableWriter.enqueue(event)
            persistPending()
        }
    }

    private fun persistPending() {
        persistRetry?.cancel(false)
        if (!durableWriter.drain()) {
            Log.e(TAG, "Could not persist listening event; retrying")
            persistRetry = worker.schedule({ persistPending() }, 30, TimeUnit.SECONDS)
        } else {
            persistRetry = null
            schedule(FLUSH_DELAY_MS)
        }
    }

    private val deviceId: String by lazy {
        val prefs = App.getContext().getSharedPreferences("homemusic-listening", 0)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(prefs.edit().putString("device_id", it).commit())
        }
    }

    private fun openDir(): File = File(App.getContext().filesDir, "homemusic-open-plays")

    private fun persistOpen(event: PlayEvent) {
        val id = event.playbackId ?: return
        val dir = openDir()
        check(dir.exists() || dir.mkdirs())
        val file = File(dir, "$id.json")
        if (event.kind == "start" || event.kind == "progress") {
            val tmp = File(dir, "$id.tmp")
            tmp.writeText(HubClient.gson.toJson(event))
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        } else if (event.kind in setOf("skip", "finish", "interrupted", "error")) {
            check(file.delete() || !file.exists())
        }
    }

    private val recoveries = mutableListOf<Pair<File, PlayEvent>>()

    private fun loadRecoveries() {
        for (file in openDir().listFiles().orEmpty().filter { it.extension == "json" }) {
            try {
                val old = HubClient.gson.fromJson(file.readText(), PlayEvent::class.java)
                if (old.playbackId != null && old.deviceId != null && old.listenedMs != null) recoveries += file to old
            } catch (e: Exception) { Log.w(TAG, "read listening checkpoint", e) }
        }
    }

    private fun recoveryMediaId(old: PlayEvent): String? =
        old.ref?.let { "${HubTracks.ID_PREFIX}$it" } ?: old.ndSongId

    private fun finishInterrupted(old: PlayEvent) {
        val mediaId = recoveryMediaId(old) ?: return
        record(PlayEvent.of("interrupted", mediaId, client, System.currentTimeMillis(),
            old.positionS?.let { (it * 1000).toLong() }, old.durationS?.let { (it * 1000).toLong() },
            old.context, old.playbackId, old.deviceId, old.listenedMs, old.account, old.trackMetadata,
            old.launchId, old.launchOrigin))
    }

    @Synchronized
    private fun closeOtherRecoveries(account: String?, keepMediaId: String?) {
        val stale = recoveries.filter { (_, event) ->
            event.account == account && (keepMediaId == null || recoveryMediaId(event) != keepMediaId)
        }
        recoveries.removeAll(stale.toSet())
        stale.forEach { (_, event) -> finishInterrupted(event) }
    }

    @Synchronized
    private fun takeRecovery(mediaId: String, account: String?): Pair<File, PlayEvent>? {
        val found = recoveries.firstOrNull { (_, event) ->
            recoveryMediaId(event) == mediaId && event.account == account && event.deviceId == deviceId
        }
        if (found != null) recoveries.remove(found)
        return found
    }

    /** Sends everything now (the background worker). */
    @JvmStatic
    @WorkerThread
    fun flushNow() {
        if (!HubConfig.isEnabled()) return
        worker.submit { flush() }.get()
    }

    private fun schedule(delayMs: Long) {
        next?.cancel(false)
        next = worker.schedule({ flush() }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun flush() {
        val delay = try {
            sender.flush()
        } catch (e: Exception) {
            Log.w(TAG, "flush", e)
            PlaySender.FIRST_RETRY_MS
        }
        if (delay != null) schedule(delay)
    }

    private fun post(events: List<PlayEvent>): PlaySender.Outcome = try {
        val accounts = events.mapNotNull { it.account }.distinct()
        val result = if (accounts.isEmpty()) HubClient.playEvents(events) else
            HubClient.withAccountSession(accounts.single()) { HubClient.playEvents(events) }
        // the batch goes either way (the Hub keeps the rest); a rejection is a bug of this code
        if (!result.rejected.isNullOrEmpty()) {
            val reasons = result.rejected!!.associate { rejection ->
                val index = (rejection["index"] as? Number)?.toInt()
                    ?: throw IOException("Hub returned invalid rejection index")
                val event = events.getOrNull(index) ?: throw IOException("Hub returned out of range rejection index")
                event.eventId to (rejection["detail"]?.toString() ?: "Hub rejected event")
            }
            Log.w(TAG, "Hub rejected ${reasons.size} of ${events.size}; quarantined")
            store.quarantine(reasons)
            store.remove(events.filter { it.eventId !in reasons })
            PlaySender.Outcome.PARTIAL
        } else PlaySender.Outcome.SENT
    } catch (e: HubException) {
        when (e.code) {
            404 -> PlaySender.Outcome.NO_ENDPOINT
            413 -> { Log.w(TAG, "play event batch too large; splitting or retaining single event"); PlaySender.Outcome.TOO_LARGE }
            // signed out (the sign-in again failed), throttled, or not a refusal of the batch at all
            0, 401, 403, 408, 429 -> PlaySender.Outcome.RETRY
            in 400..499 -> PlaySender.Outcome.REFUSED
            else -> PlaySender.Outcome.RETRY
        }
    } catch (e: IOException) {
        HubClient.invalidate()
        PlaySender.Outcome.RETRY
    }

    // --- where a queue came from --------------------------------------------------------------

    @Volatile
    private var expected: Pair<List<Child>, Map<String, String>>? = null

    @Volatile
    private var contexts: Map<String, String> = emptyMap()

    @Volatile
    private var launches: Map<String, Launch> = emptyMap()

    private var expectedLaunches: Map<String, Launch> = emptyMap()

    /** [HubPlayer] is about to start [queue]; [byId] — what the page said each track was played from. */
    fun expectQueue(queue: List<Child>, byId: Map<String, String>, launchById: Map<String, Launch> = emptyMap()) {
        expected = queue to byId
        expectedLaunches = launchById
    }

    /** A new native queue is a new launch; one that goes on with a mix or a wave keeps that list's launch. */
    @JvmStatic
    fun expectNativeQueue(queue: List<Child>, context: String) {
        val fresh = Launch.fresh(NativeContexts.originOf(context))
        expectQueue(queue, queue.associate { it.id to NativeContexts.queue(context, contexts[it.id]) },
            queue.associate { it.id to NativeContexts.launch(context, contexts[it.id], launches[it.id], fresh) })
    }

    @JvmStatic
    fun continuous(queue: List<Child>) = addContexts(queue.associate { it.id to "continuous" })

    @JvmStatic
    fun queued(queue: List<Child>) = addContexts(queue.associate { it.id to "queue" })

    /** Every new queue of the app (MediaManager.startQueue): the page's contexts hold for its own queue only. */
    @JvmStatic
    fun onQueueStarted(media: List<Child>?) {
        val e = expected
        expected = null
        val own = e != null && e.first === media
        contexts = if (own) e!!.second else emptyMap()
        launches = if (own) expectedLaunches else emptyMap()
        expectedLaunches = emptyMap()
        HubWave.onQueueStarted(contexts)
        HubSmart.onQueueStarted(contexts)
    }

    /** Tracks added to the queue that is playing ([HubWave]); [byId] as in [expectQueue]. */
    fun addContexts(byId: Map<String, String>, launchById: Map<String, Launch> = emptyMap()) {
        contexts = contexts + byId
        if (launchById.isNotEmpty()) launches = launches + launchById
    }

    fun contextOf(mediaId: String): String = contexts[mediaId] ?: OTHER

    fun launchOf(mediaId: String): Launch? = launches[mediaId]

    // --- the player -------------------------------------------------------------------------

    private val listeners = WeakHashMap<Player, Listener>()

    /** Called for each player the service makes (the phone's, a UPnP renderer). */
    @JvmStatic
    fun attach(player: Player) {
        if (!HubConfig.isEnabled()) return
        init()
        if (listeners.containsKey(player)) return
        val listener = Listener(player)
        listeners[player] = listener
        player.addListener(listener)
    }

    /** The service goes away: the open track is left where it is. */
    @JvmStatic
    fun release(player: Player) {
        listeners.remove(player)?.let {
            player.removeListener(it)
            it.close()
        }
    }

    private class Listener(private val player: Player) : Player.Listener {
        private val accounts = mutableMapOf<String, String?>()
        private val tracker = PlayTracker { kind, track, positionMs, _ ->
            if (kind == "start") accounts[track.playbackId] = CatalogFavorites.currentAccount()
            record(PlayEvent.of(kind, track.mediaId, client, System.currentTimeMillis(), positionMs, track.durationMs,
                track.context, track.playbackId, deviceId, track.listenedMs, accounts[track.playbackId], track.metadata,
                track.launch?.id, track.launch?.origin))
            if (kind in setOf("skip", "finish", "interrupted", "error")) accounts.remove(track.playbackId)
        }
        private val handler = Handler(player.applicationLooper)
        private val checkpoint = object : Runnable {
            override fun run() {
                tracker.checkpoint(now())
                handler.postDelayed(this, 15_000L)
            }
        }
        private val closeAbandoned = Runnable {
            val current = track(player.currentMediaItem)
            closeOtherRecoveries(CatalogFavorites.currentAccount(), current?.mediaId)
        }
        init {
            handler.postDelayed(checkpoint, 15_000L)
            handler.postDelayed(closeAbandoned, 15_000L)
        }

        private fun now() = SystemClock.elapsedRealtime()

        private fun track(item: MediaItem?): PlayTracker.Track? {
            item ?: return null
            val extras = item.mediaMetadata.extras
            if (extras?.getString("type") != Constants.MEDIA_TYPE_MUSIC || item.mediaId.isEmpty()) return null
            val known = if (item == player.currentMediaItem) player.duration else C.TIME_UNSET
            val duration = if (known != C.TIME_UNSET && known > 0) known else extras.getInt("duration") * 1000L
            val metadata = buildMap<String, Any> {
                item.mediaMetadata.title?.toString()?.takeIf { it.isNotBlank() }?.let { put("title", it) }
                item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }?.let { artist ->
                    put("artist_credit", artist)
                }
                extras.getString("artistId")?.takeIf { it.isNotBlank() }?.let { put("primary_nd_artist_id", it) }
                extras.getInt("year").takeIf { it in 1000..9999 }?.let { put("year", it) }
                extras.getString("genre")?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
                item.mediaMetadata.albumTitle?.toString()?.takeIf { it.isNotBlank() }?.let { put("album", it) }
                if (duration > 0) put("duration_s", duration / 1000.0)
            }
            return PlayTracker.Track(item.mediaId, duration, contextOf(item.mediaId), metadata, launchOf(item.mediaId))
        }

        private fun currentIsOpen() = player.currentMediaItem?.mediaId == tracker.openId

        private fun syncPlaying() {
            if (!player.isPlaying) return
            val t = track(player.currentMediaItem)
            if (t != null && tracker.openId == null) {
                val position = player.currentPosition
                val account = CatalogFavorites.currentAccount()
                val matching = takeRecovery(t.mediaId, account)
                closeOtherRecoveries(account, t.mediaId)
                if (matching != null) {
                    val (_, old) = matching
                    val oldPosition = old.positionS?.let { (it * 1000).toLong() }
                    if (oldPosition != null && position > 0 && kotlin.math.abs(position - oldPosition) <= 15_000L) {
                        tracker.restore(t, old.playbackId!!, old.listenedMs!!)
                        accounts[old.playbackId] = old.account
                    } else finishInterrupted(old)
                }
            }
            if (t != null) tracker.playing(t, player.currentPosition, now())
            else tracker.leave(null, null, now(), ended = false) // radio, a podcast
        }

        fun close() {
            handler.removeCallbacks(checkpoint)
            handler.removeCallbacks(closeAbandoned)
            closeOtherRecoveries(CatalogFavorites.currentAccount(), null)
            tracker.leave(player.currentMediaItem?.mediaId, player.currentPosition, now(), ended = false, reason = "interrupted")
        }

        override fun onPlayerError(error: PlaybackException) {
            tracker.leave(player.currentMediaItem?.mediaId, player.currentPosition, now(), ended = false, reason = "error")
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) syncPlaying()
            else if (currentIsOpen()) tracker.paused(player.currentPosition, now())
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            val oldId = oldPosition.mediaItem?.mediaId
            val auto = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
            val changed = auto || oldPosition.mediaItemIndex != newPosition.mediaItemIndex || oldId != newPosition.mediaItem?.mediaId
            if (!changed) {
                if (currentIsOpen()) tracker.moved(newPosition.positionMs, now())
                return
            }
            tracker.leave(oldId, oldPosition.positionMs, now(), ended = auto)
            syncPlaying()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // a new queue (setMediaItems) or the queue cleared, which no discontinuity reports
            val open = tracker.openId
            if (open != null && mediaItem?.mediaId != open) tracker.leave(open, null, now(), ended = false)
            syncPlaying()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                tracker.leave(tracker.openId, null, now(), ended = true)
                HubWave.onQueueEnded()
            }
        }
    }
}
