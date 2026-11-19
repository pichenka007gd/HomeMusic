package com.eddyizm.tempus.homemusic

import android.content.ContentResolver
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.annotation.WorkerThread
import com.eddyizm.tempus.util.Preferences
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** An error the Hub (or the way to it) reported, with a message fit for the user. */
class HubException(message: String, val code: Int = 0) : IOException(message)

class HubMe {
    var id: Int = 0
    var username: String? = null
    @SerializedName("is_admin") var isAdmin: Boolean = false
    @SerializedName("google_email") var googleEmail: String? = null
    var email: String? = null
}

/** What the sign-in screen may offer (`GET /api/auth/options`). */
class HubAuthOptions {
    var invites: Boolean = false
    /** Registration by email: a code in a letter, no invite needed. */
    @SerializedName("email_signup") var emailSignup: Boolean = false
    @SerializedName("google_client_id") var googleClientId: String? = null
    /** Public address of the music server these accounts are for. */
    @SerializedName("music_url") var musicUrl: String? = null
}

/** A registration letter went out (`POST /api/auth/email/code`). */
class HubEmailCode {
    /** Seconds before another letter may be asked for. */
    @SerializedName("resend_in") var resendIn: Int = 60
}

/** Signed in with Google: the player gets the account's Navidrome name and password. */
class HubGoogleLogin {
    var username: String? = null
    var password: String? = null
}

class HubDownloadItem {
    var id: Long = 0
    var status: String? = null
    /** Quiet Hub download, e.g. a catalog ♥ or a mix; absent on older Hubs. */
    var star: Boolean = false
}

class HubDownloadBatch {
    var queued: Int = 0
    @SerializedName("already_owned") var alreadyOwned: Int = 0
    var expanding: Boolean = false
}

class HubAssistantTask {
    var id: Long = 0
    var status: String? = null
    var error: String? = null
    @SerializedName("batch_id") var batchId: Long? = null
}

class HubUpload {
    var id: Long = 0
    @SerializedName("orig_name") var origName: String? = null
}

/** `PlayEventsOut`: `rejected` — malformed events, with the reason. */
class HubPlayEventsResult {
    var accepted: Int = 0
    var duplicates: Int = 0
    var rejected: List<Map<String, Any?>>? = null
}

/** `MixTodayOut` (what the app shows of it). */
class HubMixToday {
    var mix: HubMix? = null
    var building: Boolean = false
    var onboarding: Boolean = false
}

class HubMix {
    var id: Long = 0
    var title: String? = null
    var description: String? = null
    @SerializedName("nd_playlist_id") var ndPlaylistId: String? = null
    var ready: Int = 0
    var total: Int = 0
    var tracks: List<WaveTrack>? = null
}

/** `TrackSourceOut` (library file) or `ListenOut` (catalog track): where the file comes from. */
class HubTrackSource {
    var source: String? = null
    @SerializedName("source_url") var sourceUrl: String? = null
    var kind: String? = null
    var provider: String? = null
    @SerializedName("page_url") var pageUrl: String? = null
}

class HubAppRelease {
    var versionCode: Long = 0
    var versionName: String? = null
    var notes: String? = null
    var url: String? = null
    var size: Long = 0
    var sha256: String? = null
    var full: HubUpdateFile? = null
    var patches: List<HubUpdatePatch>? = null
}

open class HubUpdateFile {
    var url: String? = null
    var size: Long = 0
    var sha256: String? = null
}

class HubUpdatePatch : HubUpdateFile() {
    var format: String? = null
    var baseVersionCode: Long = 0
    var baseSha256: String? = null
}

/**
 * Talks to the HomeMusic Hub. Cookies live in the WebView's [CookieManager], so a session made
 * here is the one the Hub tabs use, and the user never types the password twice.
 */
object HubClient {
    private const val TAG = "HubClient"
    private const val BASE_TTL_MS = 2 * 60 * 1000L

    private val JSON: MediaType = "application/json; charset=utf-8".toMediaType()
    val gson = Gson()

