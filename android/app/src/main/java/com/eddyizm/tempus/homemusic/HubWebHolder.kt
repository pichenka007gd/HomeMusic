package com.eddyizm.tempus.homemusic

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.MutableContextWrapper
import android.content.res.Configuration
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.eddyizm.tempus.BuildConfig
import com.eddyizm.tempus.R
import com.google.android.material.color.MaterialColors

/**
 * Keeps one Hub WebView alive across bottom-navigation switches, so the PWA keeps its page,
 * scroll position and in-progress forms. The view is re-parented onto each new fragment view and
 * its context is swapped to the current activity (MutableContextWrapper).
 */
object HubWebHolder {
    private var webView: WebView? = null
    private var contextWrapper: MutableContextWrapper? = null

    /** Hub address the loaded page came from; null until a page loaded (or after an error). */
    var loadedBase: String? = null

    /** Hides the PWA's own tab bar (the app shows native tabs) and reports expired sessions. */
    const val EMBEDDED_SCRIPT = """
(function () {
  if (window.__HOMEMUSIC_EMBEDDED__) return;
  window.__HOMEMUSIC_EMBEDDED__ = true;
  var css = 'nav[aria-label="Разделы"]{display:none!important}' +
            'main{padding-bottom:16px!important}';
  function addStyle() {
    if (document.getElementById('hm-embedded-style')) return;
    var s = document.createElement('style');
    s.id = 'hm-embedded-style';
    s.textContent = css;
    (document.head || document.documentElement).appendChild(s);
    if (document.documentElement) document.documentElement.classList.add('hm-embedded');
  }
  if (document.head) addStyle(); else document.addEventListener('DOMContentLoaded', addStyle);
  window.addEventListener('hm:unauthorized', function () {
    try { HomeMusicAndroid.onUnauthorized(); } catch (e) {}
  });
})();
"""

    /** Exposed to the page as `HomeMusicAndroid`; calls land on whichever fragment shows the view. */
    object HubBridge {
        @Volatile
        var unauthorizedListener: (() -> Unit)? = null

        @Volatile
        var playListener: ((List<String>, Int) -> Unit)? = null

        @Volatile
        var playQueueListener: ((List<HubTracks.Item>, Int) -> Unit)? = null

        @Volatile
        var playWaveListener: ((WaveOut) -> Unit)? = null

        @Volatile
        var pauseNativeListener: (() -> Unit)? = null

        @Volatile
        var webPlayerListener: ((Boolean) -> Unit)? = null

        /** The page's own player (catalog track before download) is on screen: the app's player hides meanwhile. */
        @Volatile
        var webPlayerVisible = false

        @JavascriptInterface
        fun isEmbedded(): Boolean = true

        @JavascriptInterface
        fun appVersion(): String = BuildConfig.VERSION_NAME

        @JavascriptInterface
        fun onUnauthorized() {
            HomeMusic.main.post { unauthorizedListener?.invoke() }
        }

        /** Plays in the app's player: [songIdsJson] is a JSON array of Navidrome song ids, [index] the one to start with. */
        @JavascriptInterface
        fun play(songIdsJson: String?, index: Int) {
            val ids = runCatching { HubClient.gson.fromJson(songIdsJson, Array<String>::class.java)?.toList() }.getOrNull()
            if (ids.isNullOrEmpty()) return
            HomeMusic.main.post { playListener?.invoke(ids, index) }
        }

        /**
         * Plays a list in the app's player (1.2.0+): [itemsJson] is a JSON array of [HubTracks.Item] — library songs
         * by Navidrome id and catalog tracks by ref, in the list's order; [index] is the one to start with.
         */
        @JavascriptInterface
        fun playQueue(itemsJson: String?, index: Int) {
            val items = runCatching { HubClient.gson.fromJson(itemsJson, Array<HubTracks.Item>::class.java)?.toList() }.getOrNull()
            if (items.isNullOrEmpty()) return
            HomeMusic.main.post { playQueueListener?.invoke(items, index) }
        }

