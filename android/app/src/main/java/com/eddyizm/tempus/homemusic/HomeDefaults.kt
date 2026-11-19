package com.eddyizm.tempus.homemusic

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * HomeMusic looks like one app with the Hub: green accent (the Hub's #1ED760) instead of wallpaper colors,
 * black background in the dark theme, no Podcasts / Radio tabs, the next tracks of the queue loaded ahead on any
 * network (`QueueAhead`: skips start at once, the mix and continuous play don't stall). These are only defaults: a setting the user
 * has changed is left alone, and they are seeded once (a later version may add more).
 *
 * Registered as a provider in the homemusic manifest only: providers start before Application.onCreate and
 * the first activity, so the theme is right on the very first screen without touching Tempus code.
 */
class HomeDefaults : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return true
        val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        val seeded = prefs.getInt(PREF_VERSION, 0)
        if (seeded >= VERSION) return true
        // Keys of com.eddyizm.tempus.util.Preferences. A value still equal to Tempus' own default counts as untouched:
        // the settings screen writes its defaults on first run, so "absent" alone would miss existing installs.
        fun untouched(key: String, default: Any) = !prefs.contains(key) || prefs.all[key] == default
        // each version seeds only what it added: a value set before and changed back by the user stays
        prefs.edit {
            if (seeded < 2) {
                if (untouched("dynamic_color_accent", true) && untouched("color_accent", "DYNAMIC")) {
                    putBoolean("dynamic_color_accent", false)
                    putString("color_accent", HUB_ACCENT)
                }
                if (untouched("dark_theme_black", false)) putBoolean("dark_theme_black", true)
                if (untouched("podcast_section_visibility", true)) putBoolean("podcast_section_visibility", false)
                if (untouched("radio_section_visibility", true)) putBoolean("radio_section_visibility", false)
            }
            if (seeded < 3) {
                if (untouched("precache_tracks_count", "0")) putString("precache_tracks_count", PRECACHE_TRACKS.toString())
                if (untouched("precache_wifi_only", true)) putBoolean("precache_wifi_only", false)
            } else if (seeded < 4 && prefs.getString("precache_tracks_count", null) == "5") {
                // 4: the heads of 3 next tracks instead of 5 whole files; version 3's default counts as untouched
                putString("precache_tracks_count", PRECACHE_TRACKS.toString())
            }
            if (seeded < 5) {
                // 5: saving mobile traffic: a bigger cache of streamed songs (a rewind, a repeat, a tunnel cost nothing)
                // and covers at 500 px instead of the original size
                cacheSizeFor(cacheFreeBytes(ctx))?.let { if (untouched("streaming_cache_size", "256")) putString("streaming_cache_size", it.toString()) }
                if (untouched("image_size", "-1")) putString("image_size", COVER_SIZE)
            }
            putInt(PREF_VERSION, VERSION)
        }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val HUB_ACCENT = "HEX:#1ED760"
        private const val PREF_VERSION = "homemusic_defaults_version"
        private const val VERSION = 5 // 2: values equal to Tempus defaults count as untouched; 3: precache; 4: 3 heads; 5: cache, covers

        /** Next tracks whose first seconds the precache loads (`QueueLoading`; an entry of Tempus' `precache_tracks_values`). */
        const val PRECACHE_TRACKS = 3

        /** Cover size in px (an entry of Tempus' `pref_image_size_values`). */
        const val COVER_SIZE = "500"

        /** Sizes of the streaming cache in MiB (entries of `streaming_cache_size_values`), the biggest first. */
        private val CACHE_SIZES_MIB = intArrayOf(2048, 1024)

        /** The cache may take at most this share of the free space. */
        private const val CACHE_SHARE = 10

        /** The biggest cache size that is at most a tenth of [freeBytes], or null when even the smallest is too much. */
        fun cacheSizeFor(freeBytes: Long): Int? = CACHE_SIZES_MIB.firstOrNull { it * 1024L * 1024L <= freeBytes / CACHE_SHARE }

        private fun cacheFreeBytes(context: Context): Long =
            (context.getExternalFilesDir(null) ?: context.filesDir).usableSpace
    }
}
