package com.eddyizm.tempus.homemusic

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.eddyizm.tempus.App
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * What the app's player loaded over a metered network, by day: [PLAY] — the playing track (and the downloads the
 * user asked for), [PRELOAD] — the heads of the next tracks (`QueuePreloader`). It is counted at the HTTP source
 * of the player, so the numbers are exact and need no permission; covers and the Hub's pages are not in them
 * ([systemTotal] gives the whole app's mobile traffic when the system lets it).
 */
@UnstableApi
object HomeTrafficStats {
    private const val TAG = "HomeTrafficStats"
    private const val PREFS = "homemusic_traffic_stats"
    private const val KEEP_DAYS = 30
    private const val FLUSH_BYTES = 256 * 1024L
    private const val FLUSH_MS = 5_000L

    const val PLAY = "play"
    const val PRELOAD = "preload"

    /** The thread of `QueuePreloader`: what it loads is the [PRELOAD] kind. */
    const val PRELOAD_THREAD = "queue-preloader"

    private val pendingPlay = AtomicLong()
    private val pendingPreload = AtomicLong()
    private var lastFlush = 0L

    /** Hung on the player's HTTP source (`DownloadUtil`). */
    @JvmField
    val listener: TransferListener = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

        override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
            if (!isNetwork || !HomeTraffic.isMetered()) return
            val preload = Thread.currentThread().name == PRELOAD_THREAD
            (if (preload) pendingPreload else pendingPlay).addAndGet(bytesTransferred.toLong())
            flushIfDue()
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            if (isNetwork) Log.d(TAG, "end ${label(dataSpec.uri)} at ${dataSpec.position}")
        }
    }

    private fun label(uri: Uri) = uri.lastPathSegment ?: uri.toString()

    private fun prefs() = App.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun flushIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (pendingPlay.get() + pendingPreload.get() < FLUSH_BYTES && now - lastFlush < FLUSH_MS) return
        flush()
    }

    /** Writes what is pending; called before the numbers are read. */
    @Synchronized
    fun flush() {
        lastFlush = SystemClock.elapsedRealtime()
        val play = pendingPlay.getAndSet(0)
        val preload = pendingPreload.getAndSet(0)
        if (play == 0L && preload == 0L) return
        val day = dayKey(System.currentTimeMillis())
        val prefs = prefs()
        val edit = prefs.edit()
        if (play > 0) edit.putLong("$day:$PLAY", prefs.getLong("$day:$PLAY", 0) + play)
        if (preload > 0) edit.putLong("$day:$PRELOAD", prefs.getLong("$day:$PRELOAD", 0) + preload)
        val old = dayKey(System.currentTimeMillis() - KEEP_DAYS * 86_400_000L)
        prefs.all.keys.filter { it.substringBefore(':') < old }.forEach { edit.remove(it) }
        edit.apply()
        Log.d(TAG, "metered: play +$play, preload +$preload ($day)")
    }

    /** Bytes of [kind] over the last [days] days, today included. */
    fun bytes(kind: String, days: Int, now: Long = System.currentTimeMillis()): Long {
        flush()
        val prefs = prefs()
        return keysOf(now, days).sumOf { prefs.getLong("$it:$kind", 0) }
    }

    /**
     * The whole app's mobile traffic over the last [days] days from the system's statistics (covers, the Hub's
     * pages and all), or null when the system does not give it. Own data needs no permission.
     */
    fun systemTotal(days: Int): Long? = runCatching {
        val manager = App.getContext().getSystemService(Context.NETWORK_STATS_SERVICE) as NetworkStatsManager
        val end = System.currentTimeMillis()
        val start = startOfDay(end, days)
        val stats = manager.queryDetailsForUid(ConnectivityManager.TYPE_MOBILE, null, start, end, Process.myUid())
        var total = 0L
        val bucket = android.app.usage.NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            total += bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        total
    }.onFailure { Log.d(TAG, "system statistics: $it") }.getOrNull()

    private fun dayKey(at: Long) = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(at))

    /** The day keys of the last [days] days up to and including the day of [now]. */
    internal fun keysOf(now: Long, days: Int): List<String> = (0 until days).map { back ->
        dayKey(Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, -back) }.timeInMillis)
    }

    private fun startOfDay(now: Long, daysBack: Int): Long = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.DAY_OF_YEAR, -(daysBack - 1))
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
