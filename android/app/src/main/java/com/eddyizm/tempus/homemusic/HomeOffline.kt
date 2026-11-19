package com.eddyizm.tempus.homemusic

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.repository.DownloadRepository
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.Artist
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.ArtistWithAlbumsID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Directory
import com.eddyizm.tempus.subsonic.models.Index
import com.eddyizm.tempus.subsonic.models.Indexes
import com.eddyizm.tempus.subsonic.models.MusicFolder
import com.eddyizm.tempus.subsonic.utils.CacheUtil
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.ExternalAudioReader
import com.eddyizm.tempus.util.Preferences
import com.google.gson.reflect.TypeToken
import java.util.Locale

/**
 * The library without the server: no network, or Navidrome not answering. Tempus then shows what its HTTP cache
 * has — folders and albums with every song, most of which cannot play, or a «--» page when a folder was never
 * opened online. HomeMusic shows what is on the phone instead (Tempus' downloads, the «Скачанное» tab):
 * a cached list keeps only the downloaded songs and the folders that hold some, a list the cache lacks is built
 * from the downloads. With the server answering nothing changes.
 *
 * Navidrome's folder view is artist → album → songs, with the artist and album ids as folder ids and a song's
 * `parent` being its album, so the downloads (which keep these ids) rebuild it. The same ids rebuild the album
 * lists, the artist list and artist pages: offline they show the albums and artists with songs on the phone.
 *
 * Song rows also say clearly what is on the phone: [markOnPhone], [markFolderRow].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HomeOffline {
    private const val PREFS = "homemusic_offline"
    private const val KEY_FOLDERS = "music_folders"

    /** No usable network: Tempus answers from its HTTP cache (`CacheUtil.offlineInterceptor`). */
    private fun offline() = !CacheUtil.isConnected()

    private fun downloads(): List<Child> = DownloadRepository().allDownloads

    // --- hooks in Tempus' repositories ---------------------------------------------------------

    /** `getMusicDirectory`: [fromServer] is null when the request failed (the cache lacks it offline). */
    @JvmStatic
    fun directory(id: String?, fromServer: Directory?): Directory {
        if (fromServer != null && !offline()) return fromServer
        val downloads = downloads()
        return if (fromServer != null) onPhone(fromServer, downloads)
        else directoryOf(id, downloads) ?: Directory(children = emptyList(), id = id, name = App.getContext().getString(R.string.homemusic_offline_nothing))
    }

    /** `getIndexes` (the artists of «Music Library»): offline, the artists of the downloads. */
    @JvmStatic
    fun indexes(fromServer: Indexes?): Indexes? =
        if (fromServer != null && !offline()) fromServer else indexesOf(downloads())

    /** `getAlbum` tracks: offline, the album's songs on the phone. Empty [fromServer] is a failed request too. */
    @JvmStatic
    fun albumTracks(albumId: String?, fromServer: List<Child>): List<Child> {
        if (fromServer.isNotEmpty() && !offline()) return fromServer
        val downloads = downloads()
        return if (fromServer.isNotEmpty()) onPhone(fromServer, downloads) else songsOf(albumId, downloads)
    }

    /** A song list from the cache (liked songs): offline, only the songs that can play. */
    @JvmStatic
    fun songs(fromServer: List<Child>): List<Child> =
        if (!offline()) fromServer else onPhone(fromServer, downloads())

    /**
     * `getAlbumList2` lists of [type] (Library's «Альбомы» row, Home's rows): offline, the albums on the phone,
     * in the cache's order for the lists ordered by listening (recent, frequent, ...).
     */
    @JvmStatic
    fun albumList(type: String?, size: Int, fromYear: Int?, toYear: Int?, fromServer: List<AlbumID3>?): List<AlbumID3> =
        if (fromServer != null && !offline()) fromServer else ArrayList(albumListOf(type, size, fromYear, toYear, fromServer, downloads()))

    /** A page of the album catalogue («Альбомы», by name): offline, the albums on the phone. */
    @JvmStatic
    fun albumCatalogue(offset: Int, size: Int, fromServer: List<AlbumID3>?): List<AlbumID3> =
        if (fromServer != null && !offline()) fromServer
        else ArrayList(albumListOf("alphabeticalByName", 0, null, null, fromServer, downloads()).drop(offset).take(size))

    /** `getArtists` (the artist catalogue, Library's row): offline, the artists of the downloads. */
    @JvmStatic
    fun artists(fromServer: List<ArtistID3>?): List<ArtistID3> =
        if (fromServer != null && !offline()) fromServer else ArrayList(artistsOf(fromServer, downloads()))

    /** `getArtist` (the artist page): offline, the artist with its albums on the phone; null if nothing is known of it. */
    @JvmStatic
    fun artist(id: String?, fromServer: ArtistID3?): ArtistID3? =
        if (fromServer != null && !offline()) fromServer else artistOf(id, fromServer, downloads())

    /** An artist's top songs: offline, those on the phone, or the artist's downloads when the cache has none. */
    @JvmStatic
    fun topSongs(artistName: String?, fromServer: List<Child>?): List<Child> {
        if (fromServer != null && !offline()) return fromServer
        val downloads = downloads()
        return ArrayList(onPhone(fromServer.orEmpty(), downloads).ifEmpty { songsBy(artistName, downloads) })
    }

    /** `getMusicFolders`: remembered, so «Папки» stays without the server even when the cache dropped it. */
    @JvmStatic
    fun musicFolders(fromServer: List<MusicFolder>?): List<MusicFolder>? {
        val prefs = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (fromServer != null) {
            prefs.edit().putString(KEY_FOLDERS, HubClient.gson.toJson(fromServer)).apply()
            return fromServer
        }
        val json = prefs.getString(KEY_FOLDERS, null) ?: return null
        return runCatching { HubClient.gson.fromJson<List<MusicFolder>>(json, object : TypeToken<List<MusicFolder>>() {}.type) }.getOrNull()
    }

    // --- «on the phone» mark of a song row ----------------------------------------------------

    internal enum class Mark { NONE, PENDING, ON_PHONE }

    /** A media3 download state ([Download.state], null: no download) as the row shows it. */
    internal fun mark(state: Int?): Mark = when (state) {
        null, Download.STATE_FAILED, Download.STATE_REMOVING -> Mark.NONE
        Download.STATE_COMPLETED -> Mark.ON_PHONE
        else -> Mark.PENDING // queued, downloading, stopped, restarting
    }

    /**
     * Tempus' download indicator of a song row (a frame around one icon), which it shows for a download in any state:
     * a green arrow once the file is on the phone, like Spotify's, a grey outline while it is still coming.
     */
    @JvmStatic
    fun markOnPhone(indicator: View, song: Child) {
        if (indicator.visibility != View.VISIBLE) return
        val mark = markOf(indicator.context, song)
        if (mark == Mark.NONE) {
            indicator.visibility = View.GONE
            return
        }
        val icon = (indicator as? ViewGroup)?.getChildAt(0) ?: indicator
        icon.background = ContextCompat.getDrawable(
            indicator.context, if (mark == Mark.ON_PHONE) R.drawable.ic_homemusic_on_phone else R.drawable.ic_homemusic_to_phone,
        )
        icon.contentDescription = indicator.context.getString(
            if (mark == Mark.ON_PHONE) R.string.homemusic_downloaded_on_phone else R.string.homemusic_downloading_to_phone,
        )
    }

    /**
     * A row of «Папки» (`MusicDirectoryAdapter`), which has no download indicator: in a song row the «›» slot,
     * empty there, shows the same mark. Folder rows keep their «›» (rows are reused, so it is put back).
     */
    @JvmStatic
    fun markFolderRow(slot: ImageView, child: Child) {
        val mark = if (child.isDir) Mark.NONE else markOf(slot.context, child)
        if (mark == Mark.NONE) {
            slot.setImageDrawable(null)
            slot.setPadding(0, 0, 0, 0)
            slot.background = ContextCompat.getDrawable(slot.context, R.drawable.ic_navigate_next)
            slot.contentDescription = null
            return // Tempus' visibility stays: «›» for a folder, nothing for a song
        }
        val inset = (2 * slot.resources.displayMetrics.density).toInt() // an 18dp mark in the 22dp slot, as in other rows
        slot.background = null
        slot.setPadding(inset, inset, inset, inset)
        slot.setImageResource(if (mark == Mark.ON_PHONE) R.drawable.ic_homemusic_on_phone else R.drawable.ic_homemusic_to_phone)
        slot.contentDescription = slot.context.getString(
            if (mark == Mark.ON_PHONE) R.string.homemusic_downloaded_on_phone else R.string.homemusic_downloading_to_phone,
        )
        slot.visibility = View.VISIBLE
    }

    private fun markOf(context: Context, song: Child): Mark =
        if (Preferences.getDownloadDirectoryUri() != null) {
            if (ExternalAudioReader.getUri(song) != null) Mark.ON_PHONE else Mark.NONE // a file in the chosen folder
        } else {
            mark(DownloadUtil.getDownloadTracker(context).getDownloadState(song.id))
        }

    /** Calls [onChange] when a download to the phone is added, finished or removed, so the marks follow. */
    @JvmStatic
    fun observeDownloads(owner: LifecycleOwner, onChange: Runnable) {
        var first = true // the current list, already drawn
        DownloadRepository().liveDownload.observe(owner) {
            if (first) first = false else onChange.run()
        }
    }

    // --- the library from the downloads -------------------------------------------------------

    private fun albumKey(song: Child): String? = song.albumId?.takeIf { it.isNotBlank() } ?: song.parentId?.takeIf { it.isNotBlank() }

    private fun byTrack(songs: List<Child>) = songs.sortedWith(compareBy<Child>({ it.discNumber ?: 0 }, { it.track ?: 0 }, { it.title ?: "" }))

    /** Downloaded songs grouped by album; each album has one artist: the artist of its first track. */
    private class Album(val id: String, val songs: List<Child>) {
        val first = songs.first()
        val artistId: String? = first.artistId
        val artistName: String? = first.albumArtist?.takeIf { it.isNotBlank() } ?: first.artist
    }

    private fun albums(downloads: List<Child>): List<Album> = downloads
        .filter { !it.isDir }
        .groupBy { albumKey(it) }
        .mapNotNull { (id, songs) -> id?.let { Album(it, byTrack(songs)) } }

    /** Only what can play: downloaded songs, and folders that hold some. */
    internal fun onPhone(directory: Directory, downloads: List<Child>): Directory {
        val songs = downloads.mapNotNullTo(HashSet()) { it.id }
        val folders = HashSet<String>()
        for (song in downloads) listOf(song.albumId, song.parentId, song.artistId).forEach { id -> id?.let(folders::add) }
        directory.children = directory.children?.filter { if (it.isDir) it.id in folders else it.id in songs }
        return directory
    }

    internal fun onPhone(list: List<Child>, downloads: List<Child>): List<Child> {
        val songs = downloads.mapNotNullTo(HashSet()) { it.id }
        return list.filter { it.id in songs }
    }

    internal fun songsOf(albumId: String?, downloads: List<Child>): List<Child> =
        if (albumId == null) emptyList() else albums(downloads).firstOrNull { it.id == albumId }?.songs ?: emptyList()

    /** The folder [id] rebuilt from the downloads: an album (its songs) or an artist (its albums); null if neither. */
    internal fun directoryOf(id: String?, downloads: List<Child>): Directory? {
        if (id == null) return null
        val albums = albums(downloads)
        albums.firstOrNull { it.id == id }?.let { album ->
            return Directory(children = album.songs, id = id, parentId = album.artistId, name = album.first.album)
        }
        val ofArtist = albums.filter { album -> album.artistId == id || album.songs.any { it.artistId == id } }
        if (ofArtist.isEmpty()) return null
        val children = ofArtist
            .map { a ->
                Child(a.id).apply {
                    isDir = true
                    parentId = id
                    title = a.first.album
                    album = a.first.album
                    artist = a.artistName
                    artistId = id
                    albumId = a.id
                    coverArtId = a.first.coverArtId
                    year = a.first.year
                }
            }
            .sortedBy { it.title?.lowercase(Locale.ROOT) ?: "" }
        val name = ofArtist.firstOrNull { it.artistId == id }?.artistName ?: ofArtist.first().artistName
        return Directory(children = children, id = id, name = name)
    }

    /** The artists of the downloads, by first letter, as `getIndexes` gives them. */
    internal fun indexesOf(downloads: List<Child>): Indexes {
        val artists = albums(downloads)
            .filter { it.artistId != null }
            .distinctBy { it.artistId }
            .map { Artist(id = it.artistId, name = it.artistName) }
            .sortedBy { it.name?.lowercase(Locale.ROOT) ?: "" }
        return Indexes().apply {
            indices = artists
                .groupBy { it.name?.trim()?.firstOrNull()?.uppercaseChar()?.takeIf(Char::isLetter)?.toString() ?: "#" }
                .map { (letter, group) -> Index().apply { name = letter; this.artists = group } }
        }
    }

    private fun Album.toAlbumID3() = AlbumID3(
        id = id,
        name = first.album,
        artist = artistName,
        artistId = artistId,
        coverArtId = first.coverArtId,
        songCount = songs.size,
        duration = songs.sumOf { it.duration ?: 0 },
        created = first.created,
        year = first.year ?: 0,
        genre = first.genre,
    )

    /** The albums on the phone, as the cache knows them when it does (full song count, release type). */
    internal fun albumsOnPhone(cached: List<AlbumID3>?, downloads: List<Child>): List<AlbumID3> {
        val known = cached.orEmpty().associateBy { it.id }
        return albums(downloads).map { known[it.id] ?: it.toAlbumID3() }
    }

    private val byName = compareBy<AlbumID3> { it.name?.lowercase(Locale.ROOT) ?: "" }

    /** An album list of [type] (`getAlbumList2`) from the albums on the phone; [size] 0 is all of them. */
    internal fun albumListOf(
        type: String?, size: Int, fromYear: Int?, toYear: Int?, cached: List<AlbumID3>?, downloads: List<Child>,
    ): List<AlbumID3> {
        val onPhone = albumsOnPhone(cached, downloads)
        val list = when (type) {
            "random" -> onPhone.shuffled()
            "byYear" -> if (fromYear == null || toYear == null) onPhone.sortedBy { it.year } else {
                val years = minOf(fromYear, toYear)..maxOf(fromYear, toYear)
                onPhone.filter { it.year in years }.sortedBy { it.year }.let { if (fromYear > toYear) it.reversed() else it }
            }
            "alphabeticalByArtist" -> onPhone.sortedWith(compareBy<AlbumID3> { it.artist?.lowercase(Locale.ROOT) ?: "" }.then(byName))
            "newest" -> if (cached != null) inCacheOrder(cached, onPhone) else onPhone.sortedByDescending { it.created }
            "recent", "frequent", "highest", "starred" -> if (cached != null) inCacheOrder(cached, onPhone) else onPhone.sortedWith(byName)
            else -> onPhone.sortedWith(byName)
        }
        return if (size > 0) list.take(size) else list
    }

    /** A list ordered by listening: only the server knows that order, so the cached albums on the phone, as cached. */
    private fun inCacheOrder(cached: List<AlbumID3>, onPhone: List<AlbumID3>): List<AlbumID3> {
        val ids = onPhone.mapTo(HashSet()) { it.id }
        return cached.filter { it.id in ids }.distinctBy { it.id }
    }

    /** The albums on the phone that [artistId] made or appears on (as its «Папки» folder). */
    private fun albumsOfArtist(artistId: String, albums: List<Album>) =
        albums.filter { album -> album.artistId == artistId || album.songs.any { it.artistId == artistId } }

    /** The artists of the downloads by name, as the cache knows them when it does. */
    internal fun artistsOf(cached: List<ArtistID3>?, downloads: List<Child>): List<ArtistID3> {
        val known = cached.orEmpty().associateBy { it.id }
        return albums(downloads)
            .filter { it.artistId != null }
            .groupBy { it.artistId!! }
            .map { (id, albums) ->
                known[id] ?: ArtistID3(id = id, name = albums.first().artistName, coverArtId = albums.first().first.coverArtId, albumCount = albums.size)
            }
            .sortedBy { it.name?.lowercase(Locale.ROOT) ?: "" }
    }

    /** The artist [id] with only its albums on the phone (none: an empty page); null if neither the cache nor the downloads know it. */
    internal fun artistOf(id: String?, cached: ArtistID3?, downloads: List<Child>): ArtistID3? {
        if (id == null) return cached
        val ofArtist = albumsOfArtist(id, albums(downloads))
        if (cached == null && ofArtist.isEmpty()) return null
        val known = (cached as? ArtistWithAlbumsID3)?.let { it.albums.orEmpty() + it.appearsOn.orEmpty() }.orEmpty().associateBy { it.id }
        val own = ofArtist.firstOrNull { it.artistId == id } ?: ofArtist.firstOrNull()
        return ArtistWithAlbumsID3(albums = ofArtist.map { known[it.id] ?: it.toAlbumID3() }, appearsOn = emptyList()).apply {
            this.id = id
            name = cached?.name ?: own?.artistName
            coverArtId = cached?.coverArtId ?: own?.first?.coverArtId
            albumCount = ofArtist.size
            starred = cached?.starred
        }
    }

    /** The downloaded songs of the artist named [name] (`getTopSongs` asks by name). */
    internal fun songsBy(name: String?, downloads: List<Child>): List<Child> {
        if (name.isNullOrBlank()) return emptyList()
        return albums(downloads).flatMap { album ->
            album.songs.filter { name.equals(it.artist, ignoreCase = true) || name.equals(album.artistName, ignoreCase = true) }
        }
    }
}
