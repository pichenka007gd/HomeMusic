package com.eddyizm.tempus.homemusic

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import com.eddyizm.tempus.App
import java.util.concurrent.atomic.AtomicReference

/**
 * When the app saves the phone's mobile traffic: the «Экономия мобильного трафика» setting is on and the active
 * network is metered (a mobile plan, or a hotspot the system marks as metered). It is the network's own flag,
 * not its transport: a Wi-Fi point of another phone is metered, a mobile plan without a cap is not.
 * Everything that loads audio for the phone asks [economy] (`QueueLoading`, `QueuePreloader`, the mix download).
 */
object HomeTraffic {
    const val PREF_ECONOMY = "homemusic_traffic_economy"

    /** How long a read of the network stays valid; the network callback of the service ends it sooner ([onNetworkChanged]). */
    private const val MEMO_MS = 2_000L

    private class Memo(val metered: Boolean, val at: Long)

    private val memo = AtomicReference<Memo?>()

    /** The setting is on (the default). */
    @JvmStatic
    fun enabled(): Boolean = App.getInstance().preferences.getBoolean(PREF_ECONOMY, true)

    /** Set by tests: the answer of [economy] without the app and the network. */
    @Volatile
    internal var forced: Boolean? = null

    /** Whether to save traffic now. Cheap: the network is read once per [MEMO_MS]. */
    @JvmStatic
    fun economy(): Boolean = forced ?: decide(enabled()) { isMetered() }

    /** The active network is metered. No network at all counts as metered: nothing is loaded in that case anyway. */
    @JvmStatic
    fun isMetered(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val known = memo.get()
        if (known != null && now - known.at < MEMO_MS) return known.metered
        val manager = App.getContext().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val metered = manager?.isActiveNetworkMetered ?: true
        memo.set(Memo(metered, now))
        return metered
    }

    /** The network changed (the service's callback): the next read goes to the system. */
    @JvmStatic
    fun onNetworkChanged() {
        memo.set(null)
    }

    /** The network is only read when the setting is on. */
    internal inline fun decide(enabled: Boolean, metered: () -> Boolean): Boolean = enabled && metered()

    /** How many next tracks the precache loads: at most one when saving, so a skip costs one head, not three. */
    @JvmStatic
    fun precacheCount(configured: Int, economy: Boolean): Int =
        if (economy) configured.coerceAtMost(1) else configured
}
