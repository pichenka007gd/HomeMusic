package com.eddyizm.tempus.homemusic

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.ui.dialog.BatteryOptimizationDialog
import com.eddyizm.tempus.util.DownloadRepair
import com.eddyizm.tempus.util.Preferences
import com.google.android.material.navigation.NavigationBarView
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Entry points the Tempus code calls into. Every method is a no-op outside the homemusic flavor,
 * so the stock tempus / degoogled builds behave as before.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HomeMusic {
    private const val TAG = "HomeMusic"

    /** Hub PWA route to open in the Hub tab, e.g. "/assistant/12" (notifications, share). */
    const val EXTRA_HUB_ROUTE = "homemusic_hub_route"

    @JvmField
    val io: ExecutorService = Executors.newCachedThreadPool()

    @JvmField
    val main = Handler(Looper.getMainLooper())

    private var pendingRoute: String? = null
    private var batteryAsked = false

    /** Tempus asks about battery optimization right at start; HomeMusic waits until the server has answered, so
     * the question never lands on top of «Нет связи с сервером» (see [onServerAnswered]). */
    @JvmStatic
    fun deferBatteryDialog(): Boolean = HubConfig.isEnabled()

    /** The server does not answer: the offline strip says so (Tempus shows it only when the phone has no network). */
    @JvmStatic
    fun onServerUnreachable(activity: MainActivity) {
        if (!HubConfig.isEnabled()) return
        val strip = activity.bind?.offlineModeTextView ?: return
        strip.setText(R.string.homemusic_server_offline)
        strip.visibility = View.VISIBLE
    }

    @JvmStatic
    fun onServerAnswered(activity: MainActivity) {
        if (!HubConfig.isEnabled()) return
        activity.bind?.offlineModeTextView?.let { strip ->
            if (strip.text == activity.getString(R.string.homemusic_server_offline)) {
                strip.setText(R.string.activity_info_offline_mode)
                strip.visibility = View.GONE
            }
        }
        if (batteryAsked) return
        batteryAsked = true
        val power = activity.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (power.isIgnoringBatteryOptimizations(activity.packageName) || !Preferences.askForOptimization()) return
        if (activity.supportFragmentManager.isStateSaved) return
        BatteryOptimizationDialog().show(activity.supportFragmentManager, null)
    }

    @JvmStatic
    fun onCreate(activity: MainActivity) {
        if (!HubConfig.isEnabled()) return
        // every tab signed: the icons alone (equalizer, compass) say little
        activity.bind.bottomNavigation.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED
        HubNotifier.ensureChannel(activity)
        HubSyncWorker.schedule(activity.applicationContext)
        MixOffline.schedule(activity.applicationContext)
        HubPlays.init()
    }

    @JvmStatic
    fun onStart(activity: MainActivity) {
        if (!HubConfig.isEnabled() || !DownloadRepair.isUserAuthenticated()) return
        HubPlays.refreshFavorites()
        CatalogFavorites.start()
        HubEvents.start(activity)
        HubNotifier.consumePendingSync(activity)
        HubUpdater.checkOnStart(activity)
    }

    @JvmStatic
    fun onStop() {
        if (!HubConfig.isEnabled()) return
        HubEvents.stop()
    }

    /** Opens the Hub route an intent carries. Returns true when the intent was one of ours. */
    @JvmStatic
    fun handleIntent(activity: MainActivity, intent: Intent?): Boolean {
        if (!HubConfig.isEnabled() || intent == null) return false
        val route = intent.getStringExtra(EXTRA_HUB_ROUTE) ?: return false
        intent.removeExtra(EXTRA_HUB_ROUTE)
        if (!DownloadRepair.isUserAuthenticated()) {
            pendingRoute = route
            return true
        }
        HubNavigator.open(activity, route)
        return true
    }

    /** Called once the player is signed in: a route that arrived before that is opened now. */
    @JvmStatic
    fun onAuthenticated(activity: MainActivity) {
        if (!HubConfig.isEnabled()) return
        val route = pendingRoute ?: return
        pendingRoute = null
        HubNavigator.open(activity, route)
    }

    /** The user logged out of the player: the Hub session goes with it. */
    @JvmStatic
    fun onLogout(context: Context) {
        if (!HubConfig.isEnabled()) return
        HubEvents.stop()
        HubWebHolder.destroy()
        HubClient.invalidate()
        HubNotifier.reset(context)
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (t: Throwable) {
            Log.w(TAG, "Cannot clear Hub cookies", t)
        }
    }
}