    private val cookieJar = WebViewCookieJar()
    private val loginEndpoint = ThreadLocal<String>()
    private val loginHttp: OkHttpClient by lazy {
        http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
    }
    private data class AccountSession(val base: String, val cookie: String)
    private data class NativeSession(val target: HubLoginTarget, val cookie: String)
    @Volatile private var nativeSession: NativeSession? = null
    private val accountSession = ThreadLocal<AccountSession>()
    private val fixedCookieHttp: OkHttpClient by lazy {
        http.newBuilder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false).build()
    }

    internal fun <T> withAccountSession(account: String, block: () -> T): T {
        return withVerifiedAccount(account, CatalogFavorites::currentAccount, {
            ensureSession()
            val base = baseUrl()
            val session = nativeSession?.takeIf { it.target == HubConfig.loginTarget(base) }
                ?: throw IOException("Hub session unavailable")
            AccountSession(base, session.cookie)
        }, { snapshot ->
            val me = fixedCookieHttp.newCall(Request.Builder().url("${snapshot.base}/api/auth/me")
                .header("Cookie", snapshot.cookie).build()).execute().use { response ->
                if (!response.isSuccessful) throw error(response)
                parse(response, HubMe::class.java)
            }
            me.username == account.substringAfterLast('\n')
        }, { snapshot ->
            accountSession.set(snapshot)
            try { block() } finally { accountSession.remove() }
        })
    }

    @JvmStatic
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        http.newBuilder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    @Volatile private var resolvedBase: String? = null
    @Volatile private var resolvedAt = 0L

    /** The address in use: the home one while it answers, the public one otherwise. */
    @JvmStatic
    @WorkerThread
    fun baseUrl(): String {
        val now = SystemClock.elapsedRealtime()
        val remote = HubConfig.remoteUrl()
        val local = HubConfig.localUrl()
        resolvedBase?.let { if (it in listOf(remote, local) && now - resolvedAt < BASE_TTL_MS) return it }
        val base = if (local != null && local != remote && reachable(local)) local else remote
        if (base.isEmpty()) throw HubException("Не задан адрес Хаба — укажите его в настройках")
        resolvedBase = base
        resolvedAt = now
        return base
    }

    @JvmStatic
    fun lastBaseUrl(): String? = resolvedBase

    /** Forget the chosen address, e.g. after the settings or the network changed. */
    @JvmStatic
    fun invalidate() {
        resolvedBase = null
        capabilityBase = null
        nativeSession = null
    }

    @Volatile private var capabilityBase: String? = null
    @Volatile private var capabilityAt = 0L
    @Volatile private var capabilities: Set<String> = emptySet()

    /** An absent capability is a legacy Hub, never permission to encode an album as a song. */
    @WorkerThread
    fun supportsTypedFavorites(): Boolean = "typed_favorites_v1" in capabilities()

    @WorkerThread
    fun supportsCompensationChains(): Boolean = "favorite_compensation_chains_v1" in capabilities()

    fun supportsListeningCheckpoints(): Boolean = "listening_checkpoints_v1" in capabilities()

    /** `launch_id` / `launch_origin` of listening events (INSIGHTS.md 6.5). */
    @WorkerThread
    fun supportsLaunchEvents(): Boolean = "launch_events_v1" in capabilities()

    /** `origin` / `client` / `restart_of` of `POST /api/wave/start` (INSIGHTS.md 6.3). */
    @WorkerThread
    fun supportsWaveOrigin(): Boolean = "wave_origin_v1" in capabilities()

    private fun capabilities(): Set<String> {
        val base = baseUrl()
        val now = SystemClock.elapsedRealtime()
        if (capabilityBase == base && now - capabilityAt < PlaySender.CAPABILITY_RETRY_MS) return capabilities
        val supported = try {
            probeClient.newCall(Request.Builder().url("$base/healthz").build()).execute().use { response ->
                if (!response.isSuccessful) emptySet() else JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                    .getAsJsonArray("capabilities")?.map { it.asString }?.toSet().orEmpty()
            }
        } catch (e: Exception) { emptySet() }
        capabilities = supported
        capabilityAt = now
        capabilityBase = base
        return supported
    }

    private fun reachable(base: String): Boolean = try {
        probeClient.newCall(Request.Builder().url("$base/healthz").build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    // --- session ----------------------------------------------------------------------------

    /** Makes sure there is a Hub session (signing in with the player's Navidrome account if needed). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun ensureSession(): HubMe {
        if (!HubConfig.isBoundTo(Preferences.getServer())) {
            throw HubException("Сначала выберите сервер HomeMusic на экране входа", 401)
        }
        val base = baseUrl()
        val target = HubConfig.loginTarget(base) ?: throw HubException("Сначала войдите в выбранный сервер", 401)
        val me = try {
            val request = authenticatedRequest(Request.Builder().url("$base/api/auth/me").build(), target)
            fixedCookieHttp.newCall(request).execute().use { r ->
                when {
                    r.isSuccessful -> parse(r, HubMe::class.java)
                    r.code == 401 -> null
                    else -> throw error(r)
                }
            }
        } catch (e: HubException) {
            throw e
        } catch (e: IOException) {
            // The home address may have just gone away: pick again once.
            invalidate()
            throw HubException("Хаб недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
        if (HubConfig.loginTarget(base) != target) throw HubException("Выбран другой сервер или аккаунт", 401)
        return me?.takeIf { it.username == target.username } ?: login(base, target)
    }

    @WorkerThread
    @Throws(IOException::class)
    private fun login(base: String, target: HubLoginTarget): HubMe {
        if (HubConfig.loginTarget(base) != target) {
            throw HubException("Выбран другой сервер или аккаунт", 401)
        }
        val body = gson.toJson(mapOf("username" to target.username, "password" to target.password)).toRequestBody(JSON)
        val request = Request.Builder().url("$base/api/auth/login").post(body).build()
        loginHttp.newCall(request).execute().use { r ->
            if (!r.isSuccessful) throw error(r)
            if (HubConfig.loginTarget(base) != target) throw HubException("Выбран другой сервер или аккаунт", 401)
            val cookies = Cookie.parseAll(request.url, r.headers)
            val cookie = cookies.joinToString("; ") { "${it.name}=${it.value}" }
            if (cookie.isEmpty()) throw HubException("Хаб не создал сессию", 401)
            val me = parse(r, HubMe::class.java)
            if (me.username != target.username) throw HubException("Хаб вернул другой аккаунт", 401)
            nativeSession = NativeSession(target, cookie)
            cookieJar.saveFromResponse(request.url, cookies)
            flushCookies()
            return me
        }
    }

    /** Cookies are captured with their exact server/account, never loaded from a shared host cookie jar. */
    private fun authenticatedRequest(request: Request, target: HubLoginTarget): Request {
        var session = nativeSession?.takeIf { it.target == target }
        if (session == null) {
            login(target.base, target)
            session = nativeSession?.takeIf { it.target == target } ?: throw HubException("Сессия изменилась", 401)
        }
        if (HubConfig.loginTarget(target.base) != target) throw HubException("Выбран другой сервер или аккаунт", 401)
        return request.newBuilder().header("Cookie", session.cookie).build()
    }

    // --- accounts (the sign-in screen: no session yet) --------------------------------------

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun authOptions(): HubAuthOptions = post(null, "/api/auth/options", HubAuthOptions::class.java)

    /** An explicit server probe before login: no saved address, cookies or redirects. */
    fun authOptionsAt(base: String): HubAuthOptions {
        loginEndpoint.set(base)
        return try { authOptions() } finally { loginEndpoint.remove() }
    }

    /** A new account by an invite code; signs in to the Hub too (the cookie). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun register(code: String, username: String, password: String): HubMe =
        post(mapOf("code" to code, "username" to username, "password" to password), "/api/auth/register", HubMe::class.java)

    /** Sends a registration code to [email]; HTTP 409 — the address has an account already. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun emailCode(email: String): HubEmailCode = post(mapOf("email" to email), "/api/auth/email/code", HubEmailCode::class.java)

    /** A new account for [email], confirmed by the [code] from its letter; signs in to the Hub too (the cookie). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun emailRegister(email: String, code: String, username: String, password: String): HubMe =
        post(mapOf("email" to email, "code" to code, "username" to username, "password" to password),
            "/api/auth/email/register", HubMe::class.java)

    /** Sign-in by the email an account registered with: the Hub answers with its Navidrome name. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun loginByEmail(email: String, password: String): HubMe =
        post(mapOf("username" to email, "password" to password), "/api/auth/login", HubMe::class.java)

    /** Google's ID token → the linked account; HTTP 409 — not linked yet: send [code] (and a [username]) to register. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun googleLogin(idToken: String, code: String? = null, username: String? = null): HubGoogleLogin =
        post(mapOf("id_token" to idToken, "code" to code, "username" to username), "/api/auth/google", HubGoogleLogin::class.java)

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun linkGoogle(idToken: String): HubMe {
        val json = gson.toJson(mapOf("id_token" to idToken)).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/auth/google/link").post(json).build() }) { parse(it, HubMe::class.java) }
    }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun unlinkGoogle(): HubMe =
        call({ Request.Builder().url("$it/api/auth/google/link").delete().build() }) { parse(it, HubMe::class.java) }

    /** GET when [body] is null, else POST of it as JSON; no session needed. */
    private fun <T> post(body: Any?, path: String, type: Class<T>): T {
        val explicitProbe = loginEndpoint.get()
        val base = explicitProbe ?: baseUrl()
        val request = Request.Builder().url("$base$path")
        if (body != null) request.post(gson.toJson(body).toRequestBody(JSON))
        val prepared = request.build()
        try {
            loginHttp.newCall(prepared).execute().use { r ->
                if (!r.isSuccessful) throw error(r)
                if (explicitProbe == null) cookieJar.saveFromResponse(prepared.url, Cookie.parseAll(prepared.url, r.headers))
                flushCookies()
                return parse(r, type)
            }
        } catch (e: HubException) {
            throw e
        } catch (e: IOException) {
            invalidate()
            throw HubException("Хаб недоступен: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Runs a call against the Hub, signing in again once when the session has expired. */
    @WorkerThread
    @Throws(IOException::class)
    fun <T> call(build: (base: String) -> Request, handle: (Response) -> T): T {
        accountSession.get()?.let { session ->
            val request = build(session.base).newBuilder().header("Cookie", session.cookie).build()
            return fixedCookieHttp.newCall(request).execute().use { if (!it.isSuccessful) throw error(it); handle(it) }
        }
        val base = baseUrl()
        val target = HubConfig.loginTarget(base) ?: throw HubException("Сначала войдите в выбранный сервер", 401)
        val first = fixedCookieHttp.newCall(authenticatedRequest(build(base), target)).execute()
        if (HubConfig.loginTarget(base) != target) {
            first.close()
            throw HubException("Выбран другой сервер или аккаунт", 401)
        }
        if (first.code != 401) return first.use { if (!it.isSuccessful) throw error(it); handle(it) }
        first.close()
        login(base, target)
        return fixedCookieHttp.newCall(authenticatedRequest(build(base), target)).execute().use { if (!it.isSuccessful) throw error(it); handle(it) }
    }

    // --- API --------------------------------------------------------------------------------

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun downloads(limit: Int = 100): List<HubDownloadItem> =
        call({ Request.Builder().url("$it/api/downloads?limit=$limit").build() }) { parseList(it, HubDownloadItem::class.java) }

    @WorkerThread
    fun favorites(): HubFavoriteSnapshot =
        call({ Request.Builder().url("$it/api/starred").build() }) { parse(it, HubFavoriteSnapshot::class.java) }

    fun likedSongs(): HubLikedSongs =
        call({ Request.Builder().url("$it/api/nd/liked").build() }) { parse(it, HubLikedSongs::class.java) }

    /** Queues the download of one catalog track; [star]: ♥ it in Navidrome once imported. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun downloadTrack(ref: String, star: Boolean = false): HubDownloadBatch {
        val json = gson.toJson(mapOf("track_refs" to listOf(ref), "star" to star)).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/downloads").post(json).build() }) { parse(it, HubDownloadBatch::class.java) }
    }

    /** Use the optimistic decision's id for the command and telemetry: one causal decision. */
    @WorkerThread
    fun favoriteTrack(ref: String, starred: Boolean, eventId: String, timestamp: String,
                      client: String = HubPlays.client, context: String? = HubPlays.contextOf(HubTracks.ID_PREFIX + ref)) {
        val base = accountSession.get()?.base ?: baseUrl()
        val supported = probeClient.newCall(Request.Builder().url("$base/healthz").build()).execute().use { response ->
            if (!response.isSuccessful) throw error(response)
            JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
                .getAsJsonArray("capabilities")?.any { it.asString == "catalog_favorite_download_v1" } == true
        }
        if (!supported) throw HubException("Обновите Хаб для изменения понравившихся треков каталога", 426)
        val (path, payload) = catalogFavoriteRequest(ref, starred, eventId, timestamp, client, context)
        val json = gson.toJson(payload).toRequestBody(JSON)
        retryCatalogTransport {
            call({ Request.Builder().url("$it$path").post(json).build() }) { }
        }
    }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun assistantTasks(): List<HubAssistantTask> =
        call({ Request.Builder().url("$it/api/assistant/tasks").build() }) { parseList(it, HubAssistantTask::class.java) }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun assistantFromText(text: String, url: String? = null, urlOnly: Boolean = false): HubAssistantTask {
        // Same as the PWA: a bare link → {url}; anything else → {text} (+ the link found in it).
        val payload = if (urlOnly && url != null) mapOf("url" to url) else mapOf("text" to text, "url" to url)
        val json = gson.toJson(payload).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/assistant/tasks").post(json).build() }) { parse(it, HubAssistantTask::class.java) }
    }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun assistantFromImages(resolver: ContentResolver, uris: List<Uri>): HubAssistantTask {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        uris.forEachIndexed { i, uri ->
            val file = SharedFile.of(resolver, uri, "image/png", "screenshot_$i")
            body.addFormDataPart("images", file.name, ContentUriBody(resolver, uri, file.mime))
        }
        val multipart = body.build()
        return call({ Request.Builder().url("$it/api/assistant/tasks").post(multipart).build() }) { parse(it, HubAssistantTask::class.java) }
    }

    /** Streams one audio file into the Hub's staging area, where the upload form picks it up. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun uploadAudio(resolver: ContentResolver, uri: Uri): List<HubUpload> {
        val file = SharedFile.of(resolver, uri, "audio/mpeg", "audio")
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("files", file.name, ContentUriBody(resolver, uri, file.mime))
            .build()
        return call({ Request.Builder().url("$it/api/uploads").post(multipart).build() }) { parseList(it, HubUpload::class.java) }
    }

    /** A batch of listening events (`POST /api/plays/events`, ≤ 500); the Hub stores what is well-formed. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun playEvents(events: List<PlayEvent>): HubPlayEventsResult {
        val json = gson.toJson(mapOf("events" to eventPayload(events, supportsLaunchEvents()))).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/plays/events").post(json).build() }) { parse(it, HubPlayEventsResult::class.java) }
    }

    /** `POST /api/wave/start`: [body] — `{seed?, mode?, origin?, client?, restart_of?}` ([WaveSession.startBody]); up to ~10 s. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun waveStart(body: Map<String, Any>): WaveOut {
        val json = gson.toJson(body).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/wave/start").post(json).build() }) { parse(it, WaveOut::class.java) }
    }

    /** `POST /api/wave/{id}/next`: the events since the last call and the wave's tracks ahead; `404` — the wave is over. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun waveNext(sessionId: String, body: WaveNextIn): WaveOut {
        val json = gson.toJson(mapOf("played" to eventPayload(body.played, supportsLaunchEvents()), "queued" to body.queued)).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/wave/$sessionId/next").post(json).build() }) { parse(it, WaveOut::class.java) }
    }

    /** `POST /api/smart-shuffle/start`: [body] — [SmartSession.startBody]; the order of the list's own songs and the first recommendations. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun smartStart(body: Map<String, Any>): SmartOut {
        val json = gson.toJson(body).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/smart-shuffle/start").post(json).build() }) { parse(it, SmartOut::class.java) }
    }

    /** `POST /api/smart-shuffle/{id}/next`: the events of the recommendations since the last call and where the player is; `404` — the session is over. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun smartNext(sessionId: String, body: SmartNextIn): SmartOut {
        val json = gson.toJson(mapOf("played" to eventPayload(body.played, supportsLaunchEvents()), "at" to body.at)).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/smart-shuffle/$sessionId/next").post(json).build() }) { parse(it, SmartOut::class.java) }
    }

    /** «−»: not to recommend [mediaId] in this list again. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun smartHide(sessionId: String, mediaId: String) {
        val json = gson.toJson(trackIds(mediaId)).toRequestBody(JSON)
        call({ Request.Builder().url("$it/api/smart-shuffle/$sessionId/hide").post(json).build() }) { }
    }

    /** «+»: [mediaId] into the playlist (a catalog track is downloaded first); in «Понравившиеся» the Hub only notes it. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun smartAdd(sessionId: String, mediaId: String): SmartAddOut {
        val json = gson.toJson(trackIds(mediaId)).toRequestBody(JSON)
        return call({ Request.Builder().url("$it/api/smart-shuffle/$sessionId/add").post(json).build() }) { parse(it, SmartAddOut::class.java) }
    }

    /** [launch]: the Hub knows `launch_id` / `launch_origin` (`launch_events_v1`); an older one does not get them. */
    internal fun eventPayload(events: List<PlayEvent>, launch: Boolean = true) = events.map {
        gson.toJsonTree(it).asJsonObject.apply {
            remove("_compensation_chain"); remove("_account"); remove("_rejected")
            if (!launch) { remove("launch_id"); remove("launch_origin") }
        }
    }

    /** `GET /api/mixes/today`: the user's «Микс дня». */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun mixToday(): HubMixToday =
        call({ Request.Builder().url("$it/api/mixes/today").build() }) { parse(it, HubMixToday::class.java) }

    /** «Не нравится» a track: [mediaId] is a Navidrome song or `hub:<ref>`. */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun dislike(mediaId: String, artist: String?, title: String?, context: String?, client: String) {
        val body = trackIds(mediaId) + mapOf("artist" to artist, "title" to title, "context" to context, "client" to client)
        val json = gson.toJson(body).toRequestBody(JSON)
        call({ Request.Builder().url("$it/api/dislikes").post(json).build() }) { }
    }

    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun undislike(mediaId: String, client: String) {
        val (name, value) = trackIds(mediaId).entries.first()
        call({ base ->
            val url = "$base/api/dislikes".toHttpUrl().newBuilder()
                .addQueryParameter(name, value).addQueryParameter("client", client).build()
            Request.Builder().url(url).delete().build()
        }) { }
    }

    private fun trackIds(mediaId: String): Map<String, String> =
        if (HubTracks.isHub(mediaId)) mapOf("ref" to mediaId.removePrefix(HubTracks.ID_PREFIX))
        else mapOf("nd_song_id" to mediaId)

    /** Null when the Hub does not know the song (404, e.g. it was not downloaded through the Hub). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun trackSource(ndSongId: String): HubTrackSource? = try {
        call({
            val url = "$it/api/library/track-source".toHttpUrl().newBuilder().addQueryParameter("nd_song_id", ndSongId).build()
            Request.Builder().url(url).build()
        }) { parse(it, HubTrackSource::class.java) }
    } catch (e: HubException) {
        if (e.code == 404) null else throw e
    }

    /** `GET /api/listen?ref=`: the file the Hub plays for a catalog track (null when it has none, 404). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun listenInfo(ref: String): HubTrackSource? = try {
        call({
            val url = "$it/api/listen".toHttpUrl().newBuilder().addQueryParameter("ref", ref).build()
            Request.Builder().url(url).build()
        }) { parse(it, HubTrackSource::class.java) }
    } catch (e: HubException) {
        if (e.code == 404) null else throw e
    }

    /** Null when the Hub has no app build published (404). */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun latestRelease(): HubAppRelease? {
        val base = baseUrl()
        fixedCookieHttp.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(Request.Builder().url("$base/app/latest.json").build()).execute().use { r ->
            if (r.code == 404) return null
            if (!r.isSuccessful) throw error(r)
            return parse(r, HubAppRelease::class.java)
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private fun <T> parse(r: Response, type: Class<T>): T {
        val text = r.body?.string().orEmpty()
        return gson.fromJson(text, type) ?: throw HubException("Пустой ответ Хаба")
    }

    private fun <T> parseList(r: Response, type: Class<T>): List<T> {
        val text = r.body?.string().orEmpty()
        val array = JsonParser.parseString(text).asJsonArray
        return array.map { gson.fromJson(it, type) }
    }

    @JvmStatic
    fun error(r: Response): HubException {
        val detail = try {
            val obj = JsonParser.parseString(r.body?.string().orEmpty()).asJsonObject
            val d = obj.get("detail")
            if (d != null && d.isJsonPrimitive) d.asString else null
        } catch (e: Exception) {
            null
        }
        val message = when {
            !detail.isNullOrBlank() -> detail
            r.code == 401 -> "Хаб не принял логин и пароль Navidrome"
            r.code == 413 -> "Файл слишком большой"
            else -> "Ошибка Хаба (HTTP ${r.code})"
        }
        return HubException(message, r.code)
    }

    @JvmStatic
    fun flushCookies() {
        try {
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Cookie flush failed", t)
        }
    }

    /** OkHttp cookies stored in the WebView cookie store, with a memory fallback when WebView is unavailable. */
    private class WebViewCookieJar : CookieJar {
        private val memory = ConcurrentHashMap<String, MutableMap<String, Cookie>>()

        private fun manager(): CookieManager? = try {
            CookieManager.getInstance()
        } catch (t: Throwable) {
            Log.w(TAG, "WebView cookie store unavailable", t)
            null
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = manager()
            for (c in cookies) {
                if (cm != null) cm.setCookie(url.toString(), c.toString())
                else memory.getOrPut(url.host) { ConcurrentHashMap() }[c.name] = c
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cm = manager() ?: return memory[url.host]?.values?.filter { it.matches(url) }.orEmpty()
            val header = cm.getCookie(url.toString()) ?: return emptyList()
            return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
        }
    }
}

/** Display name, MIME type and size of a shared content URI, with a file extension the Hub accepts. */
data class SharedFile(val name: String, val mime: String, val size: Long) {
    companion object {
        private val AUDIO_EXT = mapOf(
            "audio/mpeg" to "mp3", "audio/mp3" to "mp3",
            "audio/mp4" to "m4a", "audio/x-m4a" to "m4a", "audio/m4a" to "m4a", "audio/aac" to "aac",
            "audio/flac" to "flac", "audio/x-flac" to "flac",
            "audio/ogg" to "ogg", "audio/opus" to "opus",
            "audio/wav" to "wav", "audio/x-wav" to "wav", "audio/wave" to "wav",
        )

        fun of(resolver: ContentResolver, uri: Uri, fallbackMime: String, fallbackName: String): SharedFile {
            var name: String? = null
            var size = -1L
            try {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) name = c.getString(ni)
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
            } catch (e: Exception) {
                Log.w("SharedFile", "Cannot query $uri", e)
            }
            if (name.isNullOrBlank()) name = uri.lastPathSegment?.substringAfterLast('/')
            val mime = resolver.getType(uri) ?: MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name?.substringAfterLast('.', "")?.lowercase()) ?: fallbackMime
            var finalName = name?.takeIf { it.isNotBlank() } ?: fallbackName
            if (!finalName.contains('.')) {
                val ext = AUDIO_EXT[mime] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
                if (ext != null) finalName = "$finalName.$ext"
            }
            return SharedFile(finalName, mime, size)
        }
    }
}

/** A request body that streams a content URI (re-opened on each write, so a retry can resend it). */
class ContentUriBody(
    private val resolver: ContentResolver,
    private val uri: Uri,
    mime: String,
    private val length: Long = -1,
) : RequestBody() {
    private val type = mime.toMediaTypeOrNull()

    override fun contentType(): MediaType? = type

    override fun contentLength(): Long = length

    override fun writeTo(sink: BufferedSink) {
        val input = resolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.source().use { sink.writeAll(it) }
    }
}
