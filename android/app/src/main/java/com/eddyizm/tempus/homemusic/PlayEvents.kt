package com.eddyizm.tempus.homemusic

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Listening data for the Hub's mixes and «Моя волна» (MIXES.md 3.2): the events of the app's player, kept on the
 * phone until the Hub has them. Plain JVM code, so the rules are unit-tested without Android; [HubPlays] wires
 * them to the player, the ♥ and the network.
 */

/** One `PlayEventIn` of `POST /api/plays/events`. */
class PlayEvent(
    @SerializedName("event_id") val eventId: String,
    val ts: String,
    val kind: String,
    val ref: String? = null,
    @SerializedName("nd_song_id") val ndSongId: String? = null,
    @SerializedName("position_s") val positionS: Double? = null,
    @SerializedName("duration_s") val durationS: Double? = null,
    val context: String? = null,
    val client: String,
    @SerializedName("target_type") val targetType: String? = null,
    @SerializedName("nd_album_id") val ndAlbumId: String? = null,
    @SerializedName("nd_artist_id") val ndArtistId: String? = null,
    @SerializedName("target_data") val targetData: Map<String, String>? = null,
    @SerializedName("reverts_event_id") val revertsEventId: String? = null,
    // Persisted locally, stripped by HubClient from HTTP bodies.
    @SerializedName("_compensation_chain") val compensationChain: Boolean = false,
    @SerializedName("_account") val account: String? = null,
    @SerializedName("playback_id") val playbackId: String? = null,
    @SerializedName("device_id") val deviceId: String? = null,
    @SerializedName("listened_ms") val listenedMs: Long? = null,
    @SerializedName("track_metadata") val trackMetadata: Map<String, Any>? = null,
    @SerializedName("_rejected") var rejected: String? = null,
    // Where the list came from (INSIGHTS.md 6.5); an old events file has neither.
    @SerializedName("launch_id") val launchId: String? = null,
    @SerializedName("launch_origin") val launchOrigin: String? = null,
) {
    // Missing target_type in persisted events is the original track contract.
    fun supported(typedFavorites: Boolean, compensationChains: Boolean = typedFavorites, checkpoints: Boolean = true): Boolean =
        ((kind != "progress" && kind != "interrupted" && kind != "error") || checkpoints) &&
            (!compensationChain || compensationChains) &&
            (typedFavorites || ((targetType == null || targetType == "track") && revertsEventId == null))

    companion object {
        const val START = "start"
        const val SKIP = "skip"
        const val FINISH = "finish"
        const val LIKE = "like"
        const val UNLIKE = "unlike"
        const val DISLIKE = "dislike"
        const val UNDISLIKE = "undislike"

        // java.time needs API 26; the app runs on 24
        fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

        /** A track of the player: `hub:<ref>` is a catalog track ([HubTracks]), anything else a Navidrome song. */
        fun of(
            kind: String, mediaId: String, client: String, nowMs: Long,
            positionMs: Long? = null, durationMs: Long? = null, context: String? = null,
            playbackId: String? = null, deviceId: String? = null, listenedMs: Long? = null, account: String? = null,
            trackMetadata: Map<String, Any>? = null, launchId: String? = null, launchOrigin: String? = null,
        ): PlayEvent {
            val hub = mediaId.startsWith(HubTracks.ID_PREFIX)
            return PlayEvent(
                eventId = UUID.randomUUID().toString(),
                ts = iso(nowMs),
                kind = kind,
                ref = if (hub) mediaId.removePrefix(HubTracks.ID_PREFIX) else null,
                ndSongId = if (hub) null else mediaId,
                positionS = positionMs?.let { it.coerceAtLeast(0) / 1000.0 },
                durationS = durationMs?.takeIf { it > 0 }?.let { it / 1000.0 },
                context = context,
                client = client, playbackId = playbackId, deviceId = deviceId, listenedMs = listenedMs, account = account, trackMetadata = trackMetadata,
                launchId = launchId, launchOrigin = launchOrigin,
            )
        }

        fun favorite(
            type: String, id: String, starred: Boolean, eventId: String, revertsEventId: String?,
            client: String, nowMs: Long, context: String?, targetData: Map<String, String>? = null,
            compensationChain: Boolean = false,
            account: String? = null, launchId: String? = null, launchOrigin: String? = null,
        ): PlayEvent {
            val catalog = id.startsWith(HubTracks.ID_PREFIX)
            return PlayEvent(
                eventId, iso(nowMs), if (starred) LIKE else UNLIKE,
                ref = if (catalog) id.removePrefix(HubTracks.ID_PREFIX) else null,
                ndSongId = id.takeIf { !catalog && type == "track" },
                ndAlbumId = id.takeIf { !catalog && type == "album" },
                ndArtistId = id.takeIf { !catalog && type == "artist" },
                targetType = type, targetData = targetData, revertsEventId = revertsEventId,
                compensationChain = compensationChain,
                account = account,
                context = context, client = client, launchId = launchId, launchOrigin = launchOrigin,
            )
        }
    }
}

