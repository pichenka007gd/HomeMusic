package com.eddyizm.tempus.homemusic

import com.google.gson.annotations.SerializedName

/**
 * «Моя волна» in the app's player (MIXES.md 7, the contract — hub/API.md «Моя волна»): the Hub hands out 5 tracks
 * at a time and picks again after every action, the player keeps the queue going by itself. Plain JVM code, so the
 * rules are unit-tested without Android; [HubWave] wires them to the player and the network.
 */

/** Supported identifiers for the track and artist actions. */
object WaveSeeds {
    fun track(raw: String): Map<String, String>? {
        val id = raw.trim()
        if (id.startsWith("hub:")) {
            val ref = id.removePrefix("hub:")
            return if ((ref.startsWith("dz:") || ref.startsWith("nd:")) && ref.length > 3) mapOf("ref" to ref) else null
        }
        return if (id.isNotEmpty() && !id.contains(":")) mapOf("nd_song_id" to id) else null
    }

    fun artist(raw: String, artistId: String?): Map<String, String>? {
        val track = track(raw) ?: return null
        if (!raw.startsWith("hub:")) artistId?.trim()?.takeIf { it.isNotEmpty() }?.let {
            return mapOf("nd_artist_id" to it)
        }
        return track + ("by_artist" to "true")
    }
}

/** The `origin` of `POST /api/wave/start` (INSIGHTS.md 6.3) and the `launch_origin` of a wave's events. */
object WaveOrigin {
    const val WAVE = "wave"
    const val HOME_CARD = "home_card"
    const val TRACK_MENU = "track_menu"
    const val ARTIST_MENU = "artist_menu"
    const val PLAYER = "player"
    const val ARTIST_PAGE = "artist_page"
    const val RADIO = "radio"
    const val RESTART = "restart"
}

/** A track of `WaveOut.tracks` / `MixOut.tracks` (`TrackOut` and what the app needs of the rest). */
open class WaveTrack {
    var ref: String? = null
    @SerializedName("nd_song_id") var ndSongId: String? = null
    var owned: Boolean = false
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    @SerializedName("cover_url") var coverUrl: String? = null
    @SerializedName("duration_s") var durationS: Double? = null
    var generator: String? = null

    /** A library song plays from Navidrome, anything else streams from the Hub ([HubTracks]). */
    val songId: String? get() = ndSongId?.takeIf { owned && it.isNotBlank() }

    fun item(context: String?) = HubTracks.Item().also {
        it.songId = songId
        it.ref = ref
        it.title = title
        it.artist = artist
        it.album = album
        it.coverUrl = coverUrl
        it.durationS = durationS
        it.context = context
        if (context?.startsWith(WaveSession.CONTEXT_PREFIX) == true) {
            it.launchId = context
            it.launchOrigin = WaveOrigin.WAVE
        }
    }

    companion object {
        const val SEED = "seed"
    }
}

/** `WaveOut` of `POST /api/wave/start` and `/api/wave/{id}/next`. */
class WaveOut {
    @SerializedName("session_id") var sessionId: String? = null
    var title: String? = null
    var mode: String? = null
    /** `{ref, artist, title}` (a track's wave), `{artist_ref, artist}` (an artist's) or null (the user's own). */
    var seed: Map<String, Any?>? = null
    var tracks: List<WaveTrack>? = null
    var pending: Boolean = false
}

/** `WaveNextIn`. */
class WaveNextIn(val played: List<PlayEvent>, val queued: List<String>) {
    fun compatible(typedFavorites: Boolean, compensationChains: Boolean = typedFavorites, account: String? = null) =
        WaveNextIn(played.filter { it.supported(typedFavorites, compensationChains) && (it.account == null || it.account == account) }.take(500), queued)
}

/**
 * One wave in the player: which queue entries are its tracks, the events not yet given to `next`, whether a call
 * is under way. [onEvent] says when to ask again: on the start of a wave track (the Hub picks after every one), on
 * a ♥ or «Не нравится» of one.
 */
class WaveSession(id: String, val title: String?, val seed: Map<String, Any?>?, val mode: String?) {
    var id: String = id
        private set

    val context: String get() = CONTEXT_PREFIX + id

    // queue entry (a Navidrome song id or hub:<ref>) → the track's ref, as `queued` wants it
    private val refs = HashMap<String, String>()
    private val unsent = ArrayList<PlayEvent>()

    /** A `next` call is under way; [again] — something happened meanwhile, ask once more after it. */
    var inFlight = false
    var again = false

    /** Failed calls in a row, for the retry delay. */
    var failures = 0

    /** The queue entry that played when the call under way was made, and the one the last answer was for. */
    var askedFor: String? = null
    var settledFor: String? = null

    /**
     * The queue ahead of [mediaId] is settled: the Hub has answered since it began, so what the precache loads
     * stays in the queue. A Hub that does not answer (failures) holds nothing back.
     */
    fun settled(mediaId: String?): Boolean = failures > 0 || (mediaId != null && settledFor == mediaId)

    /** Polls of a pool that is still filling (`pending` with nothing to give). */
    var polls = 0

    /**
     * Remembers [tracks] as the wave's; returns the queue ids each may get. A library song can end up streamed
     * from the Hub (Navidrome did not answer), so both ids are the track's.
     */
    fun register(tracks: List<WaveTrack>): List<String> = tracks.flatMap { t ->
        val ref = t.ref?.takeIf { it.isNotBlank() } ?: return@flatMap emptyList()
        listOfNotNull(t.songId, HubTracks.ID_PREFIX + ref).onEach { refs[it] = ref }
    }

