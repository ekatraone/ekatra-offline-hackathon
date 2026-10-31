package org.ekatra.alfred.lessons

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LearnerSyncManagerTest {

    private lateinit var dir: File
    private lateinit var dao: FakeQueueDao
    private lateinit var api: FakeApi
    private lateinit var net: FakeConnectivity
    private lateinit var bundles: LessonStore
    private lateinit var progress: LessonProgressStore
    private lateinit var manager: LearnerSyncManager

    @Before
    fun setup() {
        dir = Files.createTempDirectory("lesson-sync").toFile()
        dao = FakeQueueDao()
        api = FakeApi()
        net = FakeConnectivity(true)
        bundles = LessonStore(File(dir, "lessons"))
        progress = LessonProgressStore(File(dir, "progress.json"))
        manager = newManager(SyncConfig("http://x", "tok"))
    }

    @After
    fun cleanup() { dir.deleteRecursively() }

    private fun newManager(config: SyncConfig) =
        LearnerSyncManager(api, dao, bundles, progress, config, net)

    @Test
    fun `accepted, duplicate and rejected events are all deleted from the queue`() = runTest {
        repeat(3) { manager.enqueueQuizAnswer("c", "l", "q$it", "B", true) }
        val ids = dao.items.map { it.payload }
        api.eventsResult = { evs ->
            ApiResult.Ok(EventsResponse(
                accepted = listOf(evs[0].clientEventId),
                duplicates = listOf(evs[1].clientEventId),
                rejected = listOf(RejectedEvent(evs[2].clientEventId, "unknown question"))
            ))
        }

        val report = manager.sync()

        assertEquals(SyncStatus.SYNCED, report.status)
        assertEquals(3, report.eventsResolved)
        assertTrue("queue should be empty but has ${ids.size}", dao.items.isEmpty())
    }

    @Test
    fun `a network error keeps events and increments retry`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        api.eventsResult = { ApiResult.Retryable("timeout") }

        val report = manager.sync()

        assertEquals(1, dao.items.size)
        assertEquals(1, dao.items.single().retryCount)
        assertEquals(SyncStatus.ERROR, report.status)
    }

    @Test
    fun `401 stops the run without touching retry counts or deleting`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        api.lessonResult = { _, _, _ -> ApiResult.Unauthorized("expired") }

        val report = manager.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertEquals(2, dao.items.size)
        assertTrue(dao.items.all { it.retryCount == 0 })
        assertTrue("events must not be posted after a 401", api.eventBatches.isEmpty())
        assertEquals(SyncStatus.UNAUTHORIZED, manager.status.value)
    }

    @Test
    fun `a queued lesson request becomes a stored bundle and is deleted`() = runTest {
        val id = manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-1", sampleBundle())) }

        val report = manager.sync()

        assertEquals(1, report.lessonsDownloaded)
        assertEquals(id, api.lessonCalls.single().third)
        assertNotNull(bundles.get("course-1"))
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `lesson requests are sent before events`() = runTest {
        manager.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
        manager.enqueueLessonRequest("fractions", "en")
        val order = mutableListOf<String>()
        api.lessonResult = { _, _, _ -> order += "lesson"; ApiResult.Ok(RequestLessonResult("course-1", sampleBundle())) }
        api.eventsResult = { evs -> order += "events"; ApiResult.Ok(EventsResponse(accepted = evs.map { it.clientEventId })) }

        manager.sync()

        assertEquals(listOf("lesson", "events"), order)
    }

    @Test
    fun `offline does nothing at all`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        net.online = false

        val report = manager.sync()

        assertEquals(SyncStatus.OFFLINE, report.status)
        assertTrue(api.lessonCalls.isEmpty() && api.eventBatches.isEmpty() && api.meCalls == 0)
        assertEquals(2, dao.items.size)
    }

    @Test
    fun `not configured does nothing`() = runTest {
        val unconfigured = newManager(SyncConfig("", ""))
        unconfigured.enqueueQuizAnswer("c", "l", "q1", "A", false)

        assertEquals(SyncStatus.NOT_CONFIGURED, unconfigured.sync().status)
        assertEquals(1, dao.items.size)
        assertTrue(api.eventBatches.isEmpty())
    }

    @Test
    fun `events go out in batches of 20`() = runTest {
        repeat(45) { manager.enqueueQuizAnswer("c", "l", "q$it", "A", true) }

        manager.sync()

        assertEquals(listOf(20, 20, 5), api.eventBatches.map { it.size })
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a batch the server names nothing in is kept and does not loop forever`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", true)
        api.eventsResult = { ApiResult.Ok(EventsResponse()) }

        manager.sync()

        assertEquals(1, dao.items.size)
        assertEquals(1, api.eventBatches.size)
    }

    @Test
    fun `a rejected lesson request is dropped and its error is surfaced`() = runTest {
        manager.enqueueLessonRequest("x", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "topic too short") }

        val report = manager.sync()

        assertEquals(1, report.requestsDropped)
        assertEquals(listOf("topic too short"), report.requestErrors)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a whole-request 4xx keeps the events and bumps retry`() = runTest {
        repeat(3) { manager.enqueueQuizAnswer("c", "l", "q$it", "A", true) }
        for (code in listOf(400, 404, 422)) {
            api.eventsResult = { ApiResult.Rejected(code, "bad request") }

            val report = manager.sync()

            assertEquals("after HTTP $code", 3, dao.items.size)
            assertEquals(SyncStatus.ERROR, report.status)
            assertEquals(0, report.requestsDropped)
        }
        assertTrue(dao.items.all { it.retryCount == 3 })
    }

    @Test
    fun `a whole-request 4xx does not loop on the same batch within one pass`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", true)
        api.eventsResult = { ApiResult.Rejected(422, "unsupported") }

        manager.sync()

        assertEquals(1, api.eventBatches.size)
    }

    @Test
    fun `events are dropped only after the maximum retries`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", true)
        api.eventsResult = { ApiResult.Rejected(400, "bad") }

        repeat(LearnerSyncManager.MAX_RETRIES) { manager.sync() }
        assertEquals(1, dao.items.size)
        assertEquals(LearnerSyncManager.MAX_RETRIES, dao.items.single().retryCount)

        val report = manager.sync()
        assertEquals(1, report.requestsDropped)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `a failed lesson request does not stop events from syncing`() = runTest {
        manager.enqueueLessonRequest("x", "en")
        manager.enqueueQuizAnswer("c", "l", "q1", "A", true)
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(400, "topic too short") }

        manager.sync()

        assertEquals(1, api.eventBatches.size)
        assertTrue(dao.items.isEmpty())
    }

    @Test
    fun `corrupt payloads are discarded`() = runTest {
        dao.insert(org.ekatra.alfred.data.model.SyncQueueItem(type = "learner_event", payload = "not json"))

        val report = manager.sync()

        assertEquals(1, report.requestsDropped)
        assertTrue(dao.items.isEmpty())
        assertTrue(api.eventBatches.isEmpty())
    }

    @Test
    fun `sync caches the server profile and fetches courses made elsewhere`() = runTest {
        api.meResult = {
            ApiResult.Ok(MeResponse(
                courses = listOf(MeCourse("whatsapp-course"), MeCourse("authored-course")),
                profile = ServerProfile(mastery = mapOf("lesson-1" to 0.5)),
                lessonTitles = mapOf("lesson-1" to "Halves")
            ))
        }
        api.bundleResult = { id ->
            if (id == "whatsapp-course") ApiResult.Ok(sampleBundle(courseId = id)) else ApiResult.Rejected(422, "authored")
        }

        manager.sync()

        assertEquals(0.5, progress.data.profile!!.mastery["lesson-1"]!!, 0.0)
        assertNotNull(bundles.get("whatsapp-course"))
        assertNull(bundles.get("authored-course"))
    }

    @Test
    fun `pending count covers learner events and lesson requests only`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q", "A", true)
        manager.enqueueLessonRequest("t", "en")
        dao.insert(org.ekatra.alfred.data.model.SyncQueueItem(type = "chat_analytics", payload = "{}"))

        assertEquals(2, manager.pendingCount.first())
    }

    @Test
    fun `events carry a UUID id and ISO timestamp`() = runTest {
        manager.enqueueLessonCompleted("c", "l")
        val ev = com.google.gson.Gson().fromJson(dao.items.single().payload, LearnerEvent::class.java)

        java.util.UUID.fromString(ev.clientEventId)
        assertEquals("lesson_completed", ev.type)
        assertTrue(ev.occurredAt.matches(Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z""")))
    }
}
