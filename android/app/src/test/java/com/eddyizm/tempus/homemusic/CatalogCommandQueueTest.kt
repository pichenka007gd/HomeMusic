package com.eddyizm.tempus.homemusic

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CatalogCommandQueueTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun command(account: String, id: String, starred: Boolean) = CatalogCommand(
        account, account, "dz:1", starred, !starred, id, "2026-10-27T12:00:00Z", "app/test", "search")

    @Test fun offlineRestartPreservesOrderIdentityAndAccountIsolation() {
        var persisted: String? = null
        fun queue() = CatalogCommandQueue({ persisted }, { persisted = it; true })
        val first = queue()
        first.add(command("a", "like", true))
        first.add(command("a", "unlike", false))
        first.add(command("b", "other", true))
        val afterRestart = queue()
        assertEquals("like", afterRestart.head("a")?.eventId)
        assertEquals("other", afterRestart.head("b")?.eventId)
        // An uncertain attempt is not removed and is the next attempt even after another restart.
        assertEquals("like", queue().head("a")?.eventId)
        afterRestart.remove(afterRestart.head("a")!!)
        val afterAck = queue()
        assertEquals("unlike", afterAck.head("a")?.eventId)
        assertEquals(false, afterAck.head("a")?.starred)
        assertEquals("other", afterAck.head("b")?.eventId)
    }

    @Test fun failedPersistenceDoesNotExposeACommand() {
        val queue = CatalogCommandQueue({ null }, { false })
        try { queue.add(command("a", "like", true)); fail("expected failure") }
        catch (_: java.io.IOException) { assertNull(queue.head("a")) }
    }

    @Test fun uncertainSendSurvivesRestartAndBlocksLaterUnlikeUntilAcknowledged() {
        var persisted: String? = null
        fun queue() = CatalogCommandQueue({ persisted }, { persisted = it; true })
        queue().also { it.add(command("a", "like", true)); it.add(command("a", "unlike", false)) }
        val attempts = mutableListOf<String>()
        var online = false
        fun sender(q: CatalogCommandQueue) = CatalogCommandSender(q) {
            attempts += it.eventId
            if (!online) throw java.io.IOException("response lost")
        }
        repeat(3) { assertEquals(CatalogCommandSender.Outcome.RETRY, sender(queue()).step("a").outcome) }
        assertEquals(listOf("like", "like", "like"), attempts)
        online = true
        val restored = sender(queue())
        assertEquals(CatalogCommandSender.Outcome.ACCEPTED, restored.step("a").outcome)
        assertEquals(CatalogCommandSender.Outcome.ACCEPTED, restored.step("a").outcome)
        assertEquals(listOf("like", "like", "like", "like", "unlike"), attempts)
        assertEquals(CatalogCommandSender.Outcome.EMPTY, restored.step("a").outcome)
    }

    @Test fun catalogTelemetryWaitsForItsAccountAndNeverIncludesLocalAccountInPayload() {
        val store = PlayEventStore(tmp.root.resolve("events.json"))
        val event = PlayEvent.favorite("track", "hub:dz:1", true, "id", null, "app/test", 1, "search", account = "server\na")
        store.add(event)
        val sent = mutableListOf<PlayEvent>()
        var account = "server\nb"
        val sender = PlaySender(store, { sent.addAll(it); PlaySender.Outcome.SENT }, account = { account })
        assertEquals(PlaySender.CAPABILITY_RETRY_MS, sender.flush())
        assertTrue(sent.isEmpty())
        account = "server\na"
        assertNull(sender.flush())
        assertEquals("id", sent.single().eventId)
        assertFalse(HubClient.eventPayload(sent).single().has("_account"))
    }

    @Test fun accountSwitchAfterValidationStillUsesTheVerifiedCookieSnapshot() {
        val account = AtomicReference("a")
        val cookie = AtomicReference("cookie-a")
        val verified = CountDownLatch(1)
        val switched = CountDownLatch(1)
        val used = AtomicReference<String>()
        val failure = AtomicReference<Throwable>()
        val thread = Thread {
            try {
                withVerifiedAccount("a", account::get, cookie::get, { it == "cookie-a" }) { snapshot ->
                    verified.countDown()
                    check(switched.await(1, TimeUnit.SECONDS))
                    used.set(snapshot)
                }
            } catch (e: Throwable) { failure.set(e) }
        }
        thread.start()
        assertTrue(verified.await(1, TimeUnit.SECONDS))
        account.set("b")
        cookie.set("cookie-b")
        switched.countDown()
        thread.join(1000)
        assertFalse(thread.isAlive)
        assertNull(failure.get())
        assertEquals("cookie-a", used.get())
    }
}
