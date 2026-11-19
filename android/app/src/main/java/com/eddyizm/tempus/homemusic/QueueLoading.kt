package com.eddyizm.tempus.homemusic

import android.net.Uri
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import kotlin.math.max

/**
 * What the phone loads of the play queue: the playing track whole, and of the next tracks only their first
 * [HEAD_S] seconds (the precache, `QueuePreloader`). The rest stays on the server: the Hub downloads a catalog track
 * to its disk when its head is asked for and gives the rest out at once when the track comes up; library songs are
 * on the server anyway. So the playing track never shares the connection with whole files of the queue.
 *
 * On a metered network with the saving on ([HomeTraffic.economy]) the first seconds of a track load only
 * [ECONOMY_AHEAD_S] seconds ahead: most skips come in the first half minute, and a whole file thrown away is
 * the biggest waste of mobile traffic. Past [ECONOMY_WHOLE_AFTER_S] the track loads whole again, to get through a tunnel.
 */
@UnstableApi
object QueueLoading {
    /** Seconds of a next track the precache loads. */
    const val HEAD_S = 15

    /** The same when saving traffic (the player starts a track from its head and goes on at once). */
    const val ECONOMY_HEAD_S = 10

    /** Saving: how far ahead of the position the player loads while the playing track is young. */
    const val ECONOMY_AHEAD_S = 40

    /** Saving: from this position of the playing track on it loads whole. */
    const val ECONOMY_WHOLE_AFTER_S = 30

    /** The player decides whether to go on loading each time this many bytes came in (the default 1 MB is 25-60 s of audio). */
    const val CHECK_INTERVAL_BYTES = 256 * 1024

    /** The player starts loading the next track this many seconds before the playing one ends (a seamless switch). */
    const val NEXT_LEAD_S = 20

    private const val TRACK_BUFFER_MS = 20 * 60 * 1000 // the whole song (the size cap of the buffer still holds)
    private const val DEFAULT_KBPS = 320 // a catalog track's bitrate is not known before the Hub picks its file
    private const val HEAD_SLACK_BYTES = 64 * 1024L // tags and cover art before the audio
    private const val MIN_HEAD_BYTES = 256 * 1024L

    /** The player's buffer: the playing track whole, the next one only in its last [NEXT_LEAD_S] seconds. */
    fun loadControl(): LoadControl = NextTrackGate(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                TRACK_BUFFER_MS,
                TRACK_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            )
            .build()
    )

    /**
     * Whether the player may go on loading. ExoPlayer gives the position in the period being loaded: once the
     * playing track is loaded whole, that is the next playlist item, and the position is negative (minus the time
     * until it starts). [bufferedUs] — what is loaded ahead of that position; [economy] — [HomeTraffic.economy].
     */
    fun mayLoad(loadingPositionUs: Long, bufferedUs: Long = 0, economy: Boolean = false): Boolean {
        if (loadingPositionUs < -NEXT_LEAD_S * 1_000_000L) return false
        return !(economy && loadingPositionUs < ECONOMY_WHOLE_AFTER_S * 1_000_000L && bufferedUs >= ECONOMY_AHEAD_S * 1_000_000L)
    }

    /** Bytes of the first seconds ([HEAD_S], [ECONOMY_HEAD_S] when saving) of a file of [bitrateKbps] (0: not known). */
    fun headBytes(bitrateKbps: Int, economy: Boolean = false): Long {
        val kbps = if (bitrateKbps > 0) bitrateKbps else DEFAULT_KBPS
        return max(MIN_HEAD_BYTES, kbps * 1000L / 8 * (if (economy) ECONOMY_HEAD_S else HEAD_S) + HEAD_SLACK_BYTES)
    }

    /** A stream the player can continue past a cached head with a Range request: the Hub's, or Navidrome's file as is. */
    fun continuable(uri: Uri): Boolean =
        HubTracks.isStreamUri(uri) || untranscoded(uri.getQueryParameter("format"), uri.getQueryParameter("maxBitRate"))

    /** Navidrome's `stream` without a transcode (a transcode answers a Range from the start). */
    fun untranscoded(format: String?, maxBitRate: String?): Boolean =
        (format == null || format == "raw") && (maxBitRate == null || maxBitRate == "0")

    // Every method DefaultLoadControl implements is passed on by hand: Kotlin's `by` skips Java default methods,
    // and their defaults fall back to older overloads that DefaultLoadControl no longer implements.
    private class NextTrackGate(private val inner: DefaultLoadControl) : LoadControl {
        override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean =
            mayLoad(parameters.playbackPositionUs, parameters.bufferedDurationUs, HomeTraffic.economy()) &&
                inner.shouldContinueLoading(parameters)

        override fun onPrepared(playerId: PlayerId) = inner.onPrepared(playerId)
        override fun onTracksSelected(
            parameters: LoadControl.Parameters,
            trackGroups: TrackGroupArray,
            trackSelections: Array<out ExoTrackSelection?>,
        ) = inner.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun onStopped(playerId: PlayerId) = inner.onStopped(playerId)
        override fun onReleased(playerId: PlayerId) = inner.onReleased(playerId)
        override fun getAllocator(playerId: PlayerId): Allocator = inner.getAllocator(playerId)
        override fun getBackBufferDurationUs(playerId: PlayerId): Long = inner.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean =
            inner.retainBackBufferFromKeyframe(playerId)
        override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean =
            inner.shouldStartPlayback(parameters)
        override fun shouldContinuePreloading(
            playerId: PlayerId,
            timeline: Timeline,
            mediaPeriodId: MediaSource.MediaPeriodId,
            bufferedDurationUs: Long,
        ): Boolean = inner.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
    }
}