/**
 * Turns what the player does into `start` / `skip` / `finish` of one track at a time. `start` — sound began;
 * leaving the track is `finish` when it played to its end by itself or was left after ≥ 90 % of it, else `skip`.
 * Pause and seeking inside the track are no events, so the position is tracked between calls (an anchor plus
 * the time played since), for the moments the player only knows the next track's position.
 */
class PlayTracker(private val emit: (kind: String, track: Track, positionMs: Long?, nowMs: Long) -> Unit) {
    class Track(val mediaId: String, var durationMs: Long, val context: String?, val metadata: Map<String, Any>? = null, val launch: Launch? = null) {
        var playbackId: String = UUID.randomUUID().toString()
        var listenedMs: Long = 0
    }

    private var open: Track? = null
    private var anchorPosMs = 0L
    private var anchorAtMs = 0L
    private var playing = false

    val openId: String? get() = open?.mediaId

    /** Resume an existing attempt after a process restart, without emitting a second start. */
    fun restore(track: Track, playbackId: String, listenedMs: Long) {
        check(open == null)
        track.playbackId = playbackId
        track.listenedMs = listenedMs.coerceAtLeast(0)
        open = track
        playing = false
    }

    /** Sound is coming from [track] at [positionMs]: starts it, unless it is the one already open. */
    fun playing(track: Track, positionMs: Long, nowMs: Long) {
        val current = open
        if (current != null && current.mediaId != track.mediaId) leave(current.mediaId, null, nowMs, ended = false)
        if (open == null) {
            open = track
            emit(PlayEvent.START, track, positionMs, nowMs)
        } else if (track.durationMs > 0 && open!!.durationMs <= 0) {
            open!!.durationMs = track.durationMs
        }
        anchor(positionMs, nowMs, true)
    }

    /** Paused (or buffering) at [positionMs]. */
    fun paused(positionMs: Long, nowMs: Long) {
        if (open != null) anchor(positionMs, nowMs, false)
    }

    /** A seek inside the open track. */
    fun moved(positionMs: Long, nowMs: Long) {
        if (open != null) anchor(positionMs, nowMs, playing)
    }

    /**
     * The player left [mediaId] at [positionMs] (null: not known any more — estimated from the last anchor);
     * [ended]: it played to its end by itself. Nothing when that track was never started.
     */
    fun leave(mediaId: String?, positionMs: Long?, nowMs: Long, ended: Boolean, reason: String? = null) {
        val track = open ?: return
        val pos = if (positionMs != null && mediaId == track.mediaId) positionMs else position(nowMs)
        accumulate(nowMs)
        val duration = track.durationMs
        val finished = ended || (duration > 0 && pos >= duration * FINISH_SHARE)
        val at = if (ended && duration > 0) duration else pos
        open = null
        playing = false
        emit(reason ?: if (finished) PlayEvent.FINISH else PlayEvent.SKIP, track, at, nowMs)
    }

    /** Where the open track is now, by the last anchor. */
    fun position(nowMs: Long): Long {
        val played = if (playing) (nowMs - anchorAtMs).coerceAtLeast(0) else 0
        val pos = anchorPosMs + played
        val duration = open?.durationMs ?: 0
        return if (duration > 0) pos.coerceAtMost(duration) else pos
    }

    private fun accumulate(nowMs: Long) {
        if (playing) open?.let { it.listenedMs += (nowMs - anchorAtMs).coerceAtLeast(0) }
        anchorAtMs = nowMs
    }

    fun checkpoint(nowMs: Long) {
        val track = open ?: return
        val pos = position(nowMs)
        accumulate(nowMs)
        anchorPosMs = pos
        emit("progress", track, pos, nowMs)
    }

    private fun anchor(positionMs: Long, nowMs: Long, isPlaying: Boolean) {
        accumulate(nowMs)
        anchorPosMs = positionMs.coerceAtLeast(0)
        anchorAtMs = nowMs
        playing = isPlaying
    }

    companion object {
        const val FINISH_SHARE = 0.9
    }
}

/**
 * The events not yet taken by the Hub, oldest first, in a JSON file: they outlive the process and offline days.
 * Pending events remain until the Hub acknowledges them.
 */
class PlayEventStore(private val file: File) {
    private val gson = Gson()
    private var events: MutableList<PlayEvent>? = null

    @Synchronized
    fun add(event: PlayEvent) {
        val list = load().toMutableList()
        if (list.any { it.eventId == event.eventId }) return
        if (event.kind == "progress" && event.playbackId != null && event.deviceId != null && event.listenedMs != null) {
            list.removeAll { old -> old.kind == "progress" && old.playbackId == event.playbackId &&
                old.deviceId == event.deviceId && old.account == event.account && old.ts.take(15) == event.ts.take(15) &&
                old.listenedMs != null && old.listenedMs <= event.listenedMs }
        }
        list += event
        save(list)
        events = list
    }

    @Synchronized
    fun head(n: Int): List<PlayEvent> = load().take(n)

    @Synchronized
    fun compatibleHead(n: Int, typedFavorites: Boolean, compensationChains: Boolean = typedFavorites,
                       account: String? = null, checkpoints: Boolean = true): List<PlayEvent> =
        load().asSequence().filter { it.rejected == null && it.supported(typedFavorites, compensationChains, checkpoints) && (it.account == null || it.account == account) }.take(n).toList()