        /**
         * Plays «Моя волна» in the app's player (1.6.0+): [waveJson] is the page's `WaveOut` of `/api/wave/start`;
         * the app keeps the wave going with `/api/wave/{id}/next` by itself ([HubWave]).
         */
        @JavascriptInterface
        fun playWave(waveJson: String?) {
            val out = runCatching { HubClient.gson.fromJson(waveJson, WaveOut::class.java) }.getOrNull() ?: return
            HomeMusic.main.post { playWaveListener?.invoke(out) }
        }

        /** The page started its own listening (a catalog track before download): pause the app's player. */
        @JavascriptInterface
        fun pauseNative() {
            HomeMusic.main.post { pauseNativeListener?.invoke() }
        }

        /** The page hands text to another app (an invite code to a messenger): the system share sheet. */
        @JavascriptInterface
        fun shareText(text: String?) {
            if (text.isNullOrBlank()) return
            HomeMusic.main.post {
                val activity = contextWrapper?.baseContext as? Activity ?: return@post
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.homemusic_share_title)))
            }
        }

        /** The page shows or hides its own mini player. */
        @JavascriptInterface
        fun webPlayer(visible: Boolean) {
            HomeMusic.main.post {
                webPlayerVisible = visible
                webPlayerListener?.invoke(visible)
            }
        }
    }

    /**
     * Matches the page to the app's theme: light or dark palette and the app's own background color, so the Hub
     * has no seam with the status bar, the player and the navigation (the page keeps the last one for its next start).
     */
    fun applyTheme(activity: Activity) {
        val view = webView ?: return
        val light = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_YES
        val bg = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, Color.BLACK)
        view.setBackgroundColor(bg)
        val hex = String.format("#%06X", 0xFFFFFF and bg)
        view.evaluateJavascript("window.hmApplyTheme && window.hmApplyTheme($light, '$hex');", null)
    }

    /** Fires a DOM event [name] on the page's window (main thread). */
    fun dispatchEvent(name: String) {
        webView?.evaluateJavascript("window.dispatchEvent(new Event(${HubClient.gson.toJson(name)}));", null)
    }

    /** Fires a `CustomEvent` [name] with [detail] (serialized to JSON) on the page's window (main thread). */
    fun dispatchEvent(name: String, detail: Any) {
        val gson = HubClient.gson
        webView?.evaluateJavascript("window.dispatchEvent(new CustomEvent(${gson.toJson(name)}, {detail: ${gson.toJson(detail)}}));", null)
    }

    class Obtained(val webView: WebView, val fresh: Boolean)

    @SuppressLint("SetJavaScriptEnabled")
    fun obtain(activity: Activity): Obtained {
        webView?.let { existing ->
            contextWrapper?.baseContext = activity
            (existing.parent as? ViewGroup)?.removeView(existing)
            return Obtained(existing, false)
        }
        val wrapper = MutableContextWrapper(activity)
        val view = WebView(wrapper)
        view.setBackgroundColor(Color.parseColor("#0B0B0B"))
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            userAgentString = "$userAgentString HomeMusicAndroid/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.addJavascriptInterface(HubBridge, HubFragment.JS_INTERFACE)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, EMBEDDED_SCRIPT, setOf("*"))
        }
        webView = view
        contextWrapper = wrapper
        return Obtained(view, true)
    }

    /** Detaches the view from a fragment that is going away; the page stays loaded. */
    fun release(view: WebView, appContext: android.content.Context) {
        (view.parent as? ViewGroup)?.removeView(view)
        view.webViewClient = WebViewClient()
        view.webChromeClient = WebChromeClient()
        contextWrapper?.baseContext = appContext
    }

    fun hasDocumentStartScript(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    @JvmStatic
    fun destroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.removeJavascriptInterface(HubFragment.JS_INTERFACE)
            it.destroy()
        }
        webView = null
        contextWrapper = null
        loadedBase = null
    }
}
