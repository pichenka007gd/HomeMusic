package com.eddyizm.tempus.homemusic

import androidx.media3.exoplayer.offline.Download
import com.eddyizm.tempus.subsonic.models.AlbumID3
import com.eddyizm.tempus.subsonic.models.ArtistID3
import com.eddyizm.tempus.subsonic.models.ArtistWithAlbumsID3
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.models.Directory
import com.eddyizm.tempus.util.IndexUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeOfflineTest {
    // As Navidrome gives them: a song's parent is its album, the album folder's parent its artist.
    private fun song(id: String, album: String, albumId: String, artist: String, artistId: String, track: Int, disc: Int = 1) =
        Child(id).apply {
            title = "Песня $id"
            this.album = album
            this.albumId = albumId
            parentId = albumId
            this.artist = artist
            this.artistId = artistId
            this.track = track
            discNumber = disc
            coverArtId = "al-$albumId"
        }

    private val downloads = listOf(
        song("s3", "Группа крови", "al-1", "Кино", "ar-1", track = 3),
        song("s1", "Группа крови", "al-1", "Кино", "ar-1", track = 1),
        song("s9", "Звезда по имени Солнце", "al-2", "Кино", "ar-1", track = 2),
        song("s20", "Судный день", "al-3", "ЛСП", "ar-2", track = 1),
    )

    private fun dir(id: String) = Child(id).apply { isDir = true; title = id }

    @Test
    fun albumFolderIsItsSongsOnThePhoneInOrder() {
        val dir = HomeOffline.directoryOf("al-1", downloads)!!
        assertEquals("Группа крови", dir.name)
        assertEquals(listOf("s1", "s3"), dir.children!!.map { it.id })
        assertEquals("ar-1", dir.parentId)
    }

    @Test
    fun artistFolderIsItsAlbumsOnThePhone() {
        val dir = HomeOffline.directoryOf("ar-1", downloads)!!
        assertEquals("Кино", dir.name)
        val albums = dir.children!!
        assertEquals(listOf("al-1", "al-2"), albums.map { it.id })
        assertTrue(albums.all { it.isDir })
        assertEquals("Звезда по имени Солнце", albums[1].title)
        assertEquals("al-al-2", albums[1].coverArtId)
    }

    @Test
    fun folderWithNothingOnThePhone() {
        assertNull(HomeOffline.directoryOf("ar-404", downloads))
        assertNull(HomeOffline.directoryOf(null, downloads))
    }

    @Test
    fun cachedFolderKeepsOnlyWhatPlays() {
        val album = Directory(children = listOf(Child("s1"), Child("s2"), Child("s3")), id = "al-1", name = "Группа крови")
        assertEquals(listOf("s1", "s3"), HomeOffline.onPhone(album, downloads).children!!.map { it.id })

        val artist = Directory(children = listOf(dir("al-1"), dir("al-404"), dir("al-2")), id = "ar-1", name = "Кино")
        assertEquals(listOf("al-1", "al-2"), HomeOffline.onPhone(artist, downloads).children!!.map { it.id })
    }

    @Test
    fun cachedSongListKeepsOnlyWhatPlays() {
        val liked = listOf(Child("s20"), Child("s404"), Child("s1"))
        assertEquals(listOf("s20", "s1"), HomeOffline.onPhone(liked, downloads).map { it.id })
    }

    @Test
    fun albumTracksFromTheDownloads() {
        assertEquals(listOf("s1", "s3"), HomeOffline.songsOf("al-1", downloads).map { it.id })
        assertTrue(HomeOffline.songsOf("al-404", downloads).isEmpty())
    }

    @Test
    fun artistsOfTheDownloadsByLetter() {
        val indexes = HomeOffline.indexesOf(downloads)
        assertEquals(listOf("К", "Л"), indexes.indices!!.map { it.name })
        val artists = IndexUtil.getArtist(indexes)
        assertEquals(listOf("ar-1", "ar-2"), artists.map { it.id })
        assertEquals(listOf("Кино", "ЛСП"), artists.map { it.name })
    }

    @Test
    fun albumArtistNamesTheFolder() {
        val feat = song("s30", "Сборник", "al-9", "Кино feat. ЛСП", "ar-1", track = 1).apply { albumArtist = "Кино" }
        val dir = HomeOffline.directoryOf("ar-1", listOf(feat))!!
        assertEquals("Кино", dir.name)
    }

    @Test
    fun markIsGreenOnlyOnceTheFileIsOnThePhone() {
        assertEquals(HomeOffline.Mark.ON_PHONE, HomeOffline.mark(Download.STATE_COMPLETED))
        assertEquals(HomeOffline.Mark.PENDING, HomeOffline.mark(Download.STATE_QUEUED))
        assertEquals(HomeOffline.Mark.PENDING, HomeOffline.mark(Download.STATE_DOWNLOADING))
        assertEquals(HomeOffline.Mark.NONE, HomeOffline.mark(Download.STATE_FAILED))
        assertEquals(HomeOffline.Mark.NONE, HomeOffline.mark(Download.STATE_REMOVING))
        assertEquals(HomeOffline.Mark.NONE, HomeOffline.mark(null))
    }

    // --- album lists and artists offline ---

    private val dated = downloads.map { song ->
        Child(song.id).apply {
            title = song.title; album = song.album; albumId = song.albumId; parentId = song.parentId
            artist = song.artist; artistId = song.artistId; track = song.track; discNumber = song.discNumber
            coverArtId = song.coverArtId; duration = 200
            year = mapOf("al-1" to 1988, "al-2" to 1989, "al-3" to 2017)[song.albumId]
        }
    }

    private fun album(id: String, name: String) = AlbumID3(id = id, name = name, songCount = 11)

    @Test
    fun albumListIsTheAlbumsOnThePhone() {
        val all = HomeOffline.albumListOf("alphabeticalByName", 0, null, null, null, dated)
        assertEquals(listOf("al-1", "al-2", "al-3"), all.map { it.id })
        val kino = all.first()
        assertEquals("Группа крови", kino.name)
        assertEquals("Кино", kino.artist)
        assertEquals("ar-1", kino.artistId)
        assertEquals(2, kino.songCount)
        assertEquals(1988, kino.year)
        assertEquals("al-al-1", kino.coverArtId)

        assertEquals(setOf("al-1", "al-2", "al-3"), HomeOffline.albumListOf("random", 10, null, null, null, dated).map { it.id }.toSet())
        assertEquals(2, HomeOffline.albumListOf("random", 2, null, null, null, dated).size)
    }

    @Test
    fun cachedAlbumsOnThePhoneKeepTheirServerData() {
        val cached = listOf(album("al-404", "Нет на телефоне"), album("al-2", "Звезда по имени Солнце"))
        val list = HomeOffline.albumListOf("alphabeticalByName", 0, null, null, cached, dated)
        assertEquals(listOf("al-1", "al-2", "al-3"), list.map { it.id })
        assertEquals(11, list[1].songCount) // the cached album, not one built from 1 download
    }

    @Test
    fun listsByListeningKeepTheCachedOrder() {
        val cached = listOf(album("al-3", "Судный день"), album("al-404", "Нет"), album("al-1", "Группа крови"))
        assertEquals(listOf("al-3", "al-1"), HomeOffline.albumListOf("recent", 20, null, null, cached, dated).map { it.id })
        assertEquals(listOf("al-3", "al-1"), HomeOffline.albumListOf("frequent", 20, null, null, cached, dated).map { it.id })
        // the cache lacks it: all the albums on the phone
        assertEquals(3, HomeOffline.albumListOf("frequent", 20, null, null, null, dated).size)
    }

    @Test
    fun albumsOfAYear() {
        assertEquals(listOf("al-3"), HomeOffline.albumListOf("byYear", 500, 2017, 2017, null, dated).map { it.id })
        assertEquals(listOf("al-1", "al-2"), HomeOffline.albumListOf("byYear", 500, 1980, 1990, null, dated).map { it.id })
        assertEquals(listOf("al-2", "al-1"), HomeOffline.albumListOf("byYear", 500, 1990, 1980, null, dated).map { it.id })
    }

    @Test
    fun albumCataloguePagesFromTheDownloads() {
        val all = HomeOffline.albumListOf("alphabeticalByName", 0, null, null, null, dated)
        assertEquals(listOf("al-1", "al-2"), all.drop(0).take(2).map { it.id })
        assertEquals(listOf("al-3"), all.drop(2).take(2).map { it.id })
    }

    @Test
    fun artistsAreThoseOnThePhone() {
        val cached = listOf(ArtistID3(id = "ar-404", name = "Аквариум"), ArtistID3(id = "ar-2", name = "ЛСП", albumCount = 7))
        val artists = HomeOffline.artistsOf(cached, downloads)
        assertEquals(listOf("ar-1", "ar-2"), artists.map { it.id })
        assertEquals("Кино", artists[0].name)
        assertEquals(2, artists[0].albumCount)
        assertEquals(7, artists[1].albumCount) // the cached artist
        assertEquals(listOf("ar-1", "ar-2"), HomeOffline.artistsOf(null, downloads).map { it.id })
    }

    @Test
    fun artistPageShowsOnlyItsAlbumsOnThePhone() {
        val cached = ArtistWithAlbumsID3(
            albums = listOf(album("al-1", "Группа крови"), album("al-404", "Нет на телефоне")),
            appearsOn = listOf(album("al-405", "Сборник")),
        ).apply { id = "ar-1"; name = "Кино"; coverArtId = "ar-1-cover" }
        val page = HomeOffline.artistOf("ar-1", cached, downloads) as ArtistWithAlbumsID3
        assertEquals(listOf("al-1", "al-2"), page.albums!!.map { it.id })
        assertEquals(11, page.albums!![0].songCount) // al-1 as cached
        assertEquals("Звезда по имени Солнце", page.albums!![1].name) // al-2 built from the downloads
        assertTrue(page.appearsOn!!.isEmpty())
        assertEquals("ar-1-cover", page.coverArtId)

        // never opened online: built from the downloads
        val built = HomeOffline.artistOf("ar-2", null, downloads) as ArtistWithAlbumsID3
        assertEquals("ЛСП", built.name)
        assertEquals(listOf("al-3"), built.albums!!.map { it.id })

        // cached, nothing on the phone: an empty page
        val empty = HomeOffline.artistOf("ar-404", ArtistWithAlbumsID3(albums = listOf(album("al-404", "Нет"))).apply { id = "ar-404" }, downloads)
        assertTrue((empty as ArtistWithAlbumsID3).albums!!.isEmpty())

        assertNull(HomeOffline.artistOf("ar-404", null, downloads))
    }

    @Test
    fun artistSongsOnThePhone() {
        assertEquals(listOf("s1", "s3", "s9"), HomeOffline.songsBy("кино", downloads).map { it.id })
        assertTrue(HomeOffline.songsBy(null, downloads).isEmpty())
        assertTrue(HomeOffline.songsBy("Аквариум", downloads).isEmpty())
    }
}
