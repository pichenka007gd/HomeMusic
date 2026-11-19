package com.eddyizm.tempus.homemusic

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.eddyizm.tempus.R

/** The settings item «Выйти»: its title is red, as the PWA's logout. */
class HubLeavePreference(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {
    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        (holder.findViewById(android.R.id.title) as? TextView)
            ?.setTextColor(ContextCompat.getColor(context, R.color.homemusic_danger))
    }
}
