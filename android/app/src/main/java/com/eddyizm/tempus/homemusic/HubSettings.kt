package com.eddyizm.tempus.homemusic

import android.text.InputType
import android.util.Log
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.database.AppDatabase
import com.eddyizm.tempus.model.Server
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.Flavors
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The «HomeMusic» block of the settings screen: Hub addresses, Google, the admin page, the update check and
 * «Выйти».
 */
object HubSettings {
    private const val TAG = "HubSettings"

    // Items shown only when the Hub says so. The settings screen makes every item visible again each time a
    // category opens or closes, then calls applyVisibility; until the Hub has answered they stay hidden.
    private var googleShown = false
    private var adminShown = false

    @JvmStatic
    fun bind(fragment: PreferenceFragmentCompat) {
        googleShown = false
        adminShown = false
        val screen = fragment.preferenceScreen ?: return
        val category = fragment.findPreference<Preference>(HubConfig.PREF_CATEGORY) ?: return
        if (!HubConfig.isEnabled()) {
            screen.removePreference(category)
            fragment.findPreference<Preference>(HubConfig.PREF_LEAVE)?.let { screen.removePreference(it) }
            return
        }

        fragment.findPreference<EditTextPreference>(HubConfig.PREF_HUB_URL)?.let { pref ->
            setUp(pref, BuildConfig.HUB_URL) { HubConfig.remoteUrl() }
        }
        fragment.findPreference<EditTextPreference>(HubConfig.PREF_HUB_LOCAL_URL)?.let { pref ->
            setUp(pref, BuildConfig.HUB_LOCAL_URL) {
                HubConfig.localUrl() ?: fragment.getString(R.string.homemusic_settings_local_none)
            }
        }
        fragment.findPreference<Preference>(HubConfig.PREF_GOOGLE)?.let { bindGoogle(fragment, it) }
        fragment.findPreference<Preference>(HubConfig.PREF_ADMIN)?.let { bindAdmin(fragment, it) }
        fragment.findPreference<Preference>(HubConfig.PREF_CHECK_UPDATE)?.let { pref ->
            pref.summary = fragment.getString(R.string.homemusic_settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
            pref.setOnPreferenceClickListener {
                HubUpdater.check(fragment.requireActivity(), manual = true)
                true
            }
        }
        fragment.findPreference<Preference>(HubConfig.PREF_LEAVE)?.let { bindLeave(fragment, it) }
        fragment.findPreference<Preference>(HubConfig.PREF_TEMPUS_LOGOUT)?.isVisible = false
        bindTraffic(fragment)
    }

    /** «Микс дня на телефон» (what it holds) and the mobile traffic counter of the player. */
    private fun bindTraffic(fragment: PreferenceFragmentCompat) {
        fragment.findPreference<Preference>(MixOffline.PREF_ENABLED)?.let { pref ->
            pref.setOnPreferenceChangeListener { _, _ ->
                // the preference is written after this returns: schedule once it is
                HomeMusic.main.post { MixOffline.schedule(fragment.requireContext().applicationContext) }
                true
            }
            HomeMusic.io.execute {
                val (count, bytes) = runCatching { MixOffline.held() }.getOrDefault(0 to 0L)
                HomeMusic.main.post {
                    if (!fragment.isAdded || count == 0) return@post
                    pref.summary = fragment.getString(R.string.homemusic_settings_mix_offline_summary) + "\n" +
                        fragment.getString(R.string.homemusic_settings_mix_offline_held, count, HomeTrafficFormat.size(fragment.requireContext(), bytes))
                }
            }
        }
        fragment.findPreference<Preference>(HubConfig.PREF_TRAFFIC_STATS)?.let { pref ->
            fun refresh() {
                pref.summary = fragment.getString(R.string.homemusic_settings_traffic_hint)
                HomeMusic.io.execute {
                    val text = HomeTrafficFormat.summary(fragment.requireContext())
                    HomeMusic.main.post { if (fragment.isAdded) pref.summary = text }
                }
            }
            pref.setOnPreferenceClickListener { refresh(); true }
            refresh()
        }
    }

    /** Hides the items the Hub has not offered (called by the settings screen after it resets visibility). */
    @JvmStatic
    fun applyVisibility(fragment: PreferenceFragmentCompat) {
        fragment.findPreference<Preference>(HubConfig.PREF_GOOGLE)?.isVisible = googleShown
        fragment.findPreference<Preference>(HubConfig.PREF_ADMIN)?.isVisible = adminShown
        if (HubConfig.isEnabled()) {
            fragment.findPreference<Preference>(HubConfig.PREF_TEMPUS_LOGOUT)?.isVisible = false
            // The server's folders are never part of the HomeMusic Library screen.
            fragment.findPreference<Preference>("music_directory_section_visibility")?.isVisible = false
        }
        fragment.findPreference<Preference>(HubConfig.PREF_TEMPUS_PRELOAD_BUFFER)?.isVisible = false
    }

    /**
     * «Выйти» (red, the last item of the screen): after a confirmation, leaves the server and forgets the account
     * on this phone.
     */
    private fun bindLeave(fragment: PreferenceFragmentCompat, pref: Preference) {
        val user = Preferences.getUser().orEmpty()
        val server = Preferences.getServer().orEmpty().let { it.toHttpUrlOrNull()?.host ?: it }
        pref.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.homemusic_leave_title)
                .setMessage(fragment.getString(R.string.homemusic_leave_message, user, server))
                .setPositiveButton(R.string.homemusic_leave_ok) { _, _ ->
                    (fragment.activity as? MainActivity)?.let { leave(it) }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }
    }

