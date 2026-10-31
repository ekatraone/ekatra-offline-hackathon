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
    fun `no whole-request error other than 401 ever deletes events`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", true)
        for (code in listOf(400, 403, 404, 408, 409, 413, 422, 429, 500, 503)) {
            api.eventsResult = {
                if (code == 429 || code >= 500) ApiResult.Retryable("HTTP $code") else ApiResult.Rejected(code, "HTTP $code")
            }
            repeat(2) { manager.sync() }
            assertEquals("after HTTP $code", 1, dao.items.size)
        }
        // Far past the old five-retry give-up point, and the row is still there.
        assertTrue(dao.items.single().retryCount > LearnerSyncManager.MAX_RETRIES)
    }

    @Test
    fun `lesson requests are dropped only on 400 or 422 and the error is shown`() = runTest {
        for (code in listOf(400, 422)) {
            manager.enqueueLessonRequest("topic $code", "en")
            api.lessonResult = { _, _, _ -> ApiResult.Rejected(code, "bad topic $code") }

            val report = manager.sync()

            assertEquals("HTTP $code", listOf("bad topic $code"), report.requestErrors)
            assertTrue("HTTP $code should be dropped", dao.items.isEmpty())
        }
    }

    @Test
    fun `lesson requests are retried on 404, 408, 409, 413, 429 and 5xx`() = runTest {
        for (code in listOf(404, 408, 409, 413, 429, 500, 502, 503, 504)) {
            dao.deleteAll()
            manager.enqueueLessonRequest("fractions", "en")
            api.lessonResult = { _, _, _ ->
                if (code == 429 || code >= 500) ApiResult.Retryable("HTTP $code") else ApiResult.Rejected(code, "HTTP $code")
            }

            val report = manager.sync()

            assertEquals("HTTP $code should stay queued", 1, dao.items.size)
            assertEquals("HTTP $code should count a retry", 1, dao.items.single().retryCount)
            assertTrue("HTTP $code is not shown as a learner error", report.requestErrors.isEmpty())
        }
    }

    @Test
    fun `a 409 in_progress keeps the same request id for the next try`() = runTest {
        val id = manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(409, "in_progress") }
        manager.sync()

        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-1", sampleBundle())) }
        manager.sync()

        assertEquals(listOf(id, id), api.lessonCalls.map { it.third })
        assertTrue(dao.items.isEmpty())
        assertNotNull(bundles.get("course-1"))
    }

    @Test
    fun `one failing lesson request does not block the ones behind it`() = runTest {
        manager.enqueueLessonRequest("stuck", "en")
        manager.enqueueLessonRequest("fine", "en")
        api.lessonResult = { topic, _, _ ->
            if (topic == "stuck") ApiResult.Retryable("HTTP 503")
            else ApiResult.Ok(RequestLessonResult("course-1", sampleBundle()))
        }

        val report = manager.sync()

        assertEquals(listOf("stuck", "fine"), api.lessonCalls.map { it.first })
        assertEquals(1, report.lessonsDownloaded)
        assertEquals(listOf("stuck"), manager.pendingRequestTopics())
    }

    @Test
    fun `after five retries a stuck request moves to the back of the queue`() = runTest {
        val stuck = manager.enqueueLessonRequest("stuck", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Retryable("HTTP 503") }

        repeat(LearnerSyncManager.MAX_RETRIES - 1) { manager.sync() }
        manager.enqueueLessonRequest("waiting", "en") // arrives later, so it has fewer retries
        assertEquals(listOf("stuck", "waiting"), manager.pendingRequestTopics())
        assertEquals(LearnerSyncManager.MAX_RETRIES - 1, dao.items.first().retryCount)

        manager.sync() // stuck's fifth failure, waiting's first

        assertEquals(listOf("waiting", "stuck"), manager.pendingRequestTopics())
        val moved = dao.items.last()
        assertTrue("same clientRequestId, so the server stays idempotent", moved.payload.contains(stuck))
        assertEquals(0, moved.retryCount)
        assertEquals(1, dao.items.first().retryCount)
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
        val gen = Generator("meta", "muse-spark-1.3")
        api.meResult = {
            ApiResult.Ok(MeResponse(
                courses = listOf(MeCourse("whatsapp-course", generator = gen), MeCourse("authored-course", generator = gen)),
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

    // ---- /me bundle pull ----

    @Test
    fun `courses with no generator are never fetched`() = runTest {
        val fetched = mutableListOf<String>()
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("authored", generator = null)))) }
        api.bundleResult = { fetched += it; ApiResult.Ok(sampleBundle(courseId = it)) }

        manager.sync()

        assertTrue(fetched.isEmpty())
    }

    @Test
    fun `a course that returned 404 or 422 is not asked for again`() = runTest {
        val gen = Generator("meta", "muse-spark-1.3")
        val fetched = mutableListOf<String>()
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("gone", generator = gen), MeCourse("authored", generator = gen)))) }
        api.bundleResult = { id ->
            fetched += id
            ApiResult.Rejected(if (id == "gone") 404 else 422, "no")
        }

        manager.sync()
        manager.sync()
        manager.sync()

        assertEquals(listOf("gone", "authored"), fetched)
        // ...and it is remembered across a restart.
        assertEquals(setOf("gone", "authored"), LessonProgressStore(File(dir, "progress.json")).data.unavailableCourseIds)
    }

    @Test
    fun `a course that failed with a network error is tried again`() = runTest {
        val gen = Generator("meta", "muse-spark-1.3")
        var calls = 0
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("flaky", generator = gen)))) }
        api.bundleResult = { calls++; ApiResult.Retryable("reset") }

        manager.sync(); manager.sync()

        assertEquals(2, calls)
    }

    // ---- event ids ----

    @Test
    fun `answering the same question again reuses one clientEventId`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        manager.enqueueQuizAnswer("c", "l", "q1", "B", true)
        manager.enqueueQuizAnswer("c", "l", "q2", "A", true)

        val ids = dao.items.map { com.google.gson.Gson().fromJson(it.payload, LearnerEvent::class.java).clientEventId }
        assertEquals(ids[0], ids[1])
        assertTrue(ids[0] != ids[2])
    }
}
