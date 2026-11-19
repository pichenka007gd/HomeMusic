package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.util.FavoriteRegistry
import com.google.gson.annotations.SerializedName

class HubFavoriteSubject {
    @SerializedName("target_type") var targetType: String? = null
    var ref: String? = null
    @SerializedName("nd_id") var ndId: String? = null
    var liked: Boolean = false
    var aliases: List<String>? = null
    var provenance: String? = null
}

/** Own decisions, including unlikes, precede lagging ND/import mirrors within each target type. */
class HubFavoriteSnapshot {
    @SerializedName("song_ids") var songIds: List<String>? = null
    @SerializedName("album_ids") var albumIds: List<String>? = null
    @SerializedName("artist_ids") var artistIds: List<String>? = null
    var refs: List<String>? = null
    var subjects: List<HubFavoriteSubject>? = null

    fun states(): List<FavoriteRegistry.State> {
        val result = ArrayList<FavoriteRegistry.State>()
        val subjects = subjects.orEmpty().sortedBy { it.provenance != "own" }
        subjects.forEach { subject ->
            val kind = when (subject.targetType) {
                "track" -> FavoriteRegistry.Kind.SONG
                "album" -> FavoriteRegistry.Kind.ALBUM
                "artist" -> FavoriteRegistry.Kind.ARTIST
                else -> return@forEach
            }
            val aliases = subject.aliases.orEmpty() + listOfNotNull(subject.ref?.let { "ref:$it" }, subject.ndId?.let { "nd:$it" })
            aliases.distinct().forEach aliasLoop@ { alias ->
                val id = when {
                    alias.startsWith("ref:") -> HubTracks.ID_PREFIX + alias.removePrefix("ref:")
                    alias.startsWith("nd:") -> alias.removePrefix("nd:")
                    else -> return@aliasLoop
                }
                result += FavoriteRegistry.State(kind, id, subject.liked)
            }
        }
        songIds.orEmpty().forEach { result += FavoriteRegistry.State(FavoriteRegistry.Kind.SONG, it, true) }
        albumIds.orEmpty().forEach { result += FavoriteRegistry.State(FavoriteRegistry.Kind.ALBUM, it, true) }
        artistIds.orEmpty().forEach { result += FavoriteRegistry.State(FavoriteRegistry.Kind.ARTIST, it, true) }
        refs.orEmpty().forEach { result += FavoriteRegistry.State(FavoriteRegistry.Kind.SONG, HubTracks.ID_PREFIX + it, true) }
        return result
    }
}
