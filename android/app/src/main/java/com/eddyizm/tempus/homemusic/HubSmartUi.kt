package com.eddyizm.tempus.homemusic

import android.graphics.drawable.Drawable
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.media3.common.Player
import androidx.media3.ui.PlayerControlView
import com.eddyizm.tempus.R
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.FavoriteRegistry
import java.util.function.Consumer
import java.util.function.Supplier

/**
 * What the smart shuffle ([HubSmart]) adds to the full player: the shuffle button's third state (a shuffle with a
 * sparkle), the line «Рекомендация на основе …» with «+» and «−» for a recommendation that plays, and ✨ before its
 * artist. The Tempus screens call [bindPlayer] once and [decorateArtist] where they write an artist.
 */
object HubSmartUi {
    private const val SPARKLE = "✨ "

    /** ✨ before [mediaId]'s artist when it is a recommendation, and none when it is not (or no more). */
    @JvmStatic
    fun decorateArtist(label: TextView?, mediaId: String?) {
        label ?: return
        val raw = label.text?.toString().orEmpty().removePrefix(SPARKLE)
        val text = HubSmart.artistText(raw, mediaId)?.toString().orEmpty()
        if (label.text?.toString() != text) label.text = text
    }

    /**
     * Wires the player screen: the shuffle button ([R.id.exo_shuffle]) cycles through three modes, the
     * recommendation line ([R.id.player_smart_rec_row]) follows the playing song. [live] — the playing song; [like] — what
     * the player's ♥ does (in «Понравившиеся» «+» is a ♥); [player] — Media3's, for the plain toggle when the smart
     * shuffle is not ours to handle (a remote player).
     */
    @JvmStatic
    fun bindPlayer(owner: LifecycleOwner, root: View, live: LiveData<Child>, like: Consumer<Child>, player: Supplier<Player?>,
                   artistLabel: TextView?) {
        val row = root.findViewById<View>(R.id.player_smart_rec_row)
        val label = root.findViewById<TextView>(R.id.player_smart_rec_label)
        val add = root.findViewById<ImageButton>(R.id.player_smart_rec_add)
        val hide = root.findViewById<ImageButton>(R.id.player_smart_rec_hide)
        val shuffle = root.findViewById<ImageButton>(R.id.exo_shuffle)

        shuffle?.setOnClickListener {
            if (!HubSmart.onShuffleButton(root.context)) player.get()?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
        }
        add?.setOnClickListener {
            val media = live.value ?: return@setOnClickListener
            if (HubSmart.addsToLiked() && !FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, media.id, media.starred != null)) like.accept(media)
            HubSmart.accept(root.context, media.id)
        }
        hide?.setOnClickListener { live.value?.let { HubSmart.hide(root.context, it.id) } }

        val refresh = Runnable {
            val media = live.value
            val text = HubSmart.label(root.context, media?.id)
            row?.visibility = if (text != null) View.VISIBLE else View.GONE
            if (text != null) {
                label?.text = SPARKLE + text
                add?.contentDescription = root.context.getString(
                    if (HubSmart.addsToLiked()) R.string.homemusic_smart_add_liked else R.string.homemusic_smart_add_playlist)
            }
            decorateArtist(artistLabel, media?.id)
            refreshShuffle(root, player.get())
        }
        live.observe(owner) { refresh.run() }
        HubSmart.version.observe(owner) { refresh.run() }
    }

    /**
     * Media3 draws the shuffle button itself (on a shuffle change, when a player is set); after it has, the smart state
     * puts its own image on, and when the smart state is over Media3 is asked to draw its own again.
     */
    @JvmStatic
    fun refreshShuffle(root: View?, player: Player?) {
        val button = root?.findViewById<ImageView>(R.id.exo_shuffle) ?: return
        val mine = button.getTag(R.id.exo_shuffle) as? Drawable
        if (HubSmart.mode(player) == ShuffleMode.SMART) {
            if (mine != null && button.drawable === mine) return
            val image = AppCompatResources.getDrawable(button.context, R.drawable.ic_shuffle_smart)
            button.setImageDrawable(image)
            button.setTag(R.id.exo_shuffle, image)
            button.contentDescription = button.context.getString(R.string.homemusic_smart_shuffle_on)
        } else if (mine != null) {
            button.setTag(R.id.exo_shuffle, null)
            var parent = button.parent
            while (parent != null && parent !is PlayerControlView) parent = parent.parent
            // the same value again: Media3 draws the button for the player's shuffle mode
            (parent as? PlayerControlView)?.let { it.showShuffleButton = it.showShuffleButton }
        }
    }
}
