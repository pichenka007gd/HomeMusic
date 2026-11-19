package com.eddyizm.tempus.homemusic

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.service.QueuePreloader
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.utils.CacheUtil
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.MappingUtil
import java.lang.ref.WeakReference

/**
 * «Моя волна» in the app's player (MIXES.md 7): plays a wave's first tracks and keeps the queue going by itself —
 * on the start of every wave track (and after a ♥ or «Не нравится» of one) it gives the Hub the events since the
 * last call and the tracks still ahead (`POST /api/wave/{id}/next`), and the answer replaces those tracks. Offline,
 * the queue stays as it is and the call is tried again later. Another queue ends the wave.
 * The queue is edited on the service's player ([attach]), so it goes on with the app in the background.
 * Everything here runs on the main thread, the calls on [HomeMusic.io].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubWave {
    private const val TAG = "HubWave"

    // The network came back soon after the queue ran out: the wave plays on by itself.
    private const val RESUME_WITHIN_MS = 5 * 60_000L

    private var target = WeakReference<MediaManager.QueueTarget>(null)
    private var session: WaveSession? = null
    private var retry: Runnable? = null
    private var endedAt = 0L

    /** The playback service: the wave edits its player. */
    @JvmStatic
    fun attach(service: MediaManager.QueueTarget) {
        target = WeakReference(service)
    }

    // --- starting -------------------------------------------------------------------------------

    /** `HomeMusicAndroid.playWave`: the page started a wave (`POST /api/wave/start`) and hands over its answer. */
    fun play(activity: MainActivity, out: WaveOut) {
        val id = out.sessionId?.takeIf { it.isNotBlank() }
        val tracks = out.tracks.orEmpty()
        if (id == null || tracks.isEmpty()) {
            Toast.makeText(activity, R.string.homemusic_wave_empty, Toast.LENGTH_SHORT).show()
            return
        }
        cancelRetry()
        val wave = WaveSession(id, out.title, out.seed, out.mode)
        wave.register(tracks)
        session = wave
        // the start of its first track asks for what follows
        HubPlayer.playQueue(activity, tracks.map { it.item(wave.context) }, 0)
    }

    /**
     * Starts a wave from the app («Моя волна» on Главная, «Волна по треку» in the track menu, «Радиостанции»):
     * [seed] — `{ref}`, `{nd_song_id}`, `{artist_ref}`, `{nd_artist_id}` or null for the user's own. The Hub
     * failed: [fallback] when given, else a toast. [origin] — where it was started from ([WaveOrigin]).
     */
    fun start(activity: MainActivity, seed: Map<String, String>?, fallback: Runnable? = null, origin: String? = null) {
        Toast.makeText(activity, R.string.homemusic_wave_starting, Toast.LENGTH_SHORT).show()
        HomeMusic.io.execute {
            val result = runCatching {
                // origin and client only for a Hub that knows them (wave_origin_v1)
                val known = HubClient.supportsWaveOrigin()
                HubClient.waveStart(WaveSession.startBody(seed, null, origin.takeIf { known }, null, HubPlays.client.takeIf { known }))
            }
            HomeMusic.main.post {
                if (activity.isFinishing || activity.isDestroyed) return@post
                result.onSuccess { play(activity, it) }.onFailure { e ->
                    Log.w(TAG, "start", e)
                    if (fallback != null) return@onFailure fallback.run()
                    val text = (e as? HubException)?.message ?: activity.getString(R.string.homemusic_wave_failed)
                    Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** «Радиостанции» on Главная: a wave by a Navidrome artist; offline or the Hub failed — [fallback]. */
    @JvmStatic
    fun startArtist(activity: MainActivity, artistId: String, fallback: Runnable) {
        if (!CacheUtil.isConnected()) return fallback.run()
        start(activity, mapOf("nd_artist_id" to artistId), fallback, WaveOrigin.RADIO)
    }

    /** «Волна по треку» for a queue entry: a catalog track by its ref, a library song by its id. */
    fun seedOf(song: Child): Map<String, String>? = WaveSeeds.track(song.id)

    fun artistSeedOf(song: Child): Map<String, String>? = WaveSeeds.artist(song.id, song.artistId)

    @JvmStatic
    fun startArtistExact(activity: MainActivity, id: String, origin: String) {
        if (id.isNotBlank()) start(activity, mapOf("nd_artist_id" to id), null, origin)
    }

    @JvmStatic
    fun bindArtistSheetRow(row: View?, song: Child, dismiss: Runnable, fromPlayer: Boolean) =
        bindRow(row, song, dismiss, true, fromPlayer)

    /** The «Волна по треку» row of the track menu; [fromPlayer] — the menu was opened in the player. */
    @JvmStatic
    fun bindSheetRow(row: View?, song: Child, dismiss: Runnable, fromPlayer: Boolean) =
        bindRow(row, song, dismiss, false, fromPlayer)

    /** Where a wave started from the track menu was started: the player's menu is «player», the others by the row. */
    fun menuOrigin(artist: Boolean, fromPlayer: Boolean): String =
        if (fromPlayer) WaveOrigin.PLAYER else if (artist) WaveOrigin.ARTIST_MENU else WaveOrigin.TRACK_MENU

    private fun bindRow(row: View?, song: Child, dismiss: Runnable, artist: Boolean, fromPlayer: Boolean) {
        row ?: return
        val seed = if (artist) artistSeedOf(song) else seedOf(song)
        if (!HubDislikes.applies(song) || seed == null) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.setOnClickListener {
            val activity = HubDislikes.activityOf(it.context)
            dismiss.run()
            if (activity != null) start(activity, seed, null, menuOrigin(artist, fromPlayer))
        }
    }

    // --- what the player does --------------------------------------------------------------------

    /** A new queue started; [contexts] — its entries' contexts: the wave goes on only when it is the wave's own. */
    fun onQueueStarted(contexts: Map<String, String>) {
        HomeMusic.main.post {
            val wave = session ?: return@post
            if (contexts.values.none { it == wave.context }) end()
        }
    }

    /** Every listening event ([HubPlays.record]). */
    fun onEvent(event: PlayEvent) {
        HomeMusic.main.post {
            if (session?.onEvent(event) == true) ask()
        }
    }

    /** «Не нравится» went straight to the Hub (`/api/dislikes`); a wave track ahead goes at the next answer. */
    @JvmStatic
    fun onDisliked(mediaId: String) {
        HomeMusic.main.post {
            if (session?.isWave(mediaId) == true) ask()
        }
    }

    /** The queue played out (nothing ahead came in time): ask now, and play on if the answer comes soon. */
    fun onQueueEnded() {
        HomeMusic.main.post {
            val wave = session ?: return@post
            endedAt = SystemClock.elapsedRealtime()
            if (!wave.inFlight) ask()
        }
    }

    /** The wave plays on [player] now: its queue is the wave's to keep going ([QueueAhead] stays out of it). */
    @JvmStatic
    fun owns(player: Player): Boolean = session?.isWave(player.currentMediaItem?.mediaId) == true

    /** The user waits on the loading page after the last track ([QueueAhead.requestNext]). */
    @JvmStatic
    fun askNow() {
        HomeMusic.main.post {
            val wave = session ?: return@post
            wave.failures = 0
            ask()
        }
    }

    /**
     * The precache may load the heads of the tracks ahead of [player]'s: not while a wave track plays and the Hub
     * has not yet answered for it (the answer replaces the queue ahead, and the heads loaded before would be lost).
     */
    @JvmStatic
    fun preloadReady(player: Player): Boolean {
        val wave = session ?: return true
        val id = player.currentMediaItem?.mediaId
        return !wave.isWave(id) || wave.settled(id)
    }

    /** The answer is in (or the Hub does not answer): the precache may begin. */
    private fun kickPreload(wave: WaveSession) {
        val player = player(wave) ?: return
        QueuePreloader.preload(App.getContext(), player)
    }

    private fun end() {
        session = null
        cancelRetry()
    }

    // --- next -----------------------------------------------------------------------------------

    /** The service's player while it plays the wave (until the wave's queue is in, the old one plays). */
    private fun player(wave: WaveSession): Player? {
        if (LanRemoteSession.isActive()) return null
        val player = target.get()?.livePlayer() ?: return null
        return player.takeIf { wave.isWave(it.currentMediaItem?.mediaId) }
    }

    /** The wave's queue entries after the one playing, in order, with their positions. */
    private fun ahead(player: Player, wave: WaveSession): List<Pair<Int, String>> {
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        return (current + 1 until player.mediaItemCount)
            .map { it to player.getMediaItemAt(it).mediaId }
            .filter { wave.isWave(it.second) }
    }

    private fun ask() {
        val wave = session ?: return
        if (wave.inFlight) {
            wave.again = true
            return
        }
        val player = player(wave) ?: return
        cancelRetry()
        val request = wave.snapshot(ahead(player, wave).map { it.second })
        val id = wave.id
        wave.askedFor = player.currentMediaItem?.mediaId
        wave.inFlight = true
        wave.again = false
        HomeMusic.io.execute {
            var sent = request
            val result = runCatching {
                val account = CatalogFavorites.currentAccount()
                sent = request.compatible(HubClient.supportsTypedFavorites(), HubClient.supportsCompensationChains(), account)
                val out = if (sent.played.any { it.account != null })
                    HubClient.withAccountSession(requireNotNull(account)) { HubClient.waveNext(id, sent) }
                else HubClient.waveNext(id, sent)
                out to prepare(out.tracks.orEmpty(), wave.context)
            }
            HomeMusic.main.post { answered(wave, sent, result) }
        }
    }

    /** A track and how it goes into the queue (mapped on this worker thread: the mapping reads the database). */
    private class Prepared(val track: WaveTrack, val child: Child, val item: MediaItem)

    private fun prepare(tracks: List<WaveTrack>, context: String): List<Prepared> {
        val items = tracks.map { it.item(context) }
        return HubPlayer.resolve(items).mapIndexedNotNull { i, child ->
            child ?: return@mapIndexedNotNull null
            val item = MappingUtil.mapMediaItems(listOf(child)).firstOrNull() ?: return@mapIndexedNotNull null
            Prepared(tracks[i], child, item)
        }
    }

    private fun answered(wave: WaveSession, request: WaveNextIn, result: Result<Pair<WaveOut, List<Prepared>>>) {
        wave.inFlight = false
        if (session !== wave) return
        result.onSuccess { (out, prepared) ->
            wave.sent(request)
            wave.failures = 0
            apply(wave, prepared)
            wave.settledFor = wave.askedFor
            if (wave.again) {
                ask()
                return
            }
            kickPreload(wave)
            val left = player(wave)?.let { ahead(it, wave).size } ?: return
            if (prepared.isEmpty() && out.pending && left <= WaveSession.LOW_WATER) {
                wave.polls++
                WaveRetry.afterPending(wave.polls)?.let(::schedule)
            } else {
                wave.polls = 0
            }
        }.onFailure { e ->
            if (e is HubException && e.code == 404) {
                renew(wave)
                return
            }
            Log.w(TAG, "next ${wave.id}: ${e.message}")
            if (e !is HubException) HubClient.invalidate()
            wave.failures++
            kickPreload(wave)
            val left = player(wave)?.let { ahead(it, wave).size } ?: return
            if (wave.again || left <= WaveSession.LOW_WATER) WaveRetry.afterFailure(wave.failures)?.let(::schedule)
        }
    }

    /** The answer replaces the wave's tracks ahead ([WavePlan]). */
    private fun apply(wave: WaveSession, prepared: List<Prepared>) {
        val queue = target.get() ?: return
        val player = player(wave) ?: return
        val ahead = ahead(player, wave)
        val plan = WavePlan.of(ahead.map { wave.refOf(it.second).orEmpty() }, prepared.map { it.track.ref.orEmpty() })
        val add = plan.add.map { prepared[it] }
        Log.i(TAG, "next ${wave.id}: ahead ${ahead.map { wave.refOf(it.second) }} → ${prepared.map { it.track.ref }} (kept ${plan.keep})")
        if (plan.remove.isEmpty() && add.isEmpty()) return
        val at = plan.insertAt(ahead.map { it.first }, player.mediaItemCount)
        for (i in plan.remove.reversed()) {
            val index = ahead[i].first
            MediaManager.removeRange(queue, index, index + 1)
        }
        if (add.isEmpty()) return
        wave.register(add.map { it.track })
        HubPlays.addContexts(wave.ids().associateWith { wave.context }, launchesOf(wave))
        val stale = player.playbackState == Player.STATE_ENDED && SystemClock.elapsedRealtime() - endedAt > RESUME_WITHIN_MS
        MediaManager.insertAt(queue, at, add.map { it.child }, add.map { it.item })
        // the user waits on the loading page, or the queue ran out a moment ago: go on to the first new track
        if (!stale) QueueAhead.extended(player, at, add.size)
    }

    /** The Hub forgot the session (24 h, a long offline): a new one with the same seed carries the queue on. */
    private fun renew(wave: WaveSession) {
        val oldId = wave.id
        wave.inFlight = true
        HomeMusic.io.execute {
            val result = runCatching {
                val known = HubClient.supportsWaveOrigin()
                val body = WaveSession.startBody(wave.seed, wave.mode, WaveOrigin.RESTART.takeIf { known }, oldId.takeIf { known },
                    HubPlays.client.takeIf { known })
                val out = HubClient.waveStart(body)
                // a track's wave starts with that track again: it has played already
                val tracks = out.tracks.orEmpty().filter { it.generator != WaveTrack.SEED }
                val id = out.sessionId ?: throw HubException("Пустой ответ Хаба")
                Triple(id, out, prepare(tracks, WaveSession.CONTEXT_PREFIX + id))
            }
            HomeMusic.main.post {
                wave.inFlight = false
                if (session !== wave) return@post
                result.onSuccess { (id, _, prepared) ->
                    wave.renew(id)
                    HubPlays.addContexts(wave.ids().associateWith { wave.context }, launchesOf(wave))
                    apply(wave, prepared)
                    if (wave.again) ask() else kickPreload(wave)
                }.onFailure { e ->
                    Log.w(TAG, "renew: ${e.message}")
                    wave.failures++
                    WaveRetry.afterFailure(wave.failures)?.let(::schedule)
                }
            }
        }
    }

    /** The launch of every track of [wave] so far: `wave:<session>` (the same one as the context). */
    private fun launchesOf(wave: WaveSession): Map<String, Launch> {
        val launch = Launch.of(wave.context, WaveOrigin.WAVE) ?: return emptyMap()
        return wave.ids().associateWith { launch }
    }

    private fun schedule(delayMs: Long) {
        cancelRetry()
        val run = Runnable {
            retry = null
            ask()
        }
        retry = run
        HomeMusic.main.postDelayed(run, delayMs)
    }

    private fun cancelRetry() {
        retry?.let(HomeMusic.main::removeCallbacks)
        retry = null
    }
}
