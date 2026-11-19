package com.eddyizm.tempus.homemusic

import com.eddyizm.tempus.subsonic.models.Child

/**
 * The songs picked in a song list (see [HomeSongSelection]), by id, so a refresh or a search
 * filter keeps the picks.
 */
class SongSelection {
    private val ids = LinkedHashSet<String>()

    /** Called after every change: the page redraws its rows and its bar. */
    var onChange: (() -> Unit)? = null

    val isActive: Boolean get() = ids.isNotEmpty()

    fun isSelected(song: Child): Boolean = song.id in ids

    fun toggle(song: Child) {
        if (!ids.remove(song.id)) ids.add(song.id)
        onChange?.invoke()
    }

    /** Picks every song of [songs], or drops them all when they all are picked already. */
    fun toggleAll(songs: List<Child>) {
        if (songs.isNotEmpty() && songs.all(::isSelected)) songs.forEach { ids.remove(it.id) }
        else songs.forEach { ids.add(it.id) }
        onChange?.invoke()
    }

    fun clear() {
        if (ids.isEmpty()) return
        ids.clear()
        onChange?.invoke()
    }

    /** The picked songs of [songs] in the list's order, each once; picks the list no longer has are left out. */
    fun pick(songs: List<Child>): List<Child> = songs.filter { it.id in ids }.distinctBy { it.id }

    companion object {
        /** The adapter payload that redraws only a row's selection state. */
        const val PAYLOAD = "payload_homemusic_selection"
    }
}
