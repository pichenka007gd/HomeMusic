package com.eddyizm.tempus.homemusic

import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import com.eddyizm.tempus.ui.activity.MainActivity
import java.lang.ref.WeakReference

/**
 * One player at a time: the Hub page's own listening (catalog tracks before download, an `<audio>`
 * in the WebView) and the app's player. The page asks to pause the app's player when it starts
 * (`HomeMusicAndroid.pauseNative()`); when the app's player starts, the page gets `hm:native-playing`.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubAudio {
    const val NATIVE_PLAYING_EVENT = "hm:native-playing"

    // Each activity start builds a new MediaBrowser; the listener goes onto every one once.
    private var watched = WeakReference<MediaBrowser>(null)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) HubWebHolder.dispatchEvent(NATIVE_PLAYING_EVENT)
        }
    }

    /** Starts telling the page when the app's player starts. */
    fun watch(activity: MainActivity) {
        val future = activity.mediaBrowserListenableFuture ?: return
        future.addListener({
            val browser = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (watched.get() === browser) return@addListener
            browser.addListener(listener)
            watched = WeakReference(browser)
        }, ContextCompat.getMainExecutor(activity))
    }

    /** The page started playing: pause the app's player. */
    fun pauseNative(activity: MainActivity) {
        watch(activity)
        val future = activity.mediaBrowserListenableFuture ?: return
        future.addListener({
            val browser = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (browser.isPlaying || browser.playWhenReady) browser.pause()
        }, ContextCompat.getMainExecutor(activity))
    }
}
