package com.eddyizm.tempus.homemusic

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Playlist
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants

/**
 * Главная: «Моя волна» (starts the user's own wave in the app's player, [HubWave]) and «Микс дня» (the user's
 * Navidrome playlist «Микс дня», its id from `GET /api/mixes/today`; ▶ plays the mix with its catalog tracks too).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HomeMixCards {
    private const val TAG = "HomeMixCards"

    /** The row, or null outside HomeMusic; the fragment puts it above its sections. */
    @JvmStatic
    fun create(fragment: Fragment, parent: ViewGroup): View? {
        if (!HubConfig.isEnabled()) return null
        val root = LayoutInflater.from(parent.context).inflate(R.layout.homemusic_home_mixes, parent, false)
        root.findViewById<View>(R.id.homemusic_wave_card).setOnClickListener {
            (fragment.activity as? MainActivity)?.let { HubWave.start(it, null, null, WaveOrigin.HOME_CARD) }
        }
        root.findViewById<View>(R.id.homemusic_mix_card).setOnClickListener { openMix(fragment, root.tag as? HubMixToday) }
        root.findViewById<View>(R.id.homemusic_mix_play).setOnClickListener { playMix(fragment, (root.tag as? HubMixToday)?.mix) }
        show(root, null)
        return root
    }

    /** Asks the Hub for today's mix (on every return to Главная: it is replaced each morning). */
    @JvmStatic
    fun refresh(fragment: Fragment, root: View?) {
        root ?: return
        HomeMusic.io.execute {
            val today = runCatching { HubClient.mixToday() }.onFailure { Log.w(TAG, "mix: ${it.message}") }.getOrNull()
            HomeMusic.main.post {
                if (fragment.view == null || today == null) return@post
                root.tag = today
                show(root, today)
            }
        }
    }

    private fun show(root: View, today: HubMixToday?) {
        val res = root.resources
        val mix = today?.mix
        root.findViewById<TextView>(R.id.homemusic_mix_title).text = mix?.title?.takeIf { it.isNotBlank() } ?: res.getString(R.string.homemusic_mix_title)
        root.findViewById<TextView>(R.id.homemusic_mix_subtitle).text = subtitle(root, today)
        root.findViewById<View>(R.id.homemusic_mix_play).visibility = if (mix?.tracks.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun subtitle(root: View, today: HubMixToday?): String {
        val res = root.resources
        val mix = today?.mix
        return when {
            today == null -> res.getString(R.string.homemusic_mix_title)
            mix != null -> listOfNotNull(
                res.getString(R.string.homemusic_mix_title).takeIf { mix.title != null },
                res.getQuantityString(R.plurals.homemusic_mix_songs, mix.total, mix.total),
            ).joinToString(" · ")
            today.building -> res.getString(R.string.homemusic_mix_building)
            today.onboarding -> res.getString(R.string.homemusic_mix_onboarding)
            else -> res.getString(R.string.homemusic_mix_none)
        }
    }

    /** The Navidrome playlist «Микс дня»; no mix yet — the Hub's pages (picking artists, the build under way). */
    private fun openMix(fragment: Fragment, today: HubMixToday?) {
        val activity = fragment.activity as? MainActivity ?: return
        val mix = today?.mix
        val playlistId = mix?.ndPlaylistId
        if (mix == null || playlistId.isNullOrBlank()) {
            HubNavigator.open(activity, if (today?.onboarding == true) "/taste" else "/")
            return
        }
        HomeMusic.io.execute {
            val found = runCatching {
                App.getSubsonicClientInstance(false).playlistClient.getPlaylist(playlistId).execute().body()?.subsonicResponse?.playlist
            }.getOrNull()
            HomeMusic.main.post {
                if (fragment.view == null) return@post
                if (found == null) {
                    // not in Navidrome yet (the first build is downloading): the mix page of the Hub
                    HubNavigator.open(activity, "/mix/${mix.id}")
                    return@post
                }
                // a plain Playlist: the page keeps it in its arguments
                val playlist = Playlist(found.id, found.name ?: mix.title, found.duration, found.coverArtId).apply { songCount = found.songCount }
                val bundle = Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, playlist) }
                runCatching { activity.navController.navigate(R.id.action_homeFragment_to_playlistPageFragment, bundle) }
                    .onFailure { Log.w(TAG, "open the mix", it) }
            }
        }
    }

    /** ▶: the whole mix in the app's player, catalog tracks not downloaded yet included (`context mix:<id>`). */
    private fun playMix(fragment: Fragment, mix: HubMix?) {
        val activity = fragment.activity as? MainActivity ?: return
        val tracks = mix?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            Toast.makeText(activity, R.string.homemusic_play_failed, Toast.LENGTH_SHORT).show()
            return
        }
        HubPlayer.playQueue(activity, tracks.map { it.item("mix:${mix!!.id}") }, 0, "mix_card")
    }
}
