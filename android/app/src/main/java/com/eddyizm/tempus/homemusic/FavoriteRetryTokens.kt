package com.eddyizm.tempus.homemusic

import android.content.Context
import com.eddyizm.tempus.App
import com.eddyizm.tempus.model.Favorite
import com.eddyizm.tempus.util.FavoriteRegistry

/** Companion tokens for Tempus's unchanged Room retry rows; no account credentials are stored. */
object FavoriteRetryTokens {
    private fun prefs() = App.getContext().getSharedPreferences("homemusic_favorite_retries", Context.MODE_PRIVATE)

    @JvmStatic
    fun remember(timestamp: Long, record: FavoriteRegistry.Record?) {
        if (!HubConfig.isEnabled() || record == null) return
        prefs().edit().putString(timestamp.toString(), record.eventId).apply()
    }

    @JvmStatic
    fun restore(favorite: Favorite): Boolean {
        if (!HubConfig.isEnabled()) return true
        // Rows from older APKs did not retain ids. Retrying those is still no new taste signal.
        val eventId = prefs().getString(favorite.timestamp.toString(), null) ?: "legacyfav:${favorite.timestamp}"
        val kind = when {
            favorite.songId != null -> FavoriteRegistry.Kind.SONG
            favorite.albumId != null -> FavoriteRegistry.Kind.ALBUM
            else -> FavoriteRegistry.Kind.ARTIST
        }
        return FavoriteRegistry.restore(kind, favorite.songId ?: favorite.albumId ?: favorite.artistId, favorite.toStar, eventId)
    }

    @JvmStatic
    fun forget(timestamp: Long) {
        if (HubConfig.isEnabled()) prefs().edit().remove(timestamp.toString()).apply()
    }
}
