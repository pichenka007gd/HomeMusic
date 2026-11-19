package com.eddyizm.tempus.homemusic

import android.util.Log
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.activity.MainActivity
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Plays Hub lists in the app's own player: the PWA hands over Navidrome song ids (the Hub knows them for
 * every imported track) and catalog tracks that are not downloaded yet; this makes them one queue.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubPlayer {
    private const val TAG = "HubPlayer"

    // getSong is one song per request; an album's worth goes out a few at a time.
    private val fetcher = Executors.newFixedThreadPool(4)

    @Volatile
    private var generation = 0

    fun play(activity: MainActivity, ids: List<String>, index: Int) {
        playQueue(activity, ids.map { HubTracks.Item().apply { songId = it } }, index)
    }

    /** What each queue entry was played from; a library song may end up streamed by its ref ([resolve]). */
    fun contexts(items: List<HubTracks.Item>): Map<String, String> = items.flatMap { item ->
        val context = item.context?.takeIf { it.isNotBlank() }?.take(128) ?: return@flatMap emptyList()
        listOfNotNull(
            item.songId?.takeIf { it.isNotBlank() },
            item.ref?.takeIf { it.isNotBlank() }?.let { HubTracks.ID_PREFIX + it },
        ).map { it to context }
    }.toMap()

    /**
     * The launch of each queue entry: the page's own (`launch_id` of the items, one per list) or, for a caller
     * without one, a new launch for this queue with [origin]. Entries the page gave none share the new one.
     */
    fun launches(items: List<HubTracks.Item>, origin: String?): Map<String, Launch> {
        val fresh = Launch.fresh(origin)
        return items.flatMap { item ->
            val launch = Launch.of(item.launchId, item.launchOrigin ?: origin) ?: fresh
            listOfNotNull(
                item.songId?.takeIf { it.isNotBlank() },
                item.ref?.takeIf { it.isNotBlank() }?.let { HubTracks.ID_PREFIX + it },
            ).map { it to launch }
        }.toMap()
    }

    /**
     * The queue entries of [items], in order (null — a song Navidrome did not find); a worker thread. A library
     * song that also has a catalog ref streams from the Hub when Navidrome does not answer.
     */
    fun resolve(items: List<HubTracks.Item>): List<Child?> {
        items.forEach { item ->
            if (!item.ref.isNullOrBlank() && !item.songId.isNullOrBlank()) {
                com.eddyizm.tempus.util.FavoriteRegistry.alias(com.eddyizm.tempus.util.FavoriteRegistry.Kind.SONG,
                    HubTracks.ID_PREFIX + item.ref, item.songId)
            }
        }
        val browsing = App.getSubsonicClientInstance(false).getBrowsingClient()
        val futures = items.map { item ->
            val id = item.songId?.takeIf { it.isNotBlank() }
            if (id == null) {
                CompletableFuture.completedFuture(HubTracks.child(item))
            } else {
                fetcher.submit(Callable {
                    runCatching { browsing.getSong(id).execute().body()?.subsonicResponse?.song }
                        .onFailure { Log.w(TAG, "getSong($id)", it) }
                        .getOrNull()
                })
            }
        }
        return futures.mapIndexed { i, f ->
            runCatching { f.get(30, TimeUnit.SECONDS) }.getOrNull()
                ?: items[i].takeIf { it.songId != null }?.let(HubTracks::child)
        }
    }

    /** Library songs are fetched from Navidrome, catalog tracks stream from the Hub ([HubTracks]); one queue. */
    fun playQueue(activity: MainActivity, items: List<HubTracks.Item>, index: Int, origin: String? = null) {
        if (items.isEmpty()) return
        val start = index.coerceIn(0, items.size - 1)
        val current = ++generation
        HomeMusic.io.execute {
            val songs = resolve(items)
            val queue: List<Child> = songs.filterNotNull()
            // the tapped song, or (Navidrome did not find it) the next one that came
            val wanted = songs.drop(start).firstOrNull { it != null } ?: queue.firstOrNull()
            HomeMusic.main.post {
                // A newer tap won, or the activity went away while the songs were coming.
                if (current != generation || activity.isFinishing || activity.isDestroyed) return@post
                if (wanted == null) {
                    Toast.makeText(activity, R.string.homemusic_play_failed, Toast.LENGTH_SHORT).show()
                    return@post
                }
                HubPlays.expectQueue(queue, contexts(items), launches(items, origin))
                MediaManager.startQueue(activity.mediaBrowserListenableFuture, queue, queue.indexOf(wanted).coerceAtLeast(0))
                activity.setBottomSheetInPeek(true)
            }
        }
    }
}
