package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.util.Constants
import java.util.UUID

/**
 * Where a list was launched from, for the Hub's statistics (INSIGHTS.md 6.5): [id] is new for every queue the app
 * starts and the same for all its events; [origin] is a word of the Hub's `launch_origin` vocabulary.
 * Values the Hub would refuse (it rejects the whole event) are dropped by [of].
 */
class Launch private constructor(val id: String, val origin: String?) {
    companion object {
        private val ORIGIN = Regex("^[a-z][a-z0-9_]*$")
        const val MAX_ORIGIN = 32
        const val MAX_ID = 64

        /** Null without a usable id; an unusable origin is left out. */
        fun of(id: String?, origin: String?): Launch? {
            val clean = id?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
            return Launch(clean, validOrigin(origin))
        }

        fun fresh(origin: String?): Launch = Launch(UUID.randomUUID().toString(), validOrigin(origin))

        fun validOrigin(origin: String?): String? = origin?.takeIf { it.length <= MAX_ORIGIN && ORIGIN.matches(it) }
    }
}

/** Native source labels use the same vocabulary as the bridge, without changing scoring. */
object NativeContexts {
    fun queue(requested: String, previous: String?): String =
        if (requested == "queue" && (previous?.startsWith("mix:") == true || previous?.startsWith("wave:") == true)) previous
        else requested

    /** The context of a Navidrome playlist started by the app: `playlist:<id>` (the Hub tells «Микс дня» by it). */
    @JvmStatic
    fun playlist(id: String?): String = if (id.isNullOrBlank()) "playlist" else "playlist:$id"

    /** The `launch_origin` of a native [context]; null where the Hub has no word for it. */
    fun originOf(context: String): String? = when {
        context == "playlist" || context.startsWith("playlist:") -> "nd_playlist"
        context in ORIGINS -> context
        else -> null
    }

    /**
     * The launch of a native queue. [fresh] is the queue's own; a «queue» play that [queue] gave the context of a
     * mix or a wave goes on with that list's launch ([previous]).
     */
    fun launch(requested: String, previousContext: String?, previous: Launch?, fresh: Launch): Launch =
        if (queue(requested, previousContext) != requested && previous != null) previous else fresh

    private val ORIGINS = setOf("album", "artist", "liked", "search", "queue", "library")

    @JvmStatic
    fun songList(title: String?): String = when (title) {
        Constants.MEDIA_STARRED -> "liked"
        Constants.MEDIA_BY_ARTIST -> "artist"
        Constants.MEDIA_FROM_ALBUM -> "album"
        else -> "queue"
    }
}
