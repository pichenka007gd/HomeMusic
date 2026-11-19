package com.eddyizm.tempus.homemusic

import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.webkit.CookieManager
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.App
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.FragmentHomemusicLoginBinding
import com.eddyizm.tempus.interfaces.SystemCallback
import com.eddyizm.tempus.model.Server
import com.eddyizm.tempus.repository.PlaylistRepository
import com.eddyizm.tempus.repository.ServerRepository
import com.eddyizm.tempus.repository.SystemRepository
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.fragment.LoginFragment
import com.eddyizm.tempus.util.Flavors
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.textfield.TextInputLayout
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * HomeMusic's sign-in screen in place of Tempus's server list: the server first (the HomeMusic one is
 * offered), then the name (or the email) and password — or a new account by an invite code, by email (a code in
 * a letter), or Google. The account is a Navidrome user; registration and Google go through the Hub
 * (`/api/auth/register`, `/api/auth/email/…`, `/api/auth/google`), then the player signs in to Navidrome as usual.
 */
@OptIn(UnstableApi::class)
class HomeLogin private constructor(private val fragment: Fragment, private val b: FragmentHomemusicLoginBinding) {
    private enum class Step { SERVER, ACCOUNT, REGISTER, EMAIL }

    private var step = Step.SERVER
    private var server = ""
    private var hubServer = ""
    private var options: HubAuthOptions? = null
    private var googleToken: String? = null
    private var googleEmail: String? = null
    /** The address the registration letter went to; the rest of the email form shows once it is sent. */
    private var emailSent: String? = null
    private var resendAt = 0L
    private var saved: List<Server> = emptyList()
    private var busy = false

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                busy -> Unit
                step == Step.REGISTER && googleToken == null || step == Step.EMAIL -> show(Step.ACCOUNT)
                else -> show(Step.SERVER)
            }
        }
    }

    private fun str(id: Int, vararg args: Any): String = fragment.getString(id, *args)

    private fun alive(): Boolean = fragment.isAdded && fragment.view != null

    private fun init() {
        ViewCompat.setOnApplyWindowInsetsListener(b.hmLoginRoot) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }

        b.hmLoginServer.setText(BuildConfig.MUSIC_URL)
        b.hmLoginHub.setText(BuildConfig.HUB_URL)
        b.hmLoginServer.doAfterTextChanged {
            updateServerReset()
            val address = LocalServers.music(it.toString())
            if (!sameServer(address.orEmpty(), BuildConfig.MUSIC_URL) &&
                sameServer(b.hmLoginHub.text.toString(), BuildConfig.HUB_URL)) b.hmLoginHub.text = null
            b.hmLoginHubLayout.placeholderText = address?.let(LocalServers::suggestedHub)
            clearError()
        }
        b.hmLoginHub.doAfterTextChanged { clearError() }
        b.hmLoginServerDefault.setOnClickListener {
            b.hmLoginServer.setText(BuildConfig.MUSIC_URL)
            b.hmLoginHub.setText(BuildConfig.HUB_URL)
        }
        listOf(b.hmLoginCode, b.hmLoginUser, b.hmLoginPassword, b.hmLoginPassword2).forEach { field ->
            field.doAfterTextChanged { clearError() }
        }
        b.hmLoginEmail.doAfterTextChanged {
            clearError()
            // another address: a new letter is needed
            if (emailSent != null && it.toString().trim() != emailSent) {
                emailSent = null
                if (step == Step.EMAIL) show(Step.EMAIL)
            }
        }
        onGo(b.hmLoginEmail) { if (emailSent == null) primary() }
        onGo(b.hmLoginServer) { primary() }
        onGo(b.hmLoginHub) { primary() }
        onGo(b.hmLoginPassword) { if (step == Step.ACCOUNT) primary() }
        onGo(b.hmLoginPassword2) { primary() }

        b.hmLoginPrimary.setOnClickListener { primary() }
        b.hmLoginServerRow.setOnClickListener { show(Step.SERVER) }
        b.hmLoginSwitch.setOnClickListener { show(if (step == Step.ACCOUNT) Step.REGISTER else Step.ACCOUNT) }
        b.hmLoginEmailSwitch.setOnClickListener { show(Step.EMAIL) }
        b.hmLoginResend.setOnClickListener { sendEmailCode() }
        b.hmLoginGoogle.setOnClickListener { google() }

        // The last account used on this phone, after a logout
        ServerRepository().liveServer.observe(fragment.viewLifecycleOwner) { servers ->
            saved = servers.orEmpty()
            val last = saved.maxByOrNull { it.timestamp } ?: return@observe
            if (step == Step.SERVER && b.hmLoginServer.text.toString() == BuildConfig.MUSIC_URL) {
                b.hmLoginServer.setText(last.address)
                if (HubConfig.isBoundTo(last.address)) b.hmLoginHub.setText(HubConfig.remoteUrl())
            }
            val selected = LocalServers.music(b.hmLoginServer.text.toString())
            if (b.hmLoginUser.text.isNullOrEmpty()) saved.firstOrNull { LocalServers.same(it.address, selected) }
                ?.let { b.hmLoginUser.setText(it.username) }
        }

        // Back walks the steps; on the first one it leaves as usual
        fragment.requireActivity().onBackPressedDispatcher.addCallback(fragment.viewLifecycleOwner, back)
        show(Step.SERVER)
    }

    private fun onGo(field: EditText, action: () -> Unit) {
        field.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_GO || id == EditorInfo.IME_ACTION_DONE) {
                action()
                true
            } else false
        }
    }

    private fun updateServerReset() {
        val text = b.hmLoginServer.text.toString().trim()
        b.hmLoginServerDefault.isVisible = step == Step.SERVER && BuildConfig.MUSIC_URL.isNotEmpty() &&
            !sameServer(text, BuildConfig.MUSIC_URL)
    }

    private fun show(next: Step) {
        if (next != Step.REGISTER) {
            googleToken = null
            googleEmail = null
        }
        val changed = next != step
        if (changed) b.hmLoginCode.text = null
        step = next
        back.isEnabled = next != Step.SERVER
        clearError()
        val google = googleToken != null
        val email = next == Step.EMAIL
        // the email form before its letter: only the address
        val awaiting = email && emailSent == null
        val newAccount = next == Step.REGISTER || email
        val hub = hubOffers()
        val emailSignup = hub && options?.emailSignup == true
        b.hmLoginServerStep.isVisible = next == Step.SERVER
        b.hmLoginAccountStep.isVisible = next != Step.SERVER
        b.hmLoginServerLabel.text = server
        b.hmLoginEmailLayout.isVisible = email
        b.hmLoginCodeLayout.isVisible = next == Step.REGISTER || (email && !awaiting)
        b.hmLoginCodeLayout.hint = str(if (email) R.string.homemusic_login_email_code else R.string.homemusic_login_code)
        b.hmLoginCodeLayout.placeholderText = if (email) "000000" else "ABCD-EFGH"
        b.hmLoginCode.inputType = if (email) InputType.TYPE_CLASS_NUMBER
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        b.hmLoginResend.isVisible = email && !awaiting
        b.hmLoginUserLayout.isVisible = !awaiting
        b.hmLoginUserLayout.hint = str(if (next == Step.ACCOUNT && emailSignup) R.string.homemusic_login_user_or_email
        else R.string.homemusic_login_user)
        b.hmLoginPasswordLayout.isVisible = !awaiting && (next == Step.ACCOUNT || (newAccount && !google))
        b.hmLoginPassword2Layout.isVisible = !awaiting && newAccount && !google
        b.hmLoginPassword.imeOptions = if (newAccount) EditorInfo.IME_ACTION_NEXT else EditorInfo.IME_ACTION_GO
        b.hmLoginSubtitle.text = when (next) {
            Step.SERVER -> str(R.string.homemusic_login_server_subtitle)
            Step.ACCOUNT -> str(R.string.homemusic_login_account_subtitle)
            Step.REGISTER -> if (google) str(R.string.homemusic_login_google_register_subtitle, googleEmail ?: "")
            else str(R.string.homemusic_login_register_subtitle)
            Step.EMAIL -> emailSent?.let { str(R.string.homemusic_login_email_sent, it) }
                ?: str(R.string.homemusic_login_email_subtitle)
        }
        b.hmLoginPrimary.text = str(when (next) {
            Step.SERVER -> R.string.homemusic_login_next
            Step.ACCOUNT -> R.string.homemusic_login_sign_in
            Step.REGISTER -> R.string.homemusic_login_create
            Step.EMAIL -> if (awaiting) R.string.homemusic_login_email_send else R.string.homemusic_login_create
        })
        b.hmLoginSwitch.isVisible = hub && next != Step.SERVER && !google
        b.hmLoginSwitch.text = str(if (next == Step.ACCOUNT) R.string.homemusic_login_to_register else R.string.homemusic_login_to_account)
        b.hmLoginEmailSwitch.isVisible = next == Step.ACCOUNT && emailSignup
        b.hmLoginGoogle.isVisible = next == Step.ACCOUNT && hub && !options?.googleClientId.isNullOrBlank() &&
            Flavors.hasGoogleSignIn()
        b.hmLoginHint.text = when (next) {
            Step.SERVER -> ""
            Step.ACCOUNT -> if (!hub) "" else str(if (emailSignup) R.string.homemusic_login_hint_email else R.string.homemusic_login_hint)
            Step.REGISTER -> str(R.string.homemusic_login_register_hint)
            Step.EMAIL -> str(if (awaiting) R.string.homemusic_login_email_hint else R.string.homemusic_login_register_hint)
        }
        b.hmLoginHint.isVisible = b.hmLoginHint.text.isNotEmpty()
        updateServerReset()
        updateResend()
        if (!changed && !email) return
        when (next) {
            Step.SERVER -> b.hmLoginServer.requestFocus()
            Step.ACCOUNT -> (if (b.hmLoginUser.text.isNullOrEmpty()) b.hmLoginUser else b.hmLoginPassword).requestFocus()
            Step.REGISTER -> b.hmLoginCode.requestFocus()
            Step.EMAIL -> if (awaiting) { if (changed) b.hmLoginEmail.requestFocus() } else b.hmLoginCode.requestFocus()
        }
    }

    private val resendTick = Runnable { updateResend() }

    /** «Send again», with the seconds left until the Hub takes another letter. */
    private fun updateResend() {
        b.hmLoginResend.removeCallbacks(resendTick)
        if (!b.hmLoginResend.isVisible) return
        val left = ((resendAt - System.currentTimeMillis() + 999) / 1000).toInt()
        b.hmLoginResend.isEnabled = !busy && left <= 0
        b.hmLoginResend.text = if (left > 0) str(R.string.homemusic_login_email_resend_in, left)
        else str(R.string.homemusic_login_email_resend)
        if (left > 0) b.hmLoginResend.postDelayed(resendTick, 1000)
    }

    /** The Hub answered and its accounts are for this server: registration and Google make sense. */
    private fun hubOffers(): Boolean {
        return options?.invites == true && hubServer.isNotBlank()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        b.hmLoginProgress.visibility = if (value) View.VISIBLE else View.INVISIBLE
        listOf(b.hmLoginPrimary, b.hmLoginGoogle, b.hmLoginSwitch, b.hmLoginEmailSwitch, b.hmLoginServerRow,
            b.hmLoginServerDefault).forEach { it.isEnabled = !value }
        listOf(b.hmLoginServer, b.hmLoginHub, b.hmLoginEmail, b.hmLoginCode, b.hmLoginUser, b.hmLoginPassword, b.hmLoginPassword2)
            .forEach { it.isEnabled = !value }
        updateResend()
    }

    private fun showError(message: String?) {
        b.hmLoginError.text = message
        b.hmLoginError.isVisible = !message.isNullOrBlank()
    }

    private fun clearError() {
        showError(null)
        listOf(b.hmLoginServerLayout, b.hmLoginHubLayout, b.hmLoginEmailLayout, b.hmLoginCodeLayout, b.hmLoginUserLayout,
            b.hmLoginPasswordLayout, b.hmLoginPassword2Layout).forEach { it.error = null }
    }

    private fun required(layout: TextInputLayout, value: String): Boolean {
        if (value.isNotEmpty()) return true
        layout.error = str(R.string.homemusic_login_required)
        layout.editText?.requestFocus()
        return false
    }

    private fun primary() {
        if (busy) return
        when (step) {
            Step.SERVER -> checkServer()
            Step.ACCOUNT -> {
                val user = b.hmLoginUser.text.toString().trim()
                val password = b.hmLoginPassword.text.toString()
                if (!required(b.hmLoginUserLayout, user) || !required(b.hmLoginPasswordLayout, password)) return
                // the player knows only names: the Hub tells the one for this email
                if ('@' in user && hubOffers()) hubThenSignIn { (HubClient.loginByEmail(user, password).username ?: user) to password }
                else signIn(user, password)
            }
            Step.REGISTER -> register()
            Step.EMAIL -> if (emailSent == null) sendEmailCode() else registerByEmail()
        }
    }

    // --- step 1: the server -----------------------------------------------------------------

    private fun checkServer() {
        val address = LocalServers.music(b.hmLoginServer.text.toString())
        if (address == null) {
            b.hmLoginServerLayout.error = str(R.string.homemusic_login_server_bad)
            return
        }
        val hub = if (b.hmLoginHub.text.isNullOrBlank()) LocalServers.suggestedHub(address)
        else LocalServers.hub(b.hmLoginHub.text.toString())
        if (hub == null) {
            b.hmLoginHubLayout.error = str(R.string.homemusic_login_hub_bad)
            return
        }
        (fragment as LoginFragment).withLocalNetworkPermission(
            { if (alive()) checkEndpoints(address, hub) },
            { if (alive()) b.hmLoginServerLayout.error = str(R.string.homemusic_login_lan_permission) },
        )
    }

    private fun checkEndpoints(address: String, hub: String) {
        setBusy(true)
        HomeMusic.io.execute {
            val result = probe(address)
            // the Hub's options decide whether to offer registration and Google; no Hub — sign-in still works
            val opts = if (result == PROBE_OK) runCatching { HubClient.authOptionsAt(hub) }.getOrNull() else null
            HomeMusic.main.post {
                if (!alive()) return@post
                when (result) {
                    PROBE_OK -> {
                        // The new deployment must never inherit another server's credentials or Hub fallback.
                        resetPreferences()
                        HubWebHolder.destroy()
                        CookieManager.getInstance().removeAllCookies {
                            if (!alive()) return@removeAllCookies
                            if (!LocalServers.same(server, address) || !LocalServers.same(hubServer, hub)) {
                                listOf(b.hmLoginUser, b.hmLoginPassword, b.hmLoginPassword2, b.hmLoginEmail, b.hmLoginCode)
                                    .forEach { it.text = null }
                                emailSent = null
                                resendAt = 0L
                            }
                            CookieManager.getInstance().flush()
                            HubConfig.selectServer(address, hub)
                            server = address
                            hubServer = hub
                            options = opts
                            b.hmLoginServer.setText(address)
                            b.hmLoginHub.setText(hub)
                            setBusy(false)
                            show(Step.ACCOUNT)
                        }
                    }
                    PROBE_NOT_MUSIC -> { setBusy(false); b.hmLoginServerLayout.error = str(R.string.homemusic_login_server_not_music) }
                    else -> { setBusy(false); b.hmLoginServerLayout.error = str(R.string.homemusic_login_server_unreachable) }
                }
            }
        }
    }

    // --- registration and Google -------------------------------------------------------------

    /** A new password, typed twice. */
    private fun newPasswordOk(password: String): Boolean {
        if (!required(b.hmLoginPasswordLayout, password)) return false
        if (password.length < 6) {
            b.hmLoginPasswordLayout.error = str(R.string.homemusic_login_short_password)
            return false
        }
        if (password != b.hmLoginPassword2.text.toString()) {
            b.hmLoginPassword2Layout.error = str(R.string.homemusic_login_mismatch)
            return false
        }
        return true
    }

    private fun register() {
        val code = b.hmLoginCode.text.toString().trim()
        val user = b.hmLoginUser.text.toString().trim()
        val password = b.hmLoginPassword.text.toString()
        val token = googleToken
        if (!required(b.hmLoginCodeLayout, code) || !required(b.hmLoginUserLayout, user)) return
        if (token == null && !newPasswordOk(password)) return
        hubThenSignIn {
            if (token == null) {
                HubClient.register(code, user, password)
                user to password
            } else {
                val r = HubClient.googleLogin(token, code, user)
                (r.username ?: user) to (r.password ?: "")
            }
        }
    }

    private fun sendEmailCode() {
        val email = b.hmLoginEmail.text.toString().trim()
        if (!required(b.hmLoginEmailLayout, email)) return
        if (!email.contains('@')) {
            b.hmLoginEmailLayout.error = str(R.string.homemusic_login_email_bad)
            return
        }
        setBusy(true)
        HomeMusic.io.execute {
            val result = runCatching { HubClient.emailCode(email) }
            HomeMusic.main.post {
                if (!alive()) return@post
                setBusy(false)
                result.onSuccess {
                    b.hmLoginCode.text = null
                    emailSent = email
                    resendAt = System.currentTimeMillis() + it.resendIn * 1000L
                    show(Step.EMAIL)
                }
                result.onFailure { showError(it.message ?: str(R.string.homemusic_login_no_hub)) }
            }
        }
    }

    private fun registerByEmail() {
        val email = emailSent ?: return
        val code = b.hmLoginCode.text.toString().trim()
        val user = b.hmLoginUser.text.toString().trim()
        val password = b.hmLoginPassword.text.toString()
        if (!required(b.hmLoginCodeLayout, code) || !required(b.hmLoginUserLayout, user) || !newPasswordOk(password)) return
        hubThenSignIn {
            HubClient.emailRegister(email, code, user, password)
            user to password
        }
    }

    private fun google() {
        val clientId = options?.googleClientId ?: return
        if (busy) return
        setBusy(true)
        Flavors.googleSignIn(fragment.requireActivity(), clientId, object : GoogleIdCallback {
            override fun onToken(idToken: String, email: String?) {
                if (!alive()) return
                hubThenSignIn(onNotLinked = {
                    // a Google account the Hub doesn't know: register it with an invite code
                    googleToken = idToken
                    googleEmail = email
                    b.hmLoginUser.setText(usernameFromEmail(email))
                    show(Step.REGISTER)
                }) {
                    val r = HubClient.googleLogin(idToken)
                    (r.username ?: "") to (r.password ?: "")
                }
            }

            override fun onError(message: String?) {
                if (!alive()) return
                setBusy(false)
                showError(message)
            }
        })
    }

    /** Runs [hub] (registration / Google) in the background, then signs the player in with the account it gave. */
    private fun hubThenSignIn(onNotLinked: (() -> Unit)? = null, hub: () -> Pair<String, String>) {
        setBusy(true)
        HomeMusic.io.execute {
            val result = runCatching(hub)
            HomeMusic.main.post {
                if (!alive()) return@post
                result.onSuccess { (user, password) -> signIn(user, password) }
                result.onFailure { e ->
                    setBusy(false)
                    if (e is HubException && e.code == 409 && onNotLinked != null) onNotLinked()
                    else showError(e.message ?: str(R.string.homemusic_login_no_hub))
                }
            }
        }
    }

    // --- the player's sign-in (as Tempus's server list does it) ------------------------------

    private fun signIn(user: String, password: String) {
        setBusy(true)
        CookieManager.getInstance().removeAllCookies {
            if (!alive()) return@removeAllCookies
            HubClient.invalidate()
            signInWithCleanSession(user, password)
        }
    }

    private fun signInWithCleanSession(user: String, password: String) {
        val existing = saved.firstOrNull { LocalServers.same(it.address, server) && it.username == user }
        val local: String? = null
        val account = Server(
            serverId = existing?.serverId ?: UUID.randomUUID().toString(),
            serverName = existing?.serverName ?: "HomeMusic",
            username = user,
            password = password,
            address = server,
            localAddress = local,
            timestamp = System.currentTimeMillis(),
            isLowSecurity = false,
            clientCert = null,
        )
        savePreferences(account)
        SystemRepository().checkUserCredential(object : SystemCallback {
            override fun onError(exception: Exception) = failed(
                if (exception.message.orEmpty().let { it.startsWith("40") || it.startsWith("41") }) str(R.string.homemusic_login_wrong)
                else exception.message ?: str(R.string.homemusic_login_wrong)
            )

            override fun onNetworkFailure(exception: Exception) = failed(str(R.string.homemusic_login_no_network))

            override fun onSuccess(password: String?, token: String?, salt: String?) {
                ServerRepository().insert(account)
                PlaylistRepository().refreshAllPlaylists()
                if (!alive()) return
                setBusy(false)
                (fragment.activity as? MainActivity)?.goFromLogin()
            }
        })
    }

    private fun failed(message: String) {
        resetPreferences()
        if (!alive()) return
        setBusy(false)
        // a Hub account that registered fine but can't sign in stays on the account step, with its name
        if (step == Step.REGISTER || step == Step.EMAIL) show(Step.ACCOUNT)
        showError(message)
    }

    private fun savePreferences(s: Server) {
        Preferences.setServerId(s.serverId)
        Preferences.setServer(s.address)
        Preferences.setLocalAddress(s.localAddress)
        Preferences.setInUseServerAddress(s.address)
        Preferences.setUser(s.username)
        Preferences.setPassword(s.password)
        Preferences.setLowSecurity(false)
        Preferences.setClientCert(null)
        App.getSubsonicClientInstance(true)
    }

    private fun resetPreferences() {
        Preferences.setServerId(null)
        Preferences.setServer(null)
        Preferences.setUser(null)
        Preferences.setPassword(null)
        Preferences.setToken(null)
        Preferences.setSalt(null)
        Preferences.setLowSecurity(false)
        Preferences.setClientCert(null)
        App.getSubsonicClientInstance(true)
    }

    companion object {
        private const val PROBE_OK = 0
        private const val PROBE_UNREACHABLE = 1
        private const val PROBE_NOT_MUSIC = 2

        /** The sign-in screen for [fragment] (Tempus's LoginFragment); null outside the homemusic flavor. */
        @JvmStatic
        fun createView(fragment: Fragment, inflater: LayoutInflater, container: ViewGroup?): View? {
            if (!HubConfig.isEnabled()) return null
            val binding = FragmentHomemusicLoginBinding.inflate(inflater, container, false)
            HomeLogin(fragment, binding).init()
            return binding.root
        }

        /** A typed address as the player needs it: a scheme (https by default), no trailing slash; null if not one. */
        @JvmStatic
        fun normalizeServer(input: String): String? {
            var u = input.trim()
            if (u.isEmpty()) return null
            if (!u.contains("://")) u = "https://$u"
            val url = u.toHttpUrlOrNull() ?: return null
            if (url.host.isEmpty() || !url.host.contains('.') && url.host != "localhost") return null
            return url.toString().trimEnd('/')
        }

        @JvmStatic
        fun sameServer(a: String?, b: String?): Boolean {
            if (a.isNullOrBlank() || b.isNullOrBlank()) return false
            return normalizeServer(a)?.lowercase() == normalizeServer(b)?.lowercase()
        }

        /** A suggested user name for a Google account: the part of the email before «@». */
        @JvmStatic
        fun usernameFromEmail(email: String?): String =
            email.orEmpty().substringBefore('@').lowercase().filter { it.isLetterOrDigit() || it in "._-" }.take(32)

        /** Is there a Subsonic server at [address]? Its ping answers even without credentials. */
        private fun probe(address: String): Int {
            val client = HubClient.http.newBuilder().cookieJar(CookieJar.NO_COOKIES)
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(15, TimeUnit.SECONDS).build()
            val request = Request.Builder().url("$address/rest/ping.view?v=1.16.1&c=HomeMusic&f=json").build()
            return try {
                client.newCall(request).execute().use { r ->
                    if (r.body?.string().orEmpty().contains("subsonic-response")) PROBE_OK else PROBE_NOT_MUSIC
                }
            } catch (e: Exception) {
                PROBE_UNREACHABLE
            }
        }
    }
}
