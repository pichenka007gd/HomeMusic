package com.eddyizm.tempus.homemusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HubNotifierTest {
    private fun item(id: Long, status: String, star: Boolean = false) = HubDownloadItem().apply {
        this.id = id
        this.status = status
        this.star = star
    }

    /** A state after the first check, which only remembers what is there. */
    private fun checkedState() = HubNotifier.State().also { HubNotifier.plan(it, emptyList(), emptyList()) }

    @Test
    fun finishedServerDownloadSyncsWithoutNotifying() {
        val plan = HubNotifier.plan(checkedState(), listOf(item(1, "done")), emptyList())
        assertTrue(plan.tasks.isEmpty())
        assertTrue(plan.newlyDone)
    }

    @Test
    fun likedDownloadsAreSilent() {
        val state = checkedState()
        val plan = HubNotifier.plan(state, listOf(item(1, "done", star = true)), emptyList())
        assertFalse(plan.newlyDone)
    }

    @Test
    fun downloadsThatNeedAttentionStayInTheQueue() {
        val items = listOf(item(1, "needs_review"), item(2, "failed"), item(3, "downloading"))
        val plan = HubNotifier.plan(checkedState(), items, emptyList())
        assertTrue(plan.tasks.isEmpty())
        assertFalse(plan.newlyDone)
    }

    @Test
    fun eachCompletedItemTriggersSyncOnce() {
        val state = checkedState()
        val items = listOf(item(1, "done"))
        assertTrue(HubNotifier.plan(state, items, emptyList()).newlyDone)
        val again = HubNotifier.plan(state, items, emptyList())
        assertFalse(again.newlyDone)
    }

    @Test
    fun firstCheckOnlyRemembers() {
        val plan = HubNotifier.plan(HubNotifier.State(), listOf(item(1, "done")), emptyList())
        assertFalse(plan.newlyDone)
    }

    @Test
    fun assistantStillNotifiesOncePerActionableStatus() {
        val state = checkedState()
        val task = HubAssistantTask().apply {
            id = 1
            status = "awaiting_confirmation"
        }
        assertEquals(listOf(task), HubNotifier.plan(state, emptyList(), listOf(task)).tasks)
        assertTrue(HubNotifier.plan(state, emptyList(), listOf(task)).tasks.isEmpty())
        task.status = "failed"
        assertEquals(listOf(task), HubNotifier.plan(state, emptyList(), listOf(task)).tasks)
    }

    @Test
    fun starIsReadFromTheHubAndMissingMeansFalse() {
        val items = HubClient.gson.fromJson(
            """[{"id": 1, "batch_id": 5, "status": "done", "star": true}, {"id": 2, "batch_id": 6, "status": "done"}]""",
            Array<HubDownloadItem>::class.java,
        )
        assertTrue(items[0].star)
        assertFalse(items[1].star)
    }
}
