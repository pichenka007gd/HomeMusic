package com.eddyizm.tempus.homemusic

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Constants
import com.google.android.material.snackbar.Snackbar
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors

/**
 * «Не нравится» (MIXES.md 3.2) in the track menu and the player, for a library song and a catalog track: the Hub
 * keeps it out of mixes and «Моя волна» (`POST /api/dislikes`), the track playing now is skipped, and the snackbar
 * takes it back (`DELETE`). Offline the mark goes with the listening events ([HubPlays]: `dislike`/`undislike`).
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubDislikes {
    private const val TAG = "HubDislikes"

    // a mark and its undo reach the Hub in the order they were made
    private val serial = Executors.newSingleThreadExecutor()

    @JvmStatic
    fun applies(song: Child?): Boolean = HubConfig.isEnabled() && song != null && song.id.isNotEmpty() &&
        song.type != Constants.MEDIA_TYPE_PODCAST && song.type != Constants.MEDIA_TYPE_RADIO

    /** The «Не нравится» row of the track menu. */
    @JvmStatic
    fun bindSheetRow(row: View?, song: Child, dismiss: Runnable) {
        row ?: return
        if (!applies(song)) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.setOnClickListener {
            val activity = activityOf(it.context)
            dismiss.run()
            if (activity != null) dislike(activity, song)
        }
    }

    /**
     * The 👎 next to ♥ in the expanded player, made here once (every layout of the player has ♥ in a
     * ConstraintLayout) and bound to the song shown.
     */
    @JvmStatic
    fun bindPlayer(root: View, song: Child?) {
        if (!HubConfig.isEnabled()) return
        val button = root.findViewById(R.id.homemusic_player_dislike) ?: addPlayerButton(root) ?: return
        button.visibility = if (applies(song)) View.VISIBLE else View.GONE
        button.setOnClickListener {
            val activity = activityOf(it.context) ?: return@setOnClickListener
            if (song != null) dislike(activity, song)
        }
    }

    private fun addPlayerButton(root: View): View? {
        val favorite = root.findViewById<View>(R.id.button_favorite) ?: return null
        val parent = favorite.parent as? ConstraintLayout ?: return null
        val context = root.context
        val button = ImageButton(context).apply {
            id = R.id.homemusic_player_dislike
            contentDescription = context.getString(R.string.homemusic_dislike)
            setImageResource(R.drawable.ic_homemusic_dislike)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val ripple = TypedValue()
            context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
            setBackgroundResource(ripple.resourceId)
            setPadding(0, 0, 0, 0)
        }
        val size = favorite.layoutParams.width
        val gap = (16 * context.resources.displayMetrics.density).toInt()
        val params = ConstraintLayout.LayoutParams(size, favorite.layoutParams.height).apply {
            topToTop = favorite.id
            bottomToBottom = favorite.id
            endToStart = favorite.id
            marginEnd = gap
        }
        // the title and the artist end where the new button starts
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            val lp = child.layoutParams as? ConstraintLayout.LayoutParams ?: continue
            if (lp.endToStart == favorite.id) {
                lp.endToStart = button.id
                child.layoutParams = lp
            }
        }
        parent.addView(button, params)
        return button
    }

    /** Marks [song]; skips it when it is the one playing. */
    @JvmStatic
    fun dislike(activity: MainActivity, song: Child) {
        val id = song.id
        val context = HubPlays.contextOf(id)
        skipIfPlaying(activity, id)
        var queued = false
        serial.execute {
            try {
                HubClient.dislike(id, song.artist, song.title, context, HubPlays.client)
                HubWave.onDisliked(id)
            } catch (e: Exception) {
                Log.w(TAG, "dislike $id: ${e.message}")
                queued = true
                HubPlays.record(PlayEvent.of(PlayEvent.DISLIKE, id, HubPlays.client, System.currentTimeMillis(), context = context))
            }
        }
        val view = activity.findViewById<View>(android.R.id.content) ?: return
        Snackbar.make(view, R.string.homemusic_disliked, Snackbar.LENGTH_LONG)
            .setAnchorView(activity.snackbarAnchor)
            .setAction(R.string.homemusic_dislike_undo) {
                serial.execute {
                    // a mark that is still waiting with the events is taken back there too, after it
                    if (queued) undislikeLater(id)
                    else try {
                        HubClient.undislike(id, HubPlays.client)
                    } catch (e: Exception) {
                        Log.w(TAG, "undislike $id: ${e.message}")
                        undislikeLater(id)
                    }
                }
            }
            .show()
    }

    internal fun activityOf(context: Context?): MainActivity? {
        var c = context
        while (c is ContextWrapper) {
            if (c is MainActivity) return c
            c = c.baseContext
        }
        return null
    }

    private fun undislikeLater(id: String) =
        HubPlays.record(PlayEvent.of(PlayEvent.UNDISLIKE, id, HubPlays.client, System.currentTimeMillis()))

    private fun skipIfPlaying(activity: MainActivity, id: String) {
        val future = activity.mediaBrowserListenableFuture ?: return
        future.addListener({
            val browser = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (browser.currentMediaItem?.mediaId != id) return@addListener
            if (browser.hasNextMediaItem()) browser.seekToNextMediaItem() else browser.pause()
        }, MoreExecutors.directExecutor())
    }
}
