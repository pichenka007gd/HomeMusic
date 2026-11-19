package com.eddyizm.tempus.homemusic

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.eddyizm.tempus.App
import com.eddyizm.tempus.R
import com.eddyizm.tempus.util.FavoriteRegistry
import com.eddyizm.tempus.util.Preferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal fun <S, T> withVerifiedAccount(account: String, current: () -> String?, capture: () -> S,
                                        verify: (S) -> Boolean, use: (S) -> T): T {
    if (current() != account) throw IOException("Account changed")
    val snapshot = capture()
    if (!verify(snapshot) || current() != account) throw IOException("Account changed")
    return use(snapshot)
}

/** The stored account includes the configured Hub identity; no credentials or cookies are stored. */
internal data class CatalogCommand(val account: String, val user: String, val ref: String, val starred: Boolean,
    val before: Boolean, val eventId: String, val timestamp: String, val client: String, val context: String?)

/** FIFO; every mutation is persisted before it becomes visible to the sender. */
internal class CatalogCommandQueue(private val read: () -> String?, private val write: (String) -> Boolean) {
    private val gson = Gson()
    private val type = object : TypeToken<List<CatalogCommand>>() {}.type
    private var items = read()?.let { gson.fromJson<List<CatalogCommand>>(it, type) }.orEmpty()

    @Synchronized fun add(command: CatalogCommand) = save(items + command)
    @Synchronized fun head(account: String): CatalogCommand? = items.firstOrNull { it.account == account }
    @Synchronized fun pending(account: String): List<CatalogCommand> = items.filter { it.account == account }
    @Synchronized fun remove(command: CatalogCommand) = save(items.filterNot { it.account == command.account && it.eventId == command.eventId })
    private fun save(next: List<CatalogCommand>) {
        if (!write(gson.toJson(next))) throw IOException("Cannot save favorite commands")
        items = next
    }
}

internal class CatalogCommandSender(private val queue: CatalogCommandQueue, private val send: (CatalogCommand) -> Unit) {
    enum class Outcome { EMPTY, RETRY, ACCEPTED, REFUSED }
    data class Result(val outcome: Outcome, val command: CatalogCommand? = null, val error: HubException? = null)

    fun step(account: String): Result {
        val cmd = queue.head(account) ?: return Result(Outcome.EMPTY)
        return try {
            send(cmd)
            queue.remove(cmd)
            Result(Outcome.ACCEPTED, cmd)
        } catch (e: HubException) {
            if (e.code in 400..499 && e.code !in listOf(401, 403, 408, 429)) {
                try { queue.remove(cmd); Result(Outcome.REFUSED, cmd, e) }
                catch (_: IOException) { Result(Outcome.RETRY) }
            } else Result(Outcome.RETRY)
        } catch (_: Exception) { Result(Outcome.RETRY) }
    }
}

/** Catalog commands survive process death; unlike follows an uncertain like on the same account. */
internal object CatalogFavorites {
    private val prefs by lazy { App.getContext().getSharedPreferences("homemusic_catalog_commands", Context.MODE_PRIVATE) }
    private val queue by lazy { CatalogCommandQueue({ prefs.getString("queue", null) }, { prefs.edit().putString("queue", it).commit() }) }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val sender by lazy { CatalogCommandSender(queue) { cmd ->
        HubClient.withAccountSession(cmd.account) {
            HubClient.favoriteTrack(cmd.ref, cmd.starred, cmd.eventId, cmd.timestamp, cmd.client, cmd.context)
        }
    } }
    private val decisions = mutableMapOf<String, CatalogFavoriteDecision>()
    private var restoredAccount: String? = null
    private var restoredGeneration = -1L
    private var scheduled = false

    private fun identity(): Pair<String, String>? {
        val user = Preferences.getUser()?.takeIf { it.isNotBlank() } ?: return null
        val hub = HubConfig.remoteUrl().takeIf { it.isNotBlank() } ?: return null
        return (hub + "\n" + user) to user
    }
    fun currentAccount(): String? = identity()?.first

    @Synchronized private fun restore(account: String) {
        if (restoredAccount == account && restoredGeneration == FavoriteRegistry.generation()) return
        decisions.clear()
        for (cmd in queue.pending(account)) {
            decisions[cmd.eventId] = CatalogFavoriteDecision(HubTracks.ID_PREFIX + cmd.ref, cmd.before,
                cmd.starred, cmd.eventId, cmd.timestamp, restore = true)
        }
        restoredAccount = account
        restoredGeneration = FavoriteRegistry.generation()
    }

    @Synchronized fun submit(ref: String, before: Boolean, starred: Boolean): Boolean {
        val (account, user) = identity() ?: return false
        restore(account)
        val cmd = CatalogCommand(account, user, ref, starred, before, UUID.randomUUID().toString(),
            PlayEvent.iso(System.currentTimeMillis()), HubPlays.client, HubPlays.contextOf(HubTracks.ID_PREFIX + ref))
        try { queue.add(cmd) } catch (e: IOException) { Log.w("CatalogFavorites", "save", e); return false }
        decisions[cmd.eventId] = CatalogFavoriteDecision(HubTracks.ID_PREFIX + ref, before, starred,
            cmd.eventId, cmd.timestamp)
        schedule(0)
        return true
    }

    @Synchronized fun start() {
        val (account) = identity() ?: return
        restore(account)
        schedule(0)
    }

    @Synchronized private fun schedule(delay: Long) {
        if (scheduled) return
        scheduled = true
        worker.schedule({ synchronized(this) { scheduled = false }; drain() }, delay, TimeUnit.MILLISECONDS)
    }

    fun flushNow() { start() }

    private fun drain() {
        while (true) {
            val (account) = identity() ?: return
            val result = sender.step(account)
            val cmd = result.command
            when (result.outcome) {
                CatalogCommandSender.Outcome.EMPTY -> return
                CatalogCommandSender.Outcome.RETRY -> { schedule(30_000); return }
                CatalogCommandSender.Outcome.ACCEPTED -> {
                    checkNotNull(cmd)
                    HomeMusic.main.post {
                        if (identity()?.first == account) {
                            synchronized(this) { decisions.remove(cmd.eventId) }?.accepted()
                            val current = FavoriteRegistry.resolve(FavoriteRegistry.Kind.SONG, HubTracks.ID_PREFIX + cmd.ref, cmd.before)
                            if (cmd.starred && current) HubWebHolder.dispatchEvent(HubTracks.DOWNLOAD_EVENT, mapOf("ref" to cmd.ref, "star" to true))
                            HubWebHolder.dispatchEvent("hm:hub-favorite", mapOf("ref" to cmd.ref, "star" to current))
                        }
                    }
                }
                CatalogCommandSender.Outcome.REFUSED -> {
                    checkNotNull(cmd)
                    HomeMusic.main.post {
                        if (identity()?.first == account) {
                            val current = synchronized(this) { decisions.remove(cmd.eventId) }?.failed() ?: cmd.before
                            HubWebHolder.dispatchEvent("hm:hub-favorite", mapOf("ref" to cmd.ref, "star" to current))
                            val app = App.getContext()
                            Toast.makeText(app, app.getString(R.string.homemusic_hub_action_failed, result.error?.message ?: ""), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
    }
}
