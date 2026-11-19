package com.eddyizm.tempus.homemusic

import android.content.Context
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R

/** Sizes and the traffic counter as the settings screen shows them. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HomeTrafficFormat {
    /** «12,3 МБ» or «1,25 ГБ». */
    fun size(context: Context, bytes: Long): String =
        if (bytes >= 1L shl 30) context.getString(R.string.homemusic_size_gb, bytes / (1024.0 * 1024 * 1024))
        else context.getString(R.string.homemusic_size_mb, bytes / (1024.0 * 1024))

    /** Today and the last 7 days; blocking (the system statistics). */
    fun summary(context: Context): String {
        fun part(days: Int): Triple<String, String, String> {
            val play = HomeTrafficStats.bytes(HomeTrafficStats.PLAY, days)
            val preload = HomeTrafficStats.bytes(HomeTrafficStats.PRELOAD, days)
            return Triple(size(context, play + preload), size(context, play), size(context, preload))
        }
        val today = part(1)
        val week = part(7)
        val text = context.getString(R.string.homemusic_settings_traffic_summary,
            today.first, today.second, today.third, week.first, week.second, week.third)
        val system = HomeTrafficStats.systemTotal(7)?.let { context.getString(R.string.homemusic_settings_traffic_system, size(context, it)) }
        return text + system.orEmpty()
    }
}
