package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Child
import com.google.gson.annotations.SerializedName
import java.util.Date
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** The same authoritative list as the browser, including likes without a library file. */
class HubLikedSongs {
    var songs: List<HubLikedSong>? = null

    fun children(baseUrl: String): List<Child> = songs.orEmpty().mapIndexedNotNull { index, track ->
        track.child(baseUrl, (songs.orEmpty().size - index).toLong())
    }.distinctBy { it.id }
}

class HubLikedSong {
    var ref: String? = null
    @SerializedName("nd_song_id") var songId: String? = null
    @SerializedName("nd_album_id") var albumId: String? = null
    @SerializedName("nd_artist_id") var artistId: String? = null
    var title: String? = null
    var artist: String? = null
    @SerializedName("album_title") var album: String? = null
    @SerializedName("cover_url") var coverUrl: String? = null
    @SerializedName("duration_s") var duration: Double? = null
    @SerializedName("track_no") var track: Int? = null
    @SerializedName("disc_no") var disc: Int? = null

    fun child(baseUrl: String, rank: Long): Child? {
        val id = songId?.takeIf { it.isNotBlank() }
        val cover = coverUrl?.let { if (it.startsWith("/")) baseUrl.trimEnd('/') + it else it }
        val song = if (id != null) Child(id) else HubTracks.child(HubTracks.Item().also {
            it.ref = ref
            it.title = title
            it.artist = artist
            it.album = album
            it.coverUrl = cover
            it.durationS = duration
        }) ?: return null
        return song.apply {
            this.title = this@HubLikedSong.title
            this.artist = this@HubLikedSong.artist
            this.album = this@HubLikedSong.album
            this.albumId = this@HubLikedSong.albumId
            this.artistId = this@HubLikedSong.artistId
            // Navidrome covers must use its authenticated image loader, not a Hub
            // URL that Glide would request without the WebView session cookie.
            coverArtId = if (id != null && coverUrl?.startsWith("/api/nd/cover/") == true)
                URLDecoder.decode(coverUrl!!.substringAfter("/api/nd/cover/").substringBefore('?'), StandardCharsets.UTF_8.name())
            else cover
            duration = this@HubLikedSong.duration?.toInt()
            this.track = this@HubLikedSong.track
            discNumber = disc
            // The endpoint is already ordered by the true like date. Preserve that order
            // in Tempus' date sorter without inventing a new user decision.
            starred = Date(rank)
        }
    }
}

/** Discard a response once the account has changed, including during fallback. */
internal fun <T> loadLikedSongs(generation: Long, current: () -> Long, hub: () -> T,
                              fallback: () -> T, deliver: (T) -> Unit) {
    val result = try { hub() } catch (_: Exception) {
        if (current() != generation) return
        fallback()
    }
    if (current() == generation) deliver(result)
}
