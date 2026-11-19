package com.eddyizm.tempus.homemusic

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.eddyizm.tempus.subsonic.models.Child
import com.eddyizm.tempus.util.Constants
import com.eddyizm.tempus.util.FavoriteRegistry
import kotlin.math.roundToInt

/**
 * The one track menu (SongBottomSheetDialog) is opened from a row's ⋮, a long press and the
 * player's ⋮. These are its HomeMusic additions.
 */
object HomeSongSheet {
    /** Set when the sheet is opened from the expanded player, which must stay open for queue actions. */
    const val ARG_FROM_PLAYER = "homemusic_song_sheet_from_player"

    /** Set when the sheet is opened from «Понравившиеся»: it offers to delete the song from there. */
    const val ARG_FROM_LIKED = "homemusic_song_sheet_from_liked"

    /**
     * The fragment result the sheet sends to the «Понравившиеся» page with the song in
     * [Constants.TRACK_OBJECT]: the page unlikes it and drops its row, with an undo.
     */
    const val RESULT_REMOVE_FROM_LIKED = "homemusic_remove_from_liked"

    /**
     * Sizes every row icon under [root] to 24dp and tints it like its label. The drawables in use
     * come in 20dp and 24dp, which would leave the labels unaligned.
     */
    @JvmStatic
    fun normalizeIcons(root: ViewGroup) {
        val size = (24 * root.resources.displayMetrics.density).roundToInt()
        for (i in 0 until root.childCount) {
            val row = root.getChildAt(i) as? TextView ?: continue
            val icons = row.compoundDrawablesRelative
            val icon = icons[0]?.mutate() ?: continue
            icon.setBounds(0, 0, size, size)
            row.compoundDrawableTintList?.let { icon.setTintList(it) }
            row.setCompoundDrawablesRelative(icon, icons[1], icons[2], icons[3])
        }
    }

    /** Shows «Удалить из понравившихся» on [row] when [sheet] is opened from that page for a liked [song]. */
    @JvmStatic
    fun bindRemoveFromLiked(sheet: DialogFragment, row: View, song: Child) {
        val liked = FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, song.id, song.starred != null)
        if (!showsRemoveFromLiked(sheet.arguments?.getBoolean(ARG_FROM_LIKED) == true, liked, song.id)) {
            row.visibility = View.GONE
            return
        }
        row.visibility = View.VISIBLE
        row.setOnClickListener {
            val manager = sheet.parentFragmentManager
            sheet.dismiss()
            manager.setFragmentResult(RESULT_REMOVE_FROM_LIKED, Bundle().apply { putParcelable(Constants.TRACK_OBJECT, song) })
        }
    }

    internal fun showsRemoveFromLiked(fromLiked: Boolean, liked: Boolean, songId: String) =
        fromLiked && liked && songId.isNotBlank()
}