    @Synchronized
    fun size(): Int = load().size

    @Synchronized
    fun remove(sent: Collection<PlayEvent>) {
        val ids = sent.mapTo(HashSet()) { it.eventId }
        val list = load().toMutableList()
        if (list.removeAll { it.eventId in ids }) {
            save(list)
            events = list
        }
    }

    @Synchronized
    fun quarantine(reasons: Map<String, String>) {
        val list = load()
        val changed = list.filter { reasons.containsKey(it.eventId) }
        val previous = changed.map { it.rejected }
        changed.forEach { it.rejected = reasons[it.eventId] }
        try { save(list) } catch (e: Exception) {
            changed.forEachIndexed { index, event -> event.rejected = previous[index] }
            throw e
        }
    }

    private fun load(): MutableList<PlayEvent> {
        events?.let { return it }
        val backup = File(file.path + ".bak")
        if (!file.exists() && backup.exists()) check(backup.renameTo(file))
        val loaded = if (!file.exists()) mutableListOf() else {
            val parsed: List<PlayEvent>? = gson.fromJson(file.readText(), object : TypeToken<List<PlayEvent>>() {}.type)
            requireNotNull(parsed) { "Listening outbox is not a JSON array" }
            parsed.toMutableList()
        }
        events = loaded
        return loaded
    }

    private fun save(list: List<PlayEvent>) {
        file.parentFile?.let { check(it.exists() || it.mkdirs()) }
        val tmp = File(file.path + ".tmp")
        tmp.outputStream().use { out ->
            out.write(gson.toJson(list).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (tmp.renameTo(file)) return
        val backup = File(file.path + ".bak")
        if (backup.exists()) check(backup.delete())
        val hadFile = file.exists()
        if (hadFile) check(file.renameTo(backup))
        if (!tmp.renameTo(file)) {
            if (hadFile) check(backup.renameTo(file))
            throw java.io.IOException("Could not replace listening outbox")
        }
        if (hadFile) check(backup.delete())
    }

}

/** Keeps events in memory until both the outbox and the open-attempt snapshot have been written. */
class DurableEventWriter(private val write: (PlayEvent) -> Unit) {
    private val pending = ArrayDeque<PlayEvent>()

    fun enqueue(event: PlayEvent) { pending.addLast(event) }

    fun drain(): Boolean {
        while (pending.isNotEmpty()) {
            try { write(pending.first()) } catch (_: Exception) { return false }
            pending.removeFirst()
        }
        return true
    }
}

/**
 * Sends the stored events in batches of up to [batch] (the Hub takes ≤ 500). Rejected individual events remain
 * quarantined with a reason. A batch refused as a
 * whole stays for the next try, later each time it fails. A Hub without the
 * endpoint (404, older than the mixes) is asked again only rarely.
 */
class PlaySender(
    private val store: PlayEventStore,
    private val send: (List<PlayEvent>) -> Outcome,
    private val batch: Int = 500,
    private val typedFavorites: () -> Boolean = { true },
    private val compensationChains: () -> Boolean = typedFavorites,
    private val account: () -> String? = { null },
    private val checkpoints: () -> Boolean = { true },
) {
    enum class Outcome { SENT, PARTIAL, REFUSED, TOO_LARGE, RETRY, NO_ENDPOINT }

    private var failures = 0

    /** Sends what there is; returns the delay before the next try, or null when nothing is left. */
    @Synchronized
    fun flush(): Long? {
        val typed = typedFavorites()
        val chains = compensationChains()
        val checkpointSupport = checkpoints()
        var batchSize = batch
        while (true) {
            val events = store.compatibleHead(batchSize, typed, chains, account(), checkpointSupport)
            if (events.isEmpty()) {
                failures = 0
                return if (store.size() > 0) CAPABILITY_RETRY_MS else null
            }
            when (send(events)) {
                Outcome.SENT -> {
                    failures = 0
                    store.remove(events)
                }
                Outcome.PARTIAL -> failures = 0
                Outcome.TOO_LARGE -> {
                    if (events.size <= 1) {
                        store.quarantine(mapOf(events.single().eventId to "Server rejected single event as too large (413)"))
                    } else batchSize = (events.size / 2).coerceAtLeast(1)
                }
                Outcome.RETRY, Outcome.REFUSED -> return retryDelay(++failures)
                Outcome.NO_ENDPOINT -> {
                    failures++
                    return NO_ENDPOINT_DELAY_MS
                }
            }
        }
    }

    companion object {
        const val FIRST_RETRY_MS = 30_000L
        const val MAX_RETRY_MS = 30 * 60_000L
        const val NO_ENDPOINT_DELAY_MS = 6 * 60 * 60_000L
        const val CAPABILITY_RETRY_MS = 5 * 60_000L

        fun retryDelay(failures: Int): Long =
            (FIRST_RETRY_MS shl (failures - 1).coerceIn(0, 10)).coerceAtMost(MAX_RETRY_MS)
    }
}