    /**
     * Signs out as Tempus's «Выйти» does, but first deletes the saved account, so the sign-in screen starts
     * over from the server step with nothing filled in from it.
     */
    private fun leave(activity: MainActivity) {
        val id = Preferences.getServerId()
        HomeMusic.io.execute {
            if (id != null) {
                runCatching {
                    // Room deletes by the primary key: the other fields do not matter
                    AppDatabase.getInstance().serverDao().delete(Server(id, "", "", "", "", null, 0L, false, null))
                }.onFailure { Log.w(TAG, "Cannot forget the account", it) }
            }
            HomeMusic.main.post {
                if (!activity.isFinishing && !activity.isDestroyed) activity.quit()
            }
        }
    }

    /** An answer from the Hub arrived: show [pref] now only if its category is open. */
    private fun show(fragment: PreferenceFragmentCompat, pref: Preference, visible: Boolean) {
        val open = fragment.findPreference<Preference>(HubConfig.PREF_CHECK_UPDATE)?.isVisible == true
        pref.isVisible = visible && open
    }

    /** «Пользователи и приглашения»: for the Hub's admin only, opens its admin page in the Hub tab. */
    private fun bindAdmin(fragment: PreferenceFragmentCompat, pref: Preference) {
        HomeMusic.io.execute {
            val admin = runCatching { HubClient.ensureSession().isAdmin }.getOrDefault(false)
            HomeMusic.main.post {
                if (!fragment.isAdded) return@post
                adminShown = admin
                show(fragment, pref, admin)
            }
        }
        pref.setOnPreferenceClickListener {
            (fragment.activity as? MainActivity)?.let { HubNavigator.open(it, "/admin") }
            true
        }
    }

    /** «Вход через Google»: shown once the Hub says it is set up; links or unlinks the Google account. */
    private fun bindGoogle(fragment: PreferenceFragmentCompat, pref: Preference) {
        if (!Flavors.hasGoogleSignIn()) return
        var clientId: String? = null
        var email: String? = null

        fun render() {
            googleShown = clientId != null
            show(fragment, pref, googleShown)
            pref.summary = email?.let { fragment.getString(R.string.homemusic_settings_google_on, it) }
                ?: fragment.getString(R.string.homemusic_settings_google_off)
        }

        fun toast(text: String) {
            if (fragment.isAdded) Toast.makeText(fragment.requireContext(), text, Toast.LENGTH_SHORT).show()
        }

        fun inBackground(work: () -> HubMe, done: String) {
            HomeMusic.io.execute {
                val result = runCatching(work)
                HomeMusic.main.post {
                    result.onSuccess { email = it.googleEmail; render(); toast(done) }
                    result.onFailure { toast(it.message ?: fragment.getString(R.string.homemusic_hub_unreachable)) }
                }
            }
        }

        HomeMusic.io.execute {
            val id = runCatching { HubClient.authOptions().googleClientId }.getOrNull()
            val me = if (id.isNullOrBlank()) null else runCatching { HubClient.ensureSession() }.getOrNull()
            HomeMusic.main.post {
                if (!fragment.isAdded || me == null) return@post
                clientId = id
                email = me.googleEmail
                render()
            }
        }

        pref.setOnPreferenceClickListener {
            val linked = email
            if (linked != null) {
                MaterialAlertDialogBuilder(fragment.requireContext())
                    .setMessage(fragment.getString(R.string.homemusic_settings_google_unlink, linked))
                    .setPositiveButton(R.string.homemusic_settings_google_unlink_ok) { _, _ ->
                        inBackground({ HubClient.unlinkGoogle() }, fragment.getString(R.string.homemusic_settings_google_unlinked))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            } else {
                val id = clientId ?: return@setOnPreferenceClickListener true
                Flavors.googleSignIn(fragment.requireActivity(), id, object : GoogleIdCallback {
                    override fun onToken(idToken: String, email: String?) {
                        inBackground({ HubClient.linkGoogle(idToken) }, fragment.getString(R.string.homemusic_settings_google_linked))
                    }

                    override fun onError(message: String?) {
                        if (message != null) toast(message)
                    }
                })
            }
            true
        }
    }

    private fun setUp(pref: EditTextPreference, default: String, effective: () -> String) {
        pref.setOnBindEditTextListener { edit ->
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            edit.setSingleLine()
            if (pref.text == null) edit.setText(default)
            edit.setSelection(edit.text.length)
        }
        pref.summaryProvider = Preference.SummaryProvider<EditTextPreference> { effective() }
        pref.setOnPreferenceChangeListener { _, _ ->
            // The address is picked again (home or public) and the Hub tab reloads on next open.
            HubClient.invalidate()
            HubWebHolder.loadedBase = null
            true
        }
    }
}
