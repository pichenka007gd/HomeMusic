package com.eddyizm.tempus.homemusic

import com.google.gson.annotations.SerializedName

/**
 * «Умное перемешивание» in the app's player (MIXES.md 8, the contract — hub/API.md «Умное перемешивание»): a playlist
 * or «Понравившиеся» plays in the Hub's order with recommendations between its songs (one after every 3 own songs, 2 in
 * a list of 15 or fewer). The Hub keeps all the logic; this file has the app's rules — the button's three states, the
 * queue plans, the session's bookkeeping — as plain JVM code, unit-tested without Android; [HubSmart] wires them to the
 * player and the network.
 */

/** The shuffle button: off → shuffle → smart shuffle (only where the playing list can have one) → off. */
enum class ShuffleMode {
    OFF, SHUFFLE, SMART;

    fun next(smartOffered: Boolean): ShuffleMode = when (this) {
        OFF -> SHUFFLE
        SHUFFLE -> if (smartOffered) SMART else OFF
        SMART -> OFF
    }

    companion object {
        /** In the smart shuffle Media3's own shuffle is off: the Hub sets the order. */
        fun of(playerShuffle: Boolean, smartActive: Boolean): ShuffleMode =
            if (smartActive) SMART else if (playerShuffle) SHUFFLE else OFF
    }
}

/** The ids and contexts of the smart shuffle. */
object SmartContexts {
    const val LIKED = "liked"

    /** The context of the events of a recommendation of session [id] (`smart:<id>`). */
    fun recommendation(id: String) = "smart:$id"

    /** The Hub does it for a Navidrome playlist and for «Понравившиеся»; anything else plays as before. */
    fun offered(context: String?): Boolean =
        context == LIKED || (context != null && context.startsWith("playlist:") && context.length in 10..73 && !context.any { it.isWhitespace() })

    /** The list a queue was started from: the context most of its entries share, when the smart shuffle is offered for it. */
    fun dominant(contexts: List<String?>): String? {
        if (contexts.isEmpty()) return null
        val top = contexts.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return null
        return top.key.takeIf { top.value * 2 > contexts.size && offered(it) }
    }

    /** The id the Hub knows a queue entry by: a Navidrome song id, or the catalog ref of a track it streams. */
    fun hubId(mediaId: String): String = mediaId.removePrefix(HubTracks.ID_PREFIX)

    /** The queue id of an entry the Hub named: a catalog ref (it has a colon) streams from the Hub. */
    fun mediaId(hubId: String): String = if (':' in hubId) HubTracks.ID_PREFIX + hubId else hubId
}

/** A recommendation of `SmartOut.recs`: the wave's track and the own song it goes after. */
class SmartRec : WaveTrack() {
    /** The id (as the Hub knows it, [SmartContexts.hubId]) of the own song it goes right after. */
    var after: String? = null
    var slot: Int = 0

    /** The queue entry: the library song, else the track the Hub streams ([WaveTrack.register] has both). */
    val queueId: String? get() = songId ?: ref?.takeIf { it.isNotBlank() }?.let { HubTracks.ID_PREFIX + it }
}

/** `SmartOut` of `POST /api/smart-shuffle/start` and `/{id}/next`. */
class SmartOut {
    @SerializedName("session_id") var sessionId: String? = null
    var context: String? = null
    var title: String? = null
    /** The own songs in the order to play (start only), as the ids the app sent. */
    var own: List<String>? = null
    var recs: List<SmartRec>? = null
    var pending: Boolean = false
}

/** `SmartAddOut` of `POST /api/smart-shuffle/{id}/add`. */
class SmartAddOut {
    var status: String? = null
    @SerializedName("playlist_id") var playlistId: String? = null
}

/** `SmartNextIn`: the events of the session's recommendations since the last call, and the own song the player is at. */
class SmartNextIn(val played: List<PlayEvent>, val at: String?) {
    fun compatible(typedFavorites: Boolean, compensationChains: Boolean = typedFavorites, account: String? = null) =
        SmartNextIn(played.filter { it.supported(typedFavorites, compensationChains) && (it.account == null || it.account == account) }.take(500), at)
}

