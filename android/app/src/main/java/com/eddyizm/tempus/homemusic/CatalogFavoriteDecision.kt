package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.util.FavoriteRegistry
import java.io.IOException

/** One pending command. A stale response can only accept/withdraw its own optimistic record. */
class CatalogFavoriteDecision(val id: String, private val before: Boolean, val starred: Boolean,
                              eventId: String? = null, val timestamp: String = PlayEvent.iso(System.currentTimeMillis()),
                              restore: Boolean = false) {
    private val record = run {
        FavoriteRegistry.rememberBaseline(FavoriteRegistry.Kind.SONG, id, before)
        FavoriteRegistry.setWithId(FavoriteRegistry.Kind.SONG, id, starred, eventId, !restore)
    }
    val eventId: String get() = record.eventId

    fun accepted() = FavoriteRegistry.accept(record)

    fun failed(): Boolean {
        FavoriteRegistry.withdraw(record)
        return FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, id, before)
    }
}

internal fun catalogFavoriteRequest(ref: String, starred: Boolean, eventId: String, timestamp: String,
                                    client: String, context: String?): Pair<String, Map<String, Any?>> =
    if (starred) "/api/downloads" to mapOf("track_refs" to listOf(ref), "star" to true,
        "favorite_event_id" to eventId, "favorite_ts" to timestamp,
        "favorite_client" to client, "favorite_context" to context)
    else "/api/star" to mapOf("refs" to listOf(ref), "unstar" to true, "event_id" to eventId,
        "ts" to timestamp, "client" to client, "context" to context)

/** Retry only an uncertain transport failure; a server refusal is final. */
internal fun <T> retryCatalogTransport(send: () -> T): T = try {
    send()
} catch (e: IOException) {
    if (e is HubException) throw e
    send()
}
