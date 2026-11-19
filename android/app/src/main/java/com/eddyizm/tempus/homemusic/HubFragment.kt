package com.eddyizm.tempus.homemusic

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.TooltipCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.databinding.FragmentHubBinding
import com.eddyizm.tempus.ui.activity.MainActivity
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout

/**
 * The Hub tab (PLAN.md stage 4.3, decision D4: WebView first). Native tabs «Каталог»,
 * «Загрузки», «Ассистент», «Мои файлы» drive the routes of the Hub PWA, which runs signed in
 * with the player's Navidrome account and without its own tab bar.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class HubFragment : Fragment() {
    companion object {
        const val JS_INTERFACE = "HomeMusicAndroid"
        const val ARG_ROUTE = "route"
        /** Fired on the page when the tab is shown again: the PWA checks for a new build (web/src/lib/update.ts). */
        const val RESUME_EVENT = "hm:resume"
        private const val STATE_ROUTE = "hub_route"

        private data class Section(val titleRes: Int, val root: String)

        private val SECTIONS = listOf(
            Section(R.string.homemusic_tab_catalog, "/search"),
            Section(R.string.homemusic_tab_downloads, "/downloads"),
            Section(R.string.homemusic_tab_assistant, "/assistant"),
            Section(R.string.homemusic_tab_files, "/library"),
        )

        /** Which native tab a PWA route belongs to (-1: none, e.g. «Ещё»). */
        fun sectionOf(route: String): Int {
            val path = route.substringBefore('?')
            return when {
                path.startsWith("/search") || path.startsWith("/artist/") || path.startsWith("/album/") -> 0
                path.startsWith("/downloads") -> 1
                path.startsWith("/assistant") -> 2
                path.startsWith("/library") || path.startsWith("/upload") || path.startsWith("/playlists") -> 3
                else -> -1
            }
        }

        /** "/#/album/dz:1?x" from a full Hub URL; null for other pages. */
        fun routeOf(url: String?): String? {
            if (url == null) return null
            val i = url.indexOf('#')
            if (i < 0) return "/"
            return url.substring(i + 1).ifEmpty { "/" }
        }

        // Each section remembers where the user was, like the PWA's own tab bar does.
        private val lastBySection = arrayOfNulls<String>(SECTIONS.size)
    }

    private var _bind: FragmentHubBinding? = null
    private val bind get() = _bind!!
    private lateinit var activity: MainActivity

    private var webView: WebView? = null
    private var currentRoute: String = "/search"
    private var syncingTabs = false
    private var lastUnauthorizedAt = 0L
    private var pageLoadedFrom: String?
        get() = HubWebHolder.loadedBase
        set(value) { HubWebHolder.loadedBase = value }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = filePathCallback ?: return@registerForActivityResult
        filePathCallback = null
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            callback.onReceiveValue(null)
            return@registerForActivityResult
        }
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        if (uris.isEmpty()) data.data?.let { uris.add(it) }
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (activity.isBottomSheetExpanded) {
                activity.collapseBottomSheetDelayed()
            } else if (webView?.canGoBack() == true) {
                webView?.goBack()
            } else {
                isEnabled = false
                activity.onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    private val sheetCallback = object : BottomSheetBehavior.BottomSheetCallback() {
        override fun onStateChanged(bottomSheet: View, newState: Int) = updateBottomInset()
        override fun onSlide(bottomSheet: View, slideOffset: Float) {}
    }

    private val navLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateBottomInset() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        activity = requireActivity() as MainActivity
        _bind = FragmentHubBinding.inflate(inflater, container, false)
        return bind.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initTabs()
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // no bottom navigation in landscape: the drawer is the way to the other tabs
            bind.hubMenuButton.visibility = View.VISIBLE
            bind.hubMenuButton.setOnClickListener {
                activity.findViewById<DrawerLayout>(R.id.drawer_layout)?.openDrawer(GravityCompat.START)
            }
        }

        bind.hubRetryButton.setOnClickListener { connect(currentRoute, force = true) }
        bind.hubSettingsButton.setOnClickListener { activity.navController.navigate(R.id.settingsFragment) }

        val obtained = HubWebHolder.obtain(activity)
        val web = obtained.webView
        webView = web
        bind.hubWebContainer.addView(web, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web.webViewClient = client
        web.webChromeClient = chromeClient
        HubWebHolder.HubBridge.unauthorizedListener = { onUnauthorized() }
        HubWebHolder.HubBridge.playListener = { ids, index -> HubPlayer.play(activity, ids, index) }
        HubWebHolder.HubBridge.playQueueListener = { items, index -> HubPlayer.playQueue(activity, items, index) }
        HubWebHolder.HubBridge.playWaveListener = { HubWave.play(activity, it) }
        HubWebHolder.HubBridge.pauseNativeListener = { HubAudio.pauseNative(activity) }
        HubWebHolder.HubBridge.webPlayerListener = { showNativePlayer(!it) }
        HubWebHolder.applyTheme(activity)

        activity.onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        val requested = HubNavigator.consumePendingRoute()
            ?: arguments?.getString(ARG_ROUTE)?.also { arguments?.remove(ARG_ROUTE) }
        val restored = savedInstanceState?.getString(STATE_ROUTE)
        when {
            requested != null -> connect(requested)
            !obtained.fresh && pageLoadedFrom != null -> onRouteChanged(routeOf(web.url) ?: currentRoute)
            else -> connect(restored ?: lastBySection[0] ?: SECTIONS[0].root)
        }

        HubNavigator.pendingRoute.observe(viewLifecycleOwner) { route ->
            if (route != null) {
                HubNavigator.consumePendingRoute()
                connect(route)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        activity.toggleBottomNavigationBarVisibilityOnOrientationChange()
        // one player on screen: while the page shows its own (track preview), the app's player is hidden
        activity.setBottomSheetVisibility(!HubWebHolder.HubBridge.webPlayerVisible)
        activity.bottomSheetBehavior.addBottomSheetCallback(sheetCallback)
        activity.bind.bottomNavigation.addOnLayoutChangeListener(navLayoutListener)
        bind.root.post {
            updateBottomInset()
            // the WebView keeps its page for the whole process: a rebuilt Hub reaches it only by a reload,
            // which the page itself makes when it is idle
            if (pageLoadedFrom != null) HubWebHolder.dispatchEvent(RESUME_EVENT)
        }
        HubAudio.watch(activity)
    }

    override fun onStop() {
        activity.setBottomSheetVisibility(true) // the other tabs show the app's player
        activity.bottomSheetBehavior.removeBottomSheetCallback(sheetCallback)
        activity.bind?.bottomNavigation?.removeOnLayoutChangeListener(navLayoutListener)
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_ROUTE, currentRoute)
    }

    override fun onDestroyView() {
        HubWebHolder.HubBridge.unauthorizedListener = null
        HubWebHolder.HubBridge.playListener = null
        HubWebHolder.HubBridge.playQueueListener = null
        HubWebHolder.HubBridge.playWaveListener = null
        HubWebHolder.HubBridge.pauseNativeListener = null
        HubWebHolder.HubBridge.webPlayerListener = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView?.let { HubWebHolder.release(it, activity.applicationContext) }
        webView = null
        _bind = null
        super.onDestroyView()
    }

    // --- tabs -------------------------------------------------------------------------------

    private fun initTabs() {
        val tabs = bind.hubTabs
        SECTIONS.forEach {
            val tab = tabs.newTab().setText(it.titleRes)
            tabs.addTab(tab, false)
            TooltipCompat.setTooltipText(tab.view, null) // the label is on screen already
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (syncingTabs) return
                val i = tab.position
                navigateTo(lastBySection[i] ?: SECTIONS[i].root)
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
                if (syncingTabs) return
                navigateTo(SECTIONS[tab.position].root)
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
        })
    }

    private fun onRouteChanged(route: String) {
        currentRoute = route
        val section = sectionOf(route)
        if (section >= 0) lastBySection[section] = route
        val tabs = _bind?.hubTabs ?: return
        syncingTabs = true
        try {
            if (section >= 0 && tabs.selectedTabPosition != section) tabs.getTabAt(section)?.select()
            else if (section < 0) tabs.selectTab(null)
        } finally {
            syncingTabs = false
        }
        backCallback.isEnabled = webView?.canGoBack() == true
    }

    // --- loading ----------------------------------------------------------------------------

    /** Makes sure there is a Hub session, then shows [route] (loading the PWA if needed). */
    private fun connect(route: String, force: Boolean = false) {
        currentRoute = route
        showError(null)
        showProgress(true)
        HomeMusic.io.execute {
            val result = runCatching {
                if (force) HubClient.invalidate()
                val base = HubClient.baseUrl()
                HubClient.ensureSession()
                base
            }
            HomeMusic.main.post {
                if (_bind == null) return@post
                result.onSuccess { base -> load(base, route, force) }
                    .onFailure { e ->
                        showProgress(false)
                        showError(e.message ?: getString(R.string.homemusic_hub_unreachable))
                    }
            }
        }
    }

    private fun load(base: String, route: String, force: Boolean) {
        val web = webView ?: return
        if (!force && pageLoadedFrom == base && web.url?.startsWith(base) == true) {
            showProgress(false)
            navigateTo(route)
            return
        }
        pageLoadedFrom = base
        web.loadUrl("$base/?embedded=1#$route")
    }

    private fun navigateTo(route: String) {
        val web = webView ?: return
        if (pageLoadedFrom == null) {
            connect(route)
            return
        }
        if (routeOf(web.url) == route) return
        val js = "window.location.hash = " + HubClient.gson.toJson(route) + ";"
        web.evaluateJavascript(js, null)
    }

    private fun onUnauthorized() {
        // The PWA lost its session: sign in again with the player's account instead of showing its login page.
        val now = SystemClock.elapsedRealtime()
        if (now - lastUnauthorizedAt < 30_000) return
        lastUnauthorizedAt = now
        pageLoadedFrom = null
        connect(currentRoute, force = true)
    }

    private fun showProgress(visible: Boolean) {
        _bind?.hubProgress?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showError(message: String?) {
        val b = _bind ?: return
        b.hubError.visibility = if (message == null) View.GONE else View.VISIBLE
        webView?.visibility = if (message == null) View.VISIBLE else View.INVISIBLE
        if (message != null) b.hubErrorText.text = message
    }

    private fun updateBottomInset() {
        val b = _bind ?: return
        val main = activity.bind ?: return
        val behavior = activity.bottomSheetBehavior
        val sheetShown = main.playerBottomSheet.visibility == View.VISIBLE && behavior.state != BottomSheetBehavior.STATE_HIDDEN
        val navShown = main.bottomNavigation.visibility == View.VISIBLE
        // What covers the bottom of the page, by its real top: the collapsed player sits higher than peekHeight
        // (the behavior adds the gesture-bar inset), and the page's own player is drawn right above it.
        val cover = when {
            sheetShown && behavior.state != BottomSheetBehavior.STATE_COLLAPSED -> return // open or moving
            sheetShown -> main.playerBottomSheet
            navShown -> main.bottomNavigation
            else -> null
        }
        val inset = if (cover == null) 0 else (windowTop(b.root) + b.root.height - windowTop(cover)).coerceAtLeast(0)
        if (b.root.paddingBottom != inset) b.root.setPadding(0, 0, 0, inset)
    }

    private fun showNativePlayer(show: Boolean) {
        if (_bind == null) return
        activity.setBottomSheetVisibility(show)
        bind.root.post { updateBottomInset() }
    }

    private fun windowTop(view: View): Int = IntArray(2).also { view.getLocationInWindow(it) }[1]

    // --- WebView callbacks ------------------------------------------------------------------

    private fun isHubUrl(uri: Uri): Boolean {
        val base = pageLoadedFrom?.let { Uri.parse(it) } ?: return false
        return uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (isHubUrl(uri)) return false
            // Source pages (hitmoz, YouTube, Deezer…) open in the browser, the WebView stays on the Hub.
            try {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(requireContext(), uri.toString(), Toast.LENGTH_SHORT).show()
            }
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            if (!HubWebHolder.hasDocumentStartScript()) view.evaluateJavascript(HubWebHolder.EMBEDDED_SCRIPT, null)
            // a (re)loaded page has no player of its own yet
            if (HubWebHolder.HubBridge.webPlayerVisible) {
                HubWebHolder.HubBridge.webPlayerVisible = false
                showNativePlayer(true)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            if (!HubWebHolder.hasDocumentStartScript()) view.evaluateJavascript(HubWebHolder.EMBEDDED_SCRIPT, null)
            showProgress(false)
            HubWebHolder.applyTheme(activity)
            routeOf(url)?.let { onRouteChanged(it) }
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            routeOf(url)?.let { onRouteChanged(it) }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame) return
            showProgress(false)
            pageLoadedFrom = null
            HubClient.invalidate()
            showError(getString(R.string.homemusic_hub_unreachable) + "\n" + error.description)
        }
    }

    private val chromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams,
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            val intent = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
            val mimes = params.acceptTypes
                .flatMap { it.split(',') }
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .mapNotNull { if (it.startsWith(".")) MimeTypeMap.getSingleton().getMimeTypeFromExtension(it.substring(1)) else it }
                .distinct()
            when {
                mimes.isEmpty() -> intent.type = "*/*"
                mimes.size == 1 -> intent.type = mimes[0]
                else -> {
                    intent.type = "*/*"
                    intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                }
            }
            if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            return try {
                fileChooser.launch(Intent.createChooser(intent, getString(R.string.homemusic_pick_files)))
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                callback.onReceiveValue(null)
                false
            }
        }
    }
}