    fun isWave(mediaId: String?): Boolean = mediaId != null && refs.containsKey(mediaId)

    fun refOf(mediaId: String): String? = refs[mediaId]

    /** Every id of the wave's tracks so far (their context changes with the session, [renew]). */
    fun ids(): Set<String> = refs.keys.toSet()

    /** An event of the player; true when it is a reason to ask `next` now. */
    fun onEvent(event: PlayEvent): Boolean {
        if (event.context != context) return false
        unsent += event
        return event.kind in ASKING
    }

    /** The body of the next call: the events since the last answer and the refs of the wave's tracks [ahead]. */
    fun request(ahead: List<String>, typedFavorites: Boolean = true, compensationChains: Boolean = typedFavorites): WaveNextIn =
        WaveNextIn(unsent.filter { it.supported(typedFavorites, compensationChains) }.take(MAX_EVENTS),
            ahead.mapNotNull { refs[it] }.distinct().take(MAX_QUEUED))

    /** Capture all events before capability filtering, so held events cannot hide later playback. */
    fun snapshot(ahead: List<String>) = WaveNextIn(unsent.toList(), ahead.mapNotNull { refs[it] }.distinct().take(MAX_QUEUED))

    /** The Hub has the events of [request]; a failed call sends them again with the next one. */
    fun sent(request: WaveNextIn) {
        val done = request.played.mapTo(HashSet()) { it.eventId }
        unsent.removeAll { it.eventId in done }
    }

    /** The session ran out (24 h without a call, `404`): its follower takes over the tracks in the queue. */
    fun renew(newId: String) {
        id = newId
        unsent.clear()
        failures = 0
        polls = 0
    }

    companion object {
        const val CONTEXT_PREFIX = "wave:"
        private val ASKING = setOf(PlayEvent.START, PlayEvent.LIKE, PlayEvent.DISLIKE)
        private const val MAX_EVENTS = 500
        private const val MAX_QUEUED = 50

        /** Asks again, as the contract wants, when this many wave tracks or fewer are left ahead. */
        const val LOW_WATER = 2

        /**
         * The body of `POST /api/wave/start`: a wave like [seed] (also to start it again, [renew]). [origin],
         * [restartOf] and [client] are for a Hub that has `wave_origin_v1`; null leaves them out (an older Hub, or
         * a value its pattern would refuse).
         */
        fun startBody(
            seed: Map<String, Any?>?, mode: String?,
            origin: String? = null, restartOf: String? = null, client: String? = null,
        ): Map<String, Any> {
            val body = HashMap<String, Any>()
            val ref = seed?.get("ref") as? String
            val artist = seed?.get("artist_ref") as? String
            val ndArtist = seed?.get("nd_artist_id") as? String
            when {
                !ref.isNullOrBlank() -> body["seed"] = mapOf("ref" to ref)
                // a wave by a Navidrome artist keeps its library songs
                !ndArtist.isNullOrBlank() -> body["seed"] = mapOf("nd_artist_id" to ndArtist)
                !artist.isNullOrBlank() -> body["seed"] = mapOf("artist_ref" to artist)
            }
            if (!mode.isNullOrBlank()) body["mode"] = mode
            Launch.validOrigin(origin)?.let { body["origin"] = it }
            restartOf?.takeIf { it.isNotBlank() }?.let { body["restart_of"] = it.take(32) }
            client?.takeIf { it.isNotBlank() }?.let { body["client"] = it.take(32) }
            return body
        }
    }
}

/**
 * How an answer goes into the queue: it replaces the wave's tracks ahead. The common head of both stays where it
 * is (the player may be loading the next file already), the rest of the old ones goes, the rest of the answer
 * comes in after the head.
 */
data class WavePlan(val keep: Int, val remove: List<Int>, val add: List<Int>) {
    /**
     * Where the new tracks go once [remove] is done: after the kept head, else where the replaced ones were, else
     * at the end. [positions] — the queue positions of the wave's tracks ahead (the `ahead` of [of]).
     */
    fun insertAt(positions: List<Int>, queueSize: Int): Int = when {
        keep > 0 -> positions[keep - 1] + 1
        positions.isNotEmpty() -> positions.first()
        else -> queueSize
    }

    companion object {
        /** [ahead], [answer] — refs; [remove] — positions in [ahead], [add] — positions in [answer]. */
        fun of(ahead: List<String>, answer: List<String>): WavePlan {
            var keep = 0
            while (keep < ahead.size && keep < answer.size && ahead[keep] == answer[keep]) keep++
            // a track the answer repeats further on is not added twice
            val kept = ahead.take(keep).toSet()
            val add = answer.indices.drop(keep).filter { answer[it] !in kept }.distinctBy { answer[it] }
            return WavePlan(keep, (keep until ahead.size).toList(), add)
        }
    }
}

/** When to ask again after a failed call (offline, the Hub restarting) or an empty answer of a filling pool. */
object WaveRetry {
    private val BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000, 60_000, 120_000, 300_000)
    const val POLL_MS = 4_000L
    private const val MAX_POLLS = 15

    /** Gives up after this many failures in a row (about an hour): the next track start asks again anyway. */
    const val MAX_FAILURES = 16

    fun afterFailure(failures: Int): Long? =
        if (failures > MAX_FAILURES) null else BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.size - 1)]

    /** `pending` with no tracks: soon, a few times, then as after a failure. */
    fun afterPending(polls: Int): Long? = if (polls <= MAX_POLLS) POLL_MS else afterFailure(polls - MAX_POLLS)
}
