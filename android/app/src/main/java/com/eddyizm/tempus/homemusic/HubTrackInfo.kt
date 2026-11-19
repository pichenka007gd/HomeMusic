package com.eddyizm.tempus.homemusic

import java.util.function.Consumer

/** The «Источник» line of the track info dialog: where the Hub downloaded the song from. */
object HubTrackInfo {
    private val NAMES = mapOf(
        "hitmoz" to "Hitmos",
        "hitmos_fm" to "Hitmos FM",
        "ytdlp" to "YouTube",
        "yt_reupload" to "YouTube",
        "upload" to "загружено вручную",
        "manual" to "добавлено вручную",
    )

    /** Display text for a Hub `source` value, with the page address when there is one. */
    @JvmStatic
    fun describe(source: String?, url: String?): String? {
        val name = source?.takeIf { it.isNotBlank() }?.let { NAMES[it] ?: it } ?: return null
        return if (url.isNullOrBlank() || !url.startsWith("http")) name else "$name\n$url"
    }

    /** Asks the Hub off the main thread; [onResult] gets the text on the main thread, or null when unknown. */
    @JvmStatic
    fun load(songId: String?, onResult: Consumer<String?>) {
        if (songId.isNullOrEmpty()) return
        HomeMusic.io.execute {
            val text = try {
                lookup(songId)
            } catch (e: Exception) {
                null
            }
            HomeMusic.main.post { onResult.accept(text) }
        }
    }

    private fun lookup(songId: String): String? {
        if (!HubTracks.isHub(songId)) {
            return HubClient.trackSource(songId)?.let { describe(it.source, it.sourceUrl) }
        }
        // A catalog track that is not downloaded: the Hub's pick is the file that plays.
        val info = HubClient.listenInfo(songId.removePrefix(HubTracks.ID_PREFIX)) ?: return null
        return when (info.kind) {
            "full" -> describe(info.provider, info.pageUrl)
            "preview" -> "Deezer, превью 30 сек"
            else -> null
        }
    }
}
