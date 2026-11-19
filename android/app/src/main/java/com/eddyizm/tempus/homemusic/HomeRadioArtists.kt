package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.ArtistID3
import kotlin.random.Random

/**
 * The «Радиостанции» row on Главная: every artist of the library, so the row scrolls on until the one wanted —
 * the starred ones first, then the rest, each part shuffled anew (a long press on the title shuffles again).
 * A tap starts the Hub's wave by the artist (`HubWave.startArtist`).
 */
object HomeRadioArtists {
    @JvmStatic
    @JvmOverloads
    fun order(starred: List<ArtistID3>?, all: List<ArtistID3>?, random: Random = Random.Default): List<ArtistID3> {
        val first = starred.orEmpty().filter { it.id != null }.distinctBy { it.id }.shuffled(random)
        val ids = first.mapTo(HashSet()) { it.id }
        val rest = all.orEmpty().filter { it.id != null && it.id !in ids }.distinctBy { it.id }.shuffled(random)
        return first + rest
    }
}
