package com.eddyizm.tempus.homemusic

import android.content.Context
import android.util.Log
import okhttp3.Call
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Listens to the Hub's server-sent events (`GET /api/events`) while the app is open, so
 * offline playlists and assistant notifications update without waiting for the next 15-minute poll.
 * Download, assistant and library events trigger a debounced [HubNotifier.check].
 */
object HubEvents {
    private const val TAG = "HubEvents"
    private const val DEBOUNCE_MS = 3000L

    @Volatile private var running = false
    @Volatile private var call: Call? = null
    private var thread: Thread? = null
    private var appContext: Context? = null

    private val sseClient by lazy {
        HubClient.http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
    }

    private val checkRunnable = Runnable {
        val context = appContext ?: return@Runnable
        HomeMusic.io.execute {
            try {
                HubNotifier.check(context)
            } catch (e: Exception) {
                Log.w(TAG, "Check after event failed", e)
            }
        }
    }

    @JvmStatic
    @Synchronized
    fun start(context: Context) {
        appContext = context.applicationContext
        if (running) return
        running = true
        thread = Thread({ loop() }, "HubEvents").apply { isDaemon = true; start() }
    }

    @JvmStatic
    @Synchronized
    fun stop() {
        running = false
        call?.cancel()
        call = null
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        var backoff = 5_000L
        while (running) {
            try {
                HubClient.ensureSession()
                val base = HubClient.baseUrl()
                val request = Request.Builder().url("$base/api/events").header("Accept", "text/event-stream").build()
                val c = sseClient.newCall(request)
                call = c
                c.execute().use { response ->
                    if (!response.isSuccessful) throw HubClient.error(response)
                    backoff = 5_000L
                    Log.d(TAG, "Listening to $base/api/events")
                    // Something may have finished while we were not listening.
                    scheduleCheck()
                    val source = response.body?.source() ?: return@use
                    var event = "message"
                    while (running) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> event = "message"
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> if (event == "download" || event == "assistant" || event == "library") {
                                Log.d(TAG, "Event: $event")
                                if (event == "library") HomeLibrary.libraryChanged()
                                scheduleCheck()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.d(TAG, "Event stream dropped: ${e.message}")
                HubClient.invalidate()
            }
            if (!running) break
            try {
                Thread.sleep(backoff)
            } catch (e: InterruptedException) {
                break
            }
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun scheduleCheck() {
        HomeMusic.main.removeCallbacks(checkRunnable)
        HomeMusic.main.postDelayed(checkRunnable, DEBOUNCE_MS)
    }
}
