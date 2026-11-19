package com.eddyizm.tempus.homemusic

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.service.MediaManager
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.Preferences
import java.util.WeakHashMap

/**
 * Keeps the queue a few tracks ahead of what plays. Tempus' continuous play (similar songs once the list is over)
 * started only on the last track, so when the user got to the end the next track was not there yet: «next» did
 * nothing and the player stayed on, and restarted, the track just heard. Now it starts while fewer than [AHEAD]
 * tracks are left and appends after the last one in play order (`MediaManager.continuousPlay`), and the precache
 * (`QueuePreloader`, the first seconds of the next [HomeDefaults.PRECACHE_TRACKS] by default, on any network) loads them ahead.
 *
 * Should the list run out all the same (a slow or failed request), the cover pager shows a loading page as the next
 * one ([showsLoadingTail]); the user going there ([requestNext]) or the track ending moves the player on to the
 * first new track as soon as it is in the queue ([extended]).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object QueueAhead {
    private const val TAG = "QueueAhead"

    /** Tracks kept in the queue after the current one. */
    const val AHEAD = 7

    // Preferences.isInstantMixUsable allows one mix per 10 s
    private const val RETRY_MS = 11_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = WeakHashMap<Player, Player.Listener>()
    private var target: MediaManager.QueueTarget? = null

    // continuous play found nothing: no new request until the player moves to another track
    private var exhausted = false

    private val waitingData = MutableLiveData(false)

    /** The user went to the loading page and waits there for the next track. */
    @JvmStatic
    val waiting: LiveData<Boolean> get() = waitingData

    private val retry = Runnable { target?.let { t -> t.livePlayer()?.let { check(it, t) } } }

    /** Hooks a player of the service (`BaseMediaService.initializePlayerListener`); safe to call many times. */
    @JvmStatic
    fun attach(player: Player, target: MediaManager.QueueTarget) {
        this.target = target
        if (listeners.containsKey(player)) return
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) exhausted = false
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED,
                    )) check(p, target)
            }
        }
        listeners[player] = listener
        player.addListener(listener)
    }

    @JvmStatic
    fun release() {
        main.removeCallbacks(retry)
        listeners.forEach { (player, listener) -> player.removeListener(listener) }
        listeners.clear()
        target = null
        waitingData.value = false
    }

    /** Asks continuous play for more tracks when the queue is short. Main thread. */
    @JvmStatic
    fun check(player: Player, target: MediaManager.QueueTarget) {
        main.removeCallbacks(retry)
        val current = player.currentMediaItem
        val left = upcoming(player)
        if (HubWave.owns(player) || HubSmart.owns(player)) {
            // «Моя волна» keeps its queue going itself (HubWave), similar songs would cut in; the smart shuffle's list has
            // its own recommendations (HubSmart) and its places are filled by the Hub
            if (left > 0) waitingData.value = false
            return
        }
        if (current == null || !wantsMore(Preferences.isContinuousPlayEnabled(), player.repeatMode, isMusic(current), left)) {
            if (left > 0 || current == null) waitingData.value = false
            return
        }
        if (MediaManager.continuousPlayIsRunning.get()) return // it calls [extended] when done
        if (exhausted) {
            waitingData.value = false
            return
        }
        val seed = seed(player) ?: return
        if (!Preferences.isInstantMixUsable()) {
            main.postDelayed(retry, RETRY_MS)
            return
        }
        Log.d(TAG, "$left track(s) ahead: continuous play from ${seed.mediaId}")
        MediaManager.continuousPlay(seed, target)
    }

    /** Continuous play has added [added] tracks at [insertPos] (`-1`, 0: none). */
    @JvmStatic
    fun extended(player: Player?, insertPos: Int, added: Int) {
        exhausted = added <= 0
        if (player != null && added > 0 && insertPos in 0 until player.mediaItemCount &&
            (waitingData.value == true || player.playbackState == Player.STATE_ENDED)) {
            // the list ran out before the tracks came: go on to the first of them
            player.seekTo(insertPos, 0)
            player.prepare()
            player.play()
        }
        waitingData.value = false
    }

    /** The loading page of the cover pager: next track now, or as soon as continuous play has it. */
    @JvmStatic
    fun requestNext() {
        val t = target ?: return
        val player = t.livePlayer() ?: return
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        if (HubSmart.owns(player)) return // the list of a smart shuffle ends with its last song
        waitingData.value = true
        if (HubWave.owns(player)) {
            HubWave.askNow()
            return
        }
        exhausted = false // asked for in person: try again
        check(player, t)
    }

    /** The pager shows a loading page after the last track: continuous play will put something there. */
    @JvmStatic
    fun showsLoadingTail(player: Player): Boolean {
        val current = player.currentMediaItem ?: return false
        if (HubWave.owns(player)) return upcoming(player) == 0
        if (HubSmart.owns(player)) return false
        return upcoming(player) == 0 &&
            wantsMore(Preferences.isContinuousPlayEnabled(), player.repeatMode, isMusic(current), 0) && !exhausted
    }

    @JvmStatic
    fun wantsMore(enabled: Boolean, repeatMode: Int, music: Boolean, upcoming: Int): Boolean =
        enabled && music && repeatMode == Player.REPEAT_MODE_OFF && upcoming < AHEAD

    /** Window indexes in the order they play (shuffle included), from the first. */
    @JvmStatic
    fun playOrder(player: Player): IntArray {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return IntArray(0)
        val shuffle = player.shuffleModeEnabled
        val order = IntArray(timeline.windowCount)
        var n = 0
        var w = timeline.getFirstWindowIndex(shuffle)
        while (w != C.INDEX_UNSET && n < order.size) {
            order[n++] = w
            w = timeline.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, shuffle)
        }
        return if (n == order.size) order else order.copyOf(n)
    }

    /** Tracks after the current one in play order, up to [AHEAD] (repeat aside). */
    @JvmStatic
    fun upcoming(player: Player): Int {
        val timeline = player.currentTimeline
        var w = player.currentMediaItemIndex
        if (timeline.isEmpty || w == C.INDEX_UNSET || w >= timeline.windowCount) return 0
        var n = 0
        while (n < AHEAD) {
            w = timeline.getNextWindowIndex(w, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (w == C.INDEX_UNSET) break
            n++
        }
        return n
    }

    /** Where new tracks go on the timeline: after the last one in play order (the shuffle fixup keeps them last). */
    @JvmStatic
    fun insertPosition(player: Player): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return player.mediaItemCount
        val last = timeline.getLastWindowIndex(player.shuffleModeEnabled)
        return if (last == C.INDEX_UNSET) player.mediaItemCount else last + 1
    }

    /**
     * What similar songs are asked for: the last Navidrome song of the queue in play order up to the current one
     * (a catalog track of the Hub is not in Navidrome), else the current track.
     */
    private fun seed(player: Player): MediaItem? {
        val current = player.currentMediaItem ?: return null
        val order = playOrder(player)
        val at = order.indexOf(player.currentMediaItemIndex)
        val ids = order.map { player.getMediaItemAt(it).mediaId }
        val i = seedIndex(ids, at)
        return if (i >= 0) player.getMediaItemAt(order[i]) else current
    }

    /** The position in [ids] (play order) of the seed: the last library song at or before [current], else -1. */
    @JvmStatic
    fun seedIndex(ids: List<String>, current: Int): Int {
        if (current !in ids.indices) return -1
        for (i in current downTo 0) if (!HubTracks.isHub(ids[i])) return i
        return -1
    }

    private fun isMusic(item: MediaItem): Boolean {
        val type = item.mediaMetadata.extras?.getString("type")
        return type != Constants.MEDIA_TYPE_RADIO && type != Constants.MEDIA_TYPE_PODCAST &&
            type != Constants.MEDIA_TYPE_AUDIOBOOK && !item.mediaId.startsWith("ir-")
    }
}
