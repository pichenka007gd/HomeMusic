package com.eddyizm.tempus.homemusic

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.lan.LanRemoteSession
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.subsonic.utils.CacheUtil
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.MappingUtil
import com.eddyizm.tempus.util.Preferences
import java.lang.ref.WeakReference

/**
 * «Умное перемешивание» in the app's player (MIXES.md 8; the rules are in `SmartShuffle.kt`): a playlist or
 * «Понравившиеся» plays in the Hub's order with recommendations "in the mood of the list" between its songs. The Hub
 * keeps all the logic: [startFromPage] / [startFromQueue] get the order and the first recommendations
 * (`POST /api/smart-shuffle/start`), then on every start of an entry of the list [ask] gives the Hub the events of the
 * recommendations and where the player is (`/next`), and what it answers goes in after the own songs it names.
 * «+» ([accept]) puts the recommendation in the playlist (in «Понравившиеся» the page's ♥ does it), «−» ([hide]) skips
 * it and the Hub never recommends it in this list again. The queue is edited on the service's player ([attach]), so it
 * goes on with the app in the background. In the smart shuffle Media3's own shuffle is off. Without the Hub (offline, an
 * error) the plain shuffle plays and a short message says so. Everything here runs on the main thread, the calls on
 * [HomeMusic.io].
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubSmart {
    private const val TAG = "HubSmart"

    // a queue the page started is not in the player at once: the Hub's answer waits for its first song, this long at most
    private const val WAIT_TRIES = 20
    private const val WAIT_MS = 250L

    private var target = WeakReference<MediaManager.QueueTarget>(null)
    private var session: SmartSession? = null
    private var retry: Runnable? = null
    private var starting = false

    // the playlists' names the pages told, for «Рекомендация на основе «…»» (a context → its name)
    private val titles = HashMap<String, String>()

    private val changed = MutableLiveData(0L)

    /** Bumped on every change the screens show (a session began or ended, a recommendation was added). */
    val version: LiveData<Long> get() = changed

    private fun bump() {
        changed.value = (changed.value ?: 0L) + 1
    }

    /** The playback service: the smart shuffle edits its player. */
    @JvmStatic
    fun attach(service: MediaManager.QueueTarget) {
        target = WeakReference(service)
    }

    /** A playlist's page tells its name ([context] — `playlist:<id>`). */
    @JvmStatic
    fun noteTitle(context: String, title: String?) {
        if (!title.isNullOrBlank()) titles[context] = title
    }

    // --- what the screens ask -------------------------------------------------------------------

    @JvmStatic
    fun isActive(): Boolean = session != null

    /** [mediaId] is a recommendation of the smart shuffle that plays now. */
    @JvmStatic
    fun isRec(mediaId: String?): Boolean = session?.isRec(mediaId) == true

    /** ✨ before the artist of a recommendation, in the player, the mini player and the queue. */
    @JvmStatic
    fun artistText(artist: CharSequence?, mediaId: String?): CharSequence? =
        if (!artist.isNullOrEmpty() && isRec(mediaId)) "✨ $artist" else artist

    /** «Рекомендация на основе «<плейлист>»»: the line over the player's buttons; null for any other song. */
    @JvmStatic
    fun label(context: Context, mediaId: String?): String? {
        val s = session ?: return null
        if (!s.isRec(mediaId)) return null
        return when {
            s.context == SmartContexts.LIKED -> context.getString(R.string.homemusic_smart_label_liked)
            else -> (s.title ?: titles[s.context])?.let { context.getString(R.string.homemusic_smart_label_playlist, it) }
                ?: context.getString(R.string.homemusic_smart_label_playlist_unnamed)
        }
    }

    /** «+» adds to a playlist, or likes in «Понравившиеся» (the player's ♥ then does it). */
    @JvmStatic
    fun addsToLiked(): Boolean = session?.context == SmartContexts.LIKED

    // --- the shuffle button ---------------------------------------------------------------------

    private fun livePlayer(): Player? {
        if (LanRemoteSession.isActive()) return null
        return target.get()?.livePlayer()
    }

    private fun items(player: Player): List<MediaItem> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }

    /** The list the playing queue was started from, when the smart shuffle may be offered for it. */
    private fun offeredContext(player: Player): String? {
        if (!HubConfig.isEnabled() || !Preferences.isSmartShuffleInModes() || !CacheUtil.isConnected()) return null
        return SmartContexts.dominant(items(player).map { HubPlays.contextOf(it.mediaId) })
    }

    /**
     * The player's shuffle button: off → shuffle → smart → off. False when this is not ours to do (a remote
     * player, no service): the caller toggles Media3's shuffle as before.
     */
    @JvmStatic
    fun onShuffleButton(context: Context): Boolean {
        if (!HubConfig.isEnabled()) return false
        val player = livePlayer() ?: return false
        if (starting) return true
        val mode = ShuffleMode.of(player.shuffleModeEnabled, session != null)
        when (mode.next(offeredContext(player) != null)) {
            ShuffleMode.OFF -> {
                if (mode == ShuffleMode.SMART) leave(player)
                player.shuffleModeEnabled = false
                Preferences.setSmartShuffleChosen(false)
            }
            // the plain shuffle keeps a remembered smart choice: an album has no smart mode, the next playlist does
            ShuffleMode.SHUFFLE -> player.shuffleModeEnabled = true
            ShuffleMode.SMART -> {
                Preferences.setSmartShuffleChosen(true)
                startFromQueue(context.applicationContext, player)
            }
        }
        bump()
        return true
    }

    /** The mode the button shows now (a remote LAN player has the plain toggle only). */
    @JvmStatic
    fun mode(player: Player?): ShuffleMode =
        ShuffleMode.of(player?.shuffleModeEnabled == true, (session != null || starting) && !LanRemoteSession.isActive())

    /** The notification's and the widget's shuffle button: in the smart shuffle it only turns it off; false — not ours. */
    @JvmStatic
    fun turnOffFromOutside(): Boolean {
        val player = livePlayer() ?: return false
        if (session == null) return false
        HomeMusic.main.post {
            leave(player)
            player.shuffleModeEnabled = false
            Preferences.setSmartShuffleChosen(false)
            bump()
        }
        return true
    }

    // --- starting -------------------------------------------------------------------------------

    /**
     * A playlist's or «Понравившиеся»'s own «Перемешать» button: the smart shuffle when it was chosen with the
     * player's button (and may be used here), else [fallback] — the plain shuffle as before. [songs] — the list in its
     * order (a playlist: new songs at the end; «Понравившиеся»: the newest first). A random song plays at once, as in
     * the plain shuffle (the Hub takes seconds to find recommendations); the Hub's order and its recommendations
     * replace what follows it when they come ([playInPlace]).
     */
    @JvmStatic
    fun startFromPage(activity: MainActivity, context: String, title: String?, songs: List<Child>, fallback: Runnable) {
        if (songs.isEmpty() || !SmartContexts.offered(context) || !HubConfig.isEnabled() || !Preferences.isSmartShuffleInModes() ||
            !Preferences.isSmartShuffleChosen() || LanRemoteSession.isActive()) return fallback.run()
        if (!CacheUtil.isConnected()) {
            Toast.makeText(activity, R.string.homemusic_smart_offline, Toast.LENGTH_SHORT).show()
            return fallback.run()
        }
        if (starting) return
        title?.let { noteTitle(context, it) }
        val first = songs.random()
        val queue = (listOf(first) + songs.filter { it !== first }.shuffled()).take(SmartPlan.MAX_QUEUE)
        // Media3's own shuffle is off: the Hub's order is the queue's order
        Preferences.setShuffleModeEnabled(false)
        livePlayer()?.shuffleModeEnabled = false
        MediaManager.startQueue(activity.mediaBrowserListenableFuture, queue, 0, context)
        activity.setBottomSheetInPeek(true)
        val origin = if (context == SmartContexts.LIKED) "liked_page" else "playlist_page"
        begin(activity, context, songs.map { SmartContexts.hubId(it.id) }, first.id, origin, waitFor = first.id)
    }

    /** The smart shuffle chosen with the button while a playlist plays: the queue is shuffled by the Hub from the current song. */
    private fun startFromQueue(context: Context, player: Player) {
        val ctx = offeredContext(player)
        val items = items(player)
        val at = player.currentMediaItemIndex
        if (ctx == null || at !in items.indices) {
            Toast.makeText(context, R.string.homemusic_smart_unavailable_list, Toast.LENGTH_SHORT).show()
            player.shuffleModeEnabled = false
            return
        }
        // only the list's own songs go to the Hub; what the user queued meanwhile is not the list's
        val ids = items.map { it.mediaId }.filter { HubPlays.contextOf(it) == ctx }
        begin(context, ctx, ids.map(SmartContexts::hubId), items[at].mediaId.takeIf { it in ids }, "player")
    }

    /**
     * Asks the Hub for the order and the first recommendations of the list [listContext] ([hubIds] — its songs; [current]
     * — the song that plays, a queue id). [waitFor] — the first song of a queue that was just started: the Hub's answer
     * waits until the player has it.
     */
    private fun begin(context: Context, listContext: String, hubIds: List<String>, current: String?, origin: String, waitFor: String? = null) {
        val app = context.applicationContext
        Toast.makeText(app, R.string.homemusic_smart_starting, Toast.LENGTH_LONG).show()
        starting = true
        bump()
        HomeMusic.io.execute {
            val result = runCatching {
                val body = SmartSession.startBody(listContext, hubIds, current?.let(SmartContexts::hubId), titles[listContext], origin, HubPlays.client)
                val out = HubClient.smartStart(body)
                out to prepare(out.recs.orEmpty())
            }
            HomeMusic.main.post {
                result.onSuccess { (out, prepared) -> playWhenReady(app, listContext, hubIds, out, prepared, waitFor, 0) }
                    .onFailure { e ->
                        Log.w(TAG, "start", e)
                        starting = false
                        unavailable(app, e)
                        bump()
                    }
            }
        }
    }

    /** A queue the page has just started may not be in the player yet when the Hub answers: wait for its first song a little. */
    private fun playWhenReady(context: Context, listContext: String, hubIds: List<String>, out: SmartOut, prepared: List<Prepared>,
                              waitFor: String?, attempt: Int) {
        if (waitFor != null && attempt < WAIT_TRIES && livePlayer()?.currentMediaItem?.mediaId != waitFor) {
            HomeMusic.main.postDelayed({ playWhenReady(context, listContext, hubIds, out, prepared, waitFor, attempt + 1) }, WAIT_MS)
            return
        }
        starting = false
        playInPlace(context, listContext, hubIds, out, prepared)
        bump()
    }

    /** The queue after the current song is replaced by the Hub's order with the recommendations; the song itself plays on. */
    private fun playInPlace(context: Context, listContext: String, hubIds: List<String>, out: SmartOut, prepared: List<Prepared>) {
        val queue = target.get()
        val player = livePlayer()
        val id = out.sessionId?.takeIf { it.isNotBlank() }
        if (queue == null || player == null || id == null) {
            Toast.makeText(context, R.string.homemusic_smart_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val items = items(player)
        val at = player.currentMediaItemIndex
        // another queue plays by now (the user started something else while the Hub was thinking): not ours any more
        if (at !in items.indices || SmartContexts.dominant(items.map { HubPlays.contextOf(it.mediaId) }) != listContext) return
        // Media3's own shuffle off: the timeline is then the play order
        player.shuffleModeEnabled = false
        val current = items[at]
        val ahead = items.drop(at + 1)
        val byId = ahead.associateBy { it.mediaId }
        val smart = SmartSession(id, listContext, titles[listContext], hubIds)
        val plan = SmartPlan.build(ahead.map { it.mediaId }, out.own.orEmpty(), prepared.map { it.rec }, current.mediaId)
        val byRec = prepared.associateBy { it.rec.queueId }
        val children = ArrayList<Child>()
        val mediaItems = ArrayList<MediaItem>()
        for (slot in plan) {
            val rec = slot.rec?.let { byRec[slot.mediaId] }
            if (rec != null) {
                children += rec.child
                mediaItems += rec.item
            } else {
                val item = byId[slot.mediaId] ?: continue
                children += MappingUtil.mapToChild(item) ?: continue
                mediaItems += item
            }
        }
        val recIds = smart.register(plan.mapNotNull { it.rec })
        cancelRetry()
        session = smart
        HubPlays.addContexts(recIds.associateWith { smart.recContext }, launchesOf(smart))
        // what follows the playing song goes, what the Hub ordered comes in after it; what played before stays
        if (at + 1 < items.size) MediaManager.removeRange(queue, at + 1, items.size)
        MediaManager.insertAt(queue, at + 1, children, mediaItems)
        Log.i(TAG, "start ${smart.id}: ${children.size} entries after the playing one, ${recIds.size} recommendations (was ${ahead.size})")
    }

    /** The smart shuffle is over: the own songs ahead go back to the list's order, the recommendations leave the queue. */
    private fun leave(player: Player) {
        val s = session ?: return
        end()
        val queue = target.get() ?: return
        val items = items(player)
        val at = player.currentMediaItemIndex
        if (at !in items.indices) return
        val ahead = items.drop(at + 1)
        val anchor = SmartPlan.anchor(items.map { Entry(it.mediaId, s.isRec(it.mediaId)) }, at, s::isListed)
        val order = SmartPlan.restore(s.original, ahead.map { Entry(it.mediaId, s.isRec(it.mediaId)) }, anchor)
        val byId = ahead.associateBy { it.mediaId }
        val children = ArrayList<Child>()
        val mediaItems = ArrayList<MediaItem>()
        for (id in order) {
            val item = byId[id] ?: continue
            children += MappingUtil.mapToChild(item) ?: continue
            mediaItems += item
        }
        if (at + 1 < items.size) MediaManager.removeRange(queue, at + 1, items.size)
        if (children.isNotEmpty()) MediaManager.insertAt(queue, at + 1, children, mediaItems)
    }

    private fun end() {
        session = null
        cancelRetry()
        bump()
    }

    // --- what the player does --------------------------------------------------------------------

    /** A new queue started; [contexts] — its entries' contexts: the smart shuffle goes on only when it is its own. */
    fun onQueueStarted(contexts: Map<String, String>) {
        HomeMusic.main.post {
            val s = session ?: return@post
            if (contexts.values.none { it == s.recContext }) end()
        }
    }

    /** Every listening event ([HubPlays.record]). */
    fun onEvent(event: PlayEvent) {
        HomeMusic.main.post {
            if (session?.onEvent(event) == true) ask()
        }
    }

    /** The smart shuffle's queue plays on [player] now: continuous play stays out of it ([QueueAhead]). */
    @JvmStatic
    fun owns(player: Player): Boolean {
        val s = session ?: return false
        val id = player.currentMediaItem?.mediaId
        return s.isRec(id) || s.isOwn(id)
    }

    // --- next -----------------------------------------------------------------------------------

    private fun player(smart: SmartSession): Player? {
        val player = livePlayer() ?: return null
        return player.takeIf { owns(it) }?.takeIf { session === smart }
    }

    /** The own song the player is at (or passed last), as the Hub knows it. */
    private fun ownAt(player: Player, smart: SmartSession): String? =
        SmartPlan.anchor(items(player).map { Entry(it.mediaId, smart.isRec(it.mediaId)) }, player.currentMediaItemIndex, smart::isListed)
            ?.let(SmartContexts::hubId)

    private fun ask() {
        val smart = session ?: return
        if (smart.inFlight) {
            smart.again = true
            return
        }
        val player = player(smart) ?: return
        cancelRetry()
        val request = smart.snapshot(ownAt(player, smart))
        val id = smart.id
        smart.inFlight = true
        smart.again = false
        HomeMusic.io.execute {
            var sent = request
            val result = runCatching {
                val account = CatalogFavorites.currentAccount()
                sent = request.compatible(HubClient.supportsTypedFavorites(), HubClient.supportsCompensationChains(), account)
                val out = if (sent.played.any { it.account != null })
                    HubClient.withAccountSession(requireNotNull(account)) { HubClient.smartNext(id, sent) }
                else HubClient.smartNext(id, sent)
                out to prepare(out.recs.orEmpty())
            }
            HomeMusic.main.post { answered(smart, sent, result) }
        }
    }

    /** A recommendation and how it goes into the queue (mapped on a worker thread: the mapping reads the database). */
    private class Prepared(val rec: SmartRec, val child: Child, val item: MediaItem)

    private fun prepare(recs: List<SmartRec>): List<Prepared> {
        val items = recs.map { it.item(null) }
        return HubPlayer.resolve(items).mapIndexedNotNull { i, child ->
            child ?: return@mapIndexedNotNull null
            val item = MappingUtil.mapMediaItems(listOf(child)).firstOrNull() ?: return@mapIndexedNotNull null
            Prepared(recs[i], child, item)
        }
    }

    private fun answered(smart: SmartSession, request: SmartNextIn, result: Result<Pair<SmartOut, List<Prepared>>>) {
        smart.inFlight = false
        if (session !== smart) return
        result.onSuccess { (out, prepared) ->
            smart.sent(request)
            smart.failures = 0
            apply(smart, prepared)
            if (smart.again) {
                ask()
                return
            }
            if (prepared.isEmpty() && out.pending) {
                smart.polls++
                WaveRetry.afterPending(smart.polls)?.let(::schedule)
            } else {
                smart.polls = 0
            }
        }.onFailure { e ->
            if (e is HubException && e.code == 404) {
                // the Hub forgot the session (a day without a call): the queue plays out, it brings no more
                smart.expire()
                end()
                return
            }
            Log.w(TAG, "next ${smart.id}: ${e.message}")
            if (e !is HubException) HubClient.invalidate()
            smart.failures++
            if (smart.again) WaveRetry.afterFailure(smart.failures)?.let(::schedule)
        }
    }

    /** The recommendations the Hub answered go in after the own songs they name. */
    private fun apply(smart: SmartSession, prepared: List<Prepared>) {
        if (prepared.isEmpty()) return
        val queue = target.get() ?: return
        val player = player(smart) ?: return
        val entries = items(player).map { Entry(it.mediaId, smart.isRec(it.mediaId)) }
        val inserts = SmartPlan.insertions(entries, player.currentMediaItemIndex, prepared.map { it.rec })
        if (inserts.isEmpty()) return
        val byId = prepared.associateBy { it.rec.queueId }
        val ids = smart.register(inserts.map { it.second })
        HubPlays.addContexts(ids.associateWith { smart.recContext }, launchesOf(smart))
        for ((index, rec) in inserts) {
            val p = byId[rec.queueId] ?: continue
            MediaManager.insertAt(queue, index, listOf(p.child), listOf(p.item))
        }
        Log.i(TAG, "next ${smart.id}: ${inserts.map { it.second.ref }} after ${inserts.map { it.second.after }}")
    }

    /** One launch for the whole list: the recommendations belong to it. */
    private fun launchesOf(smart: SmartSession): Map<String, Launch> {
        val launch = smart.original.firstNotNullOfOrNull { HubPlays.launchOf(SmartContexts.mediaId(it)) } ?: return emptyMap()
        return smart.recIds().associateWith { launch }
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

    // --- «+» and «−» -----------------------------------------------------------------------------

    /** «+»: [mediaId] joins the playlist (a catalog track is downloaded first and added after); the list's own song from now on. */
    @JvmStatic
    fun accept(context: Context, mediaId: String) {
        val smart = session ?: return
        if (!smart.isRec(mediaId)) return
        val app = context.applicationContext
        HomeMusic.io.execute {
            val result = runCatching { HubClient.smartAdd(smart.id, mediaId) }
            HomeMusic.main.post {
                result.onSuccess { out ->
                    smart.accept(mediaId)
                    bump()
                    val text = when (out.status) {
                        "downloading" -> R.string.homemusic_smart_downloading
                        "liked" -> R.string.homemusic_smart_liked
                        else -> R.string.homemusic_smart_added
                    }
                    Toast.makeText(app, text, Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Log.w(TAG, "add", e)
                    Toast.makeText(app, (e as? HubException)?.message ?: app.getString(R.string.homemusic_smart_failed), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** «−»: skips the playing recommendation; the Hub never recommends it in this list again (it stays everywhere else). */
    @JvmStatic
    fun hide(context: Context, mediaId: String) {
        val smart = session ?: return
        if (!smart.isRec(mediaId)) return
        val app = context.applicationContext
        livePlayer()?.let { if (it.currentMediaItem?.mediaId == mediaId && it.hasNextMediaItem()) it.seekToNextMediaItem() }
        HomeMusic.io.execute {
            val result = runCatching { HubClient.smartHide(smart.id, mediaId) }
            HomeMusic.main.post {
                result.onSuccess { Toast.makeText(app, R.string.homemusic_smart_hidden, Toast.LENGTH_SHORT).show() }
                    .onFailure { e ->
                        Log.w(TAG, "hide", e)
                        Toast.makeText(app, (e as? HubException)?.message ?: app.getString(R.string.homemusic_smart_failed), Toast.LENGTH_LONG).show()
                    }
            }
        }
    }

    private fun unavailable(context: Context, e: Throwable) {
        val text = (e as? HubException)?.takeIf { it.code in 400..499 && it.code != 404 }?.message
            ?: context.getString(R.string.homemusic_smart_unavailable)
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
