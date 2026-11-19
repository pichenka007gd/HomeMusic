package com.eddyizm.tempus.homemusic

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download as MediaDownload
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.eddyizm.tempus.App
import com.eddyizm.tempus.model.Download
import com.eddyizm.tempus.repository.DownloadRepository
import com.eddyizm.tempus.service.DownloaderManager
import com.eddyizm.tempus.util.DownloadRepair
import com.eddyizm.tempus.util.DownloadUtil
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.Preferences
import java.util.concurrent.TimeUnit

/**
 * What the phone holds of the mix of the day: the songs of today's mix, downloaded in «Скачанное» while the phone is
 * on an unmetered network and charging, so the mix plays without mobile traffic. When the mix changes, the songs
 * this download brought and nobody touched are removed (see [plan]).
 */
object MixOffline {
    const val PREF_ENABLED = "homemusic_mix_offline"
    private const val PREF_STATE = "homemusic_mix_offline_state"
    private const val WORK_PERIODIC = "homemusic_mix_offline"
    private const val WORK_NOW = "homemusic_mix_offline_now"

    /** The phone keeps at least this much free: no more songs are downloaded below it. */
    const val MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024

    @JvmStatic
    fun enabled(): Boolean = App.getInstance().preferences.getBoolean(PREF_ENABLED, true)

    /**
     * The state between runs: [mixId] — the mix it is about; [owned] — songs this download brought to the phone (only
     * those are ever removed); [tried] — songs of this mix it has already asked for (a song the user removed is not
     * downloaded again).
     */
    data class State(val mixId: Long = 0, val owned: Set<String> = emptySet(), val tried: Set<String> = emptySet()) {
        fun encode(): String = listOf(mixId.toString(), owned.joinToString(","), tried.joinToString(",")).joinToString("\n")

        companion object {
            fun decode(text: String?): State {
                val lines = text?.split("\n") ?: return State()
                val id = lines.getOrNull(0)?.toLongOrNull() ?: return State()
                fun set(i: Int) = lines.getOrNull(i).orEmpty().split(",").filter { it.isNotBlank() }.toSet()
                return State(id, set(1), set(2))
            }
        }
    }

    /** [download] — songs to bring now (in the mix's order); [remove] — songs of the earlier mix to take away; [next] — the state after the removal. */
    class Plan(val download: List<String>, val remove: List<String>, val next: State)

    /**
     * Today's mix [songs] against the [state]: what to download and what to remove. Removed are only the songs this
     * download brought ([State.owned]) that the new mix does not contain and the user has not liked ([liked]);
     * what the user downloaded themselves ([onPhone] before this download) is never owned. Downloaded are the songs
     * of the mix that are not on the phone yet and not asked for before.
     */
    fun plan(state: State, mixId: Long, songs: List<String>, onPhone: Set<String>, liked: Set<String>): Plan {
        val wanted = songs.toSet()
        val remove = state.owned.filter { it !in wanted && it !in liked }
        val kept = state.owned.filter { it in wanted }
        val tried = if (state.mixId == mixId) state.tried else emptySet()
        val download = songs.filter { it !in onPhone && it !in tried }.distinct()
        return Plan(download, remove, State(mixId, kept.toSet(), tried))
    }

    /** One more song fits: [freeBytes] left on the phone stay above [MIN_FREE_BYTES] after [songBytes]. */
    fun fits(freeBytes: Long, songBytes: Long): Boolean = freeBytes - songBytes >= MIN_FREE_BYTES

    fun load(): State = State.decode(App.getInstance().preferences.getString(PREF_STATE, null))

    fun save(state: State) {
        App.getInstance().preferences.edit().putString(PREF_STATE, state.encode()).apply()
    }

    /** Songs of the mix on the phone and the space they take (for the settings screen). Blocking: the database. */
    fun held(): Pair<Int, Long> {
        val repository = DownloadRepository()
        val songs = load().owned.mapNotNull { repository.getDownload(it) }
        return songs.size to songs.sumOf { it.size ?: 0L }
    }