/** A queue entry as the plans see it: its queue id, and whether it is a recommendation. */
data class Entry(val mediaId: String, val rec: Boolean = false)

/** One place of a plan: an own song (by its queue id) or a recommendation. */
class Slot(val mediaId: String, val rec: SmartRec? = null)

object SmartPlan {
    /** A queue the page starts is no longer than the plain shuffle of «Понравившиеся» makes (a huge one is slow to map). */
    const val MAX_QUEUE = 500

    /**
     * The order of what plays after [current] for a smart shuffle just answered: the own songs [ahead] (queue ids) in
     * the Hub's [own] order, each recommendation right after the own song it names. A song the Hub does not know (the
     * user queued it meanwhile) goes at the end, in the queue's order; a song of [own] that is not [ahead] (played or
     * taken out) is not queued again. [current] — the own song playing now (a recommendation after it comes first).
     */
    fun build(ahead: List<String>, own: List<String>, recs: List<SmartRec>, current: String? = null): List<Slot> {
        val wanted = ahead.toSet()
        val byAfter = recs.sortedBy { it.slot }.groupBy { it.after.orEmpty() }
        val out = ArrayList<Slot>()
        val placed = HashSet<String>()
        fun recsAfter(hub: String) = byAfter[hub].orEmpty().forEach { r -> r.queueId?.takeIf { placed.add(it) }?.let { out += Slot(it, r) } }
        current?.let { recsAfter(SmartContexts.hubId(it)) }
        val known = HashSet<String>()
        for (id in own) {
            val media = SmartContexts.mediaId(id)
            known += media
            if (media in wanted && placed.add(media)) out += Slot(media)
            if (media in wanted || media == current) recsAfter(id)
        }
        for (media in ahead) if (media !in known && placed.add(media)) out += Slot(media)
        return out
    }

    /**
     * Where new recommendations go in a queue: each right after the own song it names, if that song is [current] or
     * still ahead of it; one already in the queue is not added again. Returns the queue index of each and the
     * recommendation, in the order the inserts are to be made (every index counts the ones made before it).
     */
    fun insertions(queue: List<Entry>, current: Int, recs: List<SmartRec>): List<Pair<Int, SmartRec>> {
        val list = queue.toMutableList()
        val out = ArrayList<Pair<Int, SmartRec>>()
        for (r in recs.sortedBy { it.slot }) {
            val id = r.queueId ?: continue
            val after = r.after?.let(SmartContexts::mediaId) ?: continue
            if (list.any { it.mediaId == id }) continue
            val at = list.indexOfFirst { !it.rec && it.mediaId == after }
            if (at < 0 || at < current) continue
            list.add(at + 1, Entry(id, true))
            out += (at + 1) to r
        }
        return out
    }

    /**
     * The song of the list the player is at, or passed last: the nearest entry at or before [current] that is not a
     * recommendation and that the Hub knows ([listed]). A song the user queued or a recommendation taken in with «+»
     * is not the Hub's, so the walk goes on to the list's song before it.
     */
    fun anchor(queue: List<Entry>, current: Int, listed: (String) -> Boolean): String? {
        for (i in current.coerceAtMost(queue.size - 1) downTo 0) {
            val entry = queue[i]
            if (!entry.rec && listed(entry.mediaId)) return entry.mediaId
        }
        return null
    }

    /**
     * Leaving the smart shuffle: the own songs [ahead] of the player (queue ids; recommendations are left out) in the
     * list's [original] order, from the song that follows [anchor] (the own song playing, or the last one that played
     * before the recommendation that plays) on, then — round — the ones before it; what the user added to the queue
     * stays after them, in the queue's order.
     */
    fun restore(original: List<String>, ahead: List<Entry>, anchor: String?): List<String> {
        val left = ahead.filter { !it.rec }.map { it.mediaId }
        val leftSet = left.toSet()
        val originalMedia = original.map(SmartContexts::mediaId)
        val from = anchor?.let { originalMedia.indexOf(it) } ?: -1
        val ordered = (originalMedia.drop(from + 1) + originalMedia.take(from + 1)).filter { it in leftSet }
        val orderedSet = ordered.toSet()
        return ordered + left.filter { it !in orderedSet }
    }
}

