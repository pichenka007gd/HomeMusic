package com.eddyizm.tempus.homemusic

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.eddyizm.tempus.R
import com.eddyizm.tempus.repository.PlaylistRepository
import com.eddyizm.tempus.ui.activity.MainActivity
import com.eddyizm.tempus.util.DownloadRepair
import com.google.gson.reflect.TypeToken

/**
 * Notifies about assistant tasks and keeps offline playlists in step with Hub imports.
 *
 * Both the background worker and the live event stream call [check]; the persisted state makes
 * each task transition and each new import are processed once.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
object HubNotifier {
    private const val TAG = "HubNotifier"
    const val CHANNEL_ID = "homemusic_hub"
    private const val PREFS = "homemusic_notifier"
    private const val KEY_STATE = "state"
    private const val KEY_PENDING_SYNC = "pending_kept_sync"
    private const val MAX_REMEMBERED = 500

    internal class State {
        var initialized = false
        var doneItems = mutableListOf<Long>()
        var tasks = mutableMapOf<String, String>()
    }

    /** What one check posts and whether it starts a «keep synced» sync. */
    internal class Plan {
        val tasks = mutableListOf<HubAssistantTask>()
        var newlyDone = false
    }

    private val lock = Any()

    @JvmStatic
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.homemusic_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        channel.description = context.getString(R.string.homemusic_channel_description)
        nm.createNotificationChannel(channel)
    }

    /** Polls the Hub once and posts what changed since the previous check. */
    @JvmStatic
    @WorkerThread
    fun check(context: Context) {
        if (!HubConfig.isEnabled() || !DownloadRepair.isUserAuthenticated()) return
        val items = HubClient.downloads(200)
        val tasks = try {
            HubClient.assistantTasks()
        } catch (e: Exception) {
            Log.w(TAG, "Assistant tasks unavailable", e)
            emptyList()
        }
        process(context.applicationContext, items, tasks)
    }

    private fun process(context: Context, items: List<HubDownloadItem>, tasks: List<HubAssistantTask>) {
        val plan = synchronized(lock) {
            val state = load(context)
            plan(state, items, tasks).also {
                trim(state)
                save(context, state)
            }
        }
        Log.d(TAG, "Checked ${items.size} downloads, ${tasks.size} tasks: ${plan.tasks.size} to notify, newly done: ${plan.newlyDone}")
        plan.tasks.forEach { notifyTask(context, it) }
        if (plan.newlyDone) requestKeptSync(context)
    }

    /**
     * Decides what changed since [state] (and records it there). The first check after login only remembers.
     * Server downloads only trigger offline sync. Quiet imports ([HubDownloadItem.star]) skip that too.
     */
    internal fun plan(state: State, items: List<HubDownloadItem>, tasks: List<HubAssistantTask>): Plan {
        val plan = Plan()
        val first = !state.initialized
        for (item in items) {
            if (!item.star && item.status == "done" && item.id !in state.doneItems) {
                state.doneItems.add(item.id)
                if (!first) plan.newlyDone = true
            }
        }

        // Assistant tasks: ready for confirmation, or failed.
        for (task in tasks) {
            val status = task.status ?: continue
            val key = task.id.toString()
            if (state.tasks[key] == status) continue
            state.tasks[key] = status
            if (!first && (status == "awaiting_confirmation" || status == "failed")) plan.tasks.add(task)
        }

        state.initialized = true
        return plan
    }

    // --- keep-synced playlists ------------------------------------------------------------

    /**
     * New tracks went into the library (and into the Hub's target playlist): let Tempus fetch the
     * playlists marked «keep synced» and download what is missing. Tempus queues the downloads
     * through its DownloadService, which Android only lets us start while the app is in the
     * foreground or playing; otherwise the sync waits for the next app start.
     */
    @JvmStatic
    fun requestKeptSync(context: Context) {
        if (!canStartServices()) {
            prefs(context).edit().putBoolean(KEY_PENDING_SYNC, true).apply()
            return
        }
        prefs(context).edit().putBoolean(KEY_PENDING_SYNC, false).apply()
        HomeMusic.main.post { runKeptSync(context.applicationContext) }
    }

    /** Runs a sync that was deferred while the app was in the background. */
    @JvmStatic
    fun consumePendingSync(context: Context) {
        if (prefs(context).getBoolean(KEY_PENDING_SYNC, false)) requestKeptSync(context)
    }

    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    private fun runKeptSync(context: Context) {
        try {
            PlaylistRepository.resetKeptSync()
            PlaylistRepository().syncKeptPlaylists(context)
        } catch (e: Exception) {
            Log.w(TAG, "Keep-synced playlists sync failed", e)
        }
    }

    private fun canStartServices(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    // --- notifications ----------------------------------------------------------------------

    private fun notifyTask(context: Context, task: HubAssistantTask) {
        val title: String
        val text: String
        if (task.status == "failed") {
            title = context.getString(R.string.homemusic_notify_task_failed)
            text = task.error ?: context.getString(R.string.homemusic_notify_task_open)
        } else {
            title = context.getString(R.string.homemusic_notify_task_ready)
            text = context.getString(R.string.homemusic_notify_task_confirm)
        }
        post(context, ("t${task.id}").hashCode(), title, text, "/assistant/${task.id}")
    }

    private fun post(context: Context, id: Int, title: String, text: String, route: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(HomeMusic.EXTRA_HUB_ROUTE, route)
        val pending = PendingIntent.getActivity(
            context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification not allowed", e)
        }
    }

    // --- state ----------------------------------------------------------------------------

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): State {
        val json = prefs(context).getString(KEY_STATE, null) ?: return State()
        return try {
            HubClient.gson.fromJson(json, object : TypeToken<State>() {}.type) ?: State()
        } catch (e: Exception) {
            State()
        }
    }

    private fun save(context: Context, state: State) {
        prefs(context).edit().putString(KEY_STATE, HubClient.gson.toJson(state)).apply()
    }

    internal fun trim(state: State) {
        fun MutableList<Long>.cap() { if (size > MAX_REMEMBERED) subList(0, size - MAX_REMEMBERED).clear() }
        state.doneItems.cap()
        if (state.tasks.size > MAX_REMEMBERED) {
            val keep = state.tasks.keys.mapNotNull { it.toLongOrNull() }.sorted().takeLast(MAX_REMEMBERED).map { it.toString() }.toSet()
            state.tasks = state.tasks.filterKeys { it in keep }.toMutableMap()
        }
    }

    /** Forgets everything (logout / another account). */
    @JvmStatic
    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