    /** The periodic job and, at once, one run for today's mix (it waits for the unmetered network and the charger). */
    @JvmStatic
    fun schedule(context: Context) {
        val work = WorkManager.getInstance(context)
        if (!enabled()) {
            work.cancelUniqueWork(WORK_PERIODIC)
            work.cancelUniqueWork(WORK_NOW)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<MixOfflineWorker>(6, TimeUnit.HOURS).setConstraints(constraints()).build()
        work.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic)
        val now = OneTimeWorkRequestBuilder<MixOfflineWorker>().setConstraints(constraints()).build()
        work.enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, now)
    }

    /** Never on a metered network: the system holds the job until an unmetered one. */
    private fun constraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .build()
}

/** Brings today's mix to the phone one song at a time, so a switch to mobile data costs at most the song under way. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class MixOfflineWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (!HubConfig.isEnabled() || !MixOffline.enabled() || !DownloadRepair.isUserAuthenticated()) return Result.success()
        if (Preferences.getDownloadDirectoryUri() != null) return Result.success() // downloads go to a folder of the user's own
        if (HomeTraffic.isMetered()) return Result.retry()
        return try {
            // the periodic job and the run at the app start may meet: one at a time, the second finds nothing left
            synchronized(LOCK) { run() }
        } catch (e: Exception) {
            Log.d(TAG, "mix download: ${e.message}")
            Result.retry()
        }
    }

    private fun run(): Result {
        val mix = HubClient.mixToday().mix ?: return Result.success()
        val playlistId = mix.ndPlaylistId?.takeIf { it.isNotBlank() } ?: return Result.success()
        val client = App.getSubsonicClientInstance(false)
        val songs = client.playlistClient.getPlaylist(playlistId).execute().body()?.subsonicResponse?.playlist?.entries
            ?.filter { !HubTracks.isHub(it.id) }.orEmpty()
        if (songs.isEmpty()) return Result.success() // not built yet: the old mix stays until the new one is there
        // Without the likes nothing is removed: a liked song must stay.
        val liked = client.albumSongListClient.getStarred2().execute().body()?.subsonicResponse?.starred2?.songs
            ?.map { it.id }?.toSet() ?: return Result.retry()

        val tracker = DownloadUtil.getDownloadTracker(applicationContext)
        val state = MixOffline.load()
        val ids = songs.map { it.id }
        val onPhone = (ids + state.owned).filter { tracker.isDownloaded(it) }.toSet()
        val plan = MixOffline.plan(state, mix.id, ids, onPhone, liked)
        Log.i(TAG, "mix ${mix.id}: download ${plan.download.size}, remove ${plan.remove.size}, kept ${plan.next.owned.size}")

        removeAll(tracker, plan.remove)
        var current = plan.next
        MixOffline.save(current)

        val byId = songs.associateBy { it.id }
        val dir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        for (id in plan.download) {
            val song = byId[id] ?: continue
            if (isStopped || HomeTraffic.isMetered()) return Result.retry()
            if (tracker.isRequested(id)) continue
            if (!MixOffline.fits(dir.usableSpace, song.size ?: 0L)) {
                Log.i(TAG, "mix: no room left")
                return Result.success()
            }
            current = current.copy(owned = current.owned + id, tried = current.tried + id)
            MixOffline.save(current)
            if (!tracker.download(MappingUtil.mapDownload(song), Download(song), true)) return Result.retry()
            if (!await(tracker, id)) {
                // switched to mobile data (or stopped) under way: take the half-loaded song back, the next run goes on
                DownloadRepository().getDownload(id)?.let { tracker.remove(MappingUtil.mapDownload(it), it) }
                MixOffline.save(current.copy(owned = current.owned - id, tried = current.tried - id))
                return Result.retry()
            }
        }
        return Result.success()
    }

    private fun removeAll(tracker: DownloaderManager, ids: List<String>) {
        val repository = DownloadRepository()
        for (id in ids) {
            val download = repository.getDownload(id) ?: continue
            tracker.remove(MappingUtil.mapDownload(download), download)
        }
    }

    /** Waits for the download of [id] to end; false when the work was stopped or the network became metered. */
    private fun await(tracker: DownloaderManager, id: String): Boolean {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (tracker.getDownloadState(id)) {
                MediaDownload.STATE_COMPLETED, MediaDownload.STATE_FAILED -> return true
            }
            if (isStopped || HomeTraffic.isMetered()) return false
            Thread.sleep(1000)
        }
        return false
    }

    private companion object {
        val LOCK = Any()
        const val TAG = "MixOffline"
        const val TIMEOUT_MS = 5 * 60 * 1000L
    }
}