/**
 * One smart shuffle in the player: which queue entries are its recommendations, the events not yet given to `next`,
 * whether a call is under way. [onEvent] says when to ask again: on the start of any entry of the list (the Hub fills
 * the places ahead of the player), and on what the user does to a recommendation.
 */
class SmartSession(id: String, val context: String, val title: String?, val original: List<String>) {
    var id: String = id
        private set

    /** What the events of the recommendations carry. */
    val recContext: String get() = SmartContexts.recommendation(id)

    // queue entry of a recommendation → its catalog ref
    private val recs = HashMap<String, String>()
    private val listed = HashSet<String>().apply { original.forEach { add(SmartContexts.mediaId(it)) } }
    private val ownIds = HashSet<String>(listed)
    private val unsent = ArrayList<PlayEvent>()

    var inFlight = false
    var again = false
    var failures = 0

    /** Polls of a pool that is still filling (`pending` with nothing to give). */
    var polls = 0

    /** Remembers [list] as the session's recommendations; returns their queue ids. */
    fun register(list: List<SmartRec>): List<String> = list.mapNotNull { r ->
        val ref = r.ref?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val queueId = r.queueId ?: return@mapNotNull null
        recs[queueId] = ref
        // a library song can end up streamed from the Hub (Navidrome did not answer): both ids are the track's
        recs[HubTracks.ID_PREFIX + ref] = ref
        queueId
    }

    fun isRec(mediaId: String?): Boolean = mediaId != null && recs.containsKey(mediaId)

    /** A song of the list as the Hub got it at the start (not a recommendation taken in later, not what the user queued). */
    fun isListed(mediaId: String?): Boolean = mediaId != null && mediaId in listed

    fun isOwn(mediaId: String?): Boolean = mediaId != null && mediaId in ownIds

    fun recIds(): Set<String> = recs.keys.toSet()

    fun refOf(mediaId: String): String? = recs[mediaId]

    /** «+»: the recommendation is the list's own song from now on. */
    fun accept(mediaId: String) {
        val ref = recs[mediaId] ?: return
        recs.remove(mediaId)
        recs.remove(HubTracks.ID_PREFIX + ref)
        ownIds += mediaId
    }

    /** An event of the player; true when it is a reason to ask `next` now. */
    fun onEvent(event: PlayEvent): Boolean {
        val rec = event.context == recContext
        if (!rec && event.context != context) return false
        if (rec) unsent += event
        return event.kind == PlayEvent.START ||
            (rec && event.kind in REACTIONS)
    }

    fun snapshot(at: String?) = SmartNextIn(unsent.toList(), at)

    /** The Hub has the events of [request]; a failed call sends them again with the next one. */
    fun sent(request: SmartNextIn) {
        val done = request.played.mapTo(HashSet()) { it.eventId }
        unsent.removeAll { it.eventId in done }
    }

    /** The Hub forgot the session (24 h without a call): the queue plays out, it brings no more. */
    fun expire() {
        unsent.clear()
    }

    companion object {
        private val REACTIONS = setOf(PlayEvent.SKIP, PlayEvent.FINISH, PlayEvent.LIKE, PlayEvent.DISLIKE)

        /** Asks again, as the Hub's answer wants, when this many recommendations or fewer are left ahead. */
        const val LOW_WATER = 1

        /** The body of `POST /api/smart-shuffle/start`; null values are left out, an origin the Hub would refuse too. */
        fun startBody(context: String, songs: List<String>, current: String?, title: String?, origin: String?, client: String?): Map<String, Any> {
            val body = HashMap<String, Any>()
            body["context"] = context
            body["songs"] = songs.take(MAX_SONGS)
            current?.takeIf { it.isNotBlank() }?.let { body["current"] = it.take(80) }
            title?.takeIf { it.isNotBlank() }?.let { body["title"] = it.take(256) }
            Launch.validOrigin(origin)?.let { body["origin"] = it }
            client?.takeIf { it.isNotBlank() }?.let { body["client"] = it.take(32) }
            return body
        }

        const val MAX_SONGS = 5000
    }
}
