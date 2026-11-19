package com.eddyizm.tempus.homemusic

import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.DefaultTimeBar
import com.google.android.material.color.MaterialColors
import com.eddyizm.tempus.R

/**
 * The seek bar of the player screen: the loaded part of the track stands out from both the played and the
 * unloaded parts. The layouts give it `colorOnSecondaryContainer`, which is all but the played
 * `colorOnPrimaryContainer` in every theme (#E8DEF8 and #EADDFF in the dark one), so the loaded part merged with
 * the played one; it is now a shade halfway between the played and the unloaded colors.
 */
@UnstableApi
object PlayerSeekBar {
    /** How far the loaded part goes from the unloaded color towards the played one. */
    const val BUFFERED_SHARE = 0.45f

    @JvmStatic
    fun style(bar: DefaultTimeBar) {
        val played = MaterialColors.getColor(bar, R.attr.colorOnPrimaryContainer)
        val unplayed = MaterialColors.getColor(bar, R.attr.colorPrimaryContainer)
        bar.setBufferedColor(bufferedColor(played, unplayed))
    }

    /** The opaque color [BUFFERED_SHARE] of the way from [unplayed] to [played] (ARGB, channel by channel). */
    fun bufferedColor(played: Int, unplayed: Int): Int {
        fun mix(shift: Int): Int {
            val from = (unplayed ushr shift) and 0xFF
            val to = (played ushr shift) and 0xFF
            return (from + (to - from) * BUFFERED_SHARE + 0.5f).toInt() shl shift
        }
        return (0xFF shl 24) or mix(16) or mix(8) or mix(0)
    }
}
