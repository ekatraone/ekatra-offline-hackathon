package org.ekatra.alfred.lessons

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `401 on events stops the run without touching retry counts or deleting`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        api.eventsResult = { ApiResult.Unauthorized("expired") }

        val report = manager.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertEquals(2, dao.items.size)
        assertTrue(dao.items.all { it.retryCount == 0 })
        assertTrue("nothing after the failed step runs", api.lessonCalls.isEmpty() && api.meCalls == 0)
        assertEquals(SyncStatus.UNAUTHORIZED, manager.status.value)
    }

    @Test
    fun `401 on a lesson request keeps the request without bumping retries, after events already synced`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        manager.enqueueQuizAnswer("c", "l", "q1", "A", false)
        api.lessonResult = { _, _, _ -> ApiResult.Unauthorized("expired") }

        val report = manager.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertEquals("the event went out first", 1, api.eventBatches.size)
        val left = dao.items.single()
        assertEquals("lesson_request", left.type)
        assertEquals(0, left.retryCount)
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
    fun `events are posted before lesson requests`() = runTest {
        manager.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
        manager.enqueueLessonRequest("fractions", "en")
        val order = mutableListOf<String>()
        api.lessonResult = { _, _, _ -> order += "lesson"; ApiResult.Ok(RequestLessonResult("course-1", sampleBundle())) }
        api.eventsResult = { evs -> order += "events"; ApiResult.Ok(EventsResponse(accepted = evs.map { it.clientEventId })) }
        api.meResult = { order += "me"; ApiResult.Ok(MeResponse()) }

        manager.sync()

        assertEquals(listOf("events", "lesson", "me"), order)
    }

    @Test
    fun `finishing a lesson syncs within seconds even while a topic is still being generated`() = runTest {
        manager.ioDispatcher = Dispatchers.Unconfined
        manager.enqueueLessonRequest("slow topic", "en")
        val gate = CompletableDeferred<Unit>()
        api.lessonGate = gate
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-1", sampleBundle())) }

        // An earlier sync is now stuck in generation (Muse takes 60-240 s).
        val generating = launch(start = CoroutineStart.UNDISPATCHED) { manager.sync() }
        assertTrue(generating.isActive)

        // The learner finishes a lesson and reconnects: a second sync.
        manager.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
        manager.enqueueLessonCompleted("course-1", "lesson-1")
        val second = launch(start = CoroutineStart.UNDISPATCHED) { manager.sync() }

        // Events are already on the server and the chip would say Synced, with generation still running.
        assertEquals(1, api.eventBatches.size)
        assertEquals(2, api.eventBatches.single().size)
        assertTrue(dao.items.none { it.type == "learner_event" })
        assertEquals(0, manager.pendingEventCount.first())
        assertEquals(SyncStatus.SYNCED, manager.status.value)
        assertTrue("generation has not finished", generating.isActive && second.isActive)
        assertEquals(1, api.lessonCalls.size)

        gate.complete(Unit)
        generating.join(); second.join()
        assertNotNull(bundles.get("course-1"))
    }

    @Test
    fun `the updates-waiting count is events only, not lesson requests`() = runTest {
        manager.enqueueQuizAnswer("c", "l", "q", "A", true)
        manager.enqueueLessonRequest("t", "en")
        assertEquals(1, manager.pendingEventCount.first())
        assertEquals(2, manager.pendingCount.first())
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
        val failed = progress.data.failedRequests.single()
        assertEquals("x", failed.topic)
        assertEquals("topic too short", failed.reason)
    }

    @Test
    fun `retrying a failed request uses a fresh request id`() = runTest {
        val oldId = manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(422, "generation_failed") }
        manager.sync()
        assertEquals(oldId, progress.data.failedRequests.single().clientRequestId)

        assertTrue(manager.retryFailedRequest(oldId))

        val queued = dao.items.single { it.type == "lesson_request" }
        assertTrue(!queued.payload.contains(oldId))
        assertTrue(queued.payload.contains("fractions"))
        assertTrue(progress.data.failedRequests.isEmpty())
        assertTrue(!manager.retryFailedRequest(oldId))
    }

    @Test
    fun `a retryable failure is never recorded as a failure`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Rejected(409, "in_progress") }
        manager.sync()
        assertTrue(progress.data.failedRequests.isEmpty())
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
    fun `courses with no generator are fetched too, such as WhatsApp-made ones`() = runTest {
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("whatsapp-made", generator = null)))) }
        api.bundleResult = { ApiResult.Ok(sampleBundle(courseId = it)) }

        manager.sync()

        assertNotNull(bundles.get("whatsapp-made"))
    }

    @Test
    fun `a 404 that did not come from the API is retried, not remembered as gone`() = runTest {
        // The client turns a bare HTML 404 (a proxy during a redeploy) into Retryable.
        var calls = 0
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("c1")))) }
        api.bundleResult = { calls++; ApiResult.Retryable("HTTP 404 (not an API response)") }

        manager.sync(); manager.sync()

        assertEquals(2, calls)
        assertTrue(progress.data.unavailableCourseIds.isEmpty())
    }

    @Test
    fun `a course that returned 404 or 422 is not asked for again`() = runTest {
        val fetched = mutableListOf<String>()
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("gone"), MeCourse("authored")))) }
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

    // ---- images ----

    private fun imageFiles(courseId: String = "course-1") =
        File(dir, "lessons/$courseId").listFiles()?.map { it.name }?.sorted().orEmpty()

    @Test
    fun `a requested lesson's cover and module images are downloaded into the course folder`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-1", sampleBundleWithImages())) }

        val report = manager.sync()

        assertEquals(2, report.imagesDownloaded)
        assertEquals(listOf("cover.jpg", "lesson-1.jpg"), imageFiles())
        assertEquals(JPEG_BYTES.toList(), bundles.coverFile("course-1").readBytes().toList())
        assertEquals(listOf(COVER_URL, LESSON_IMAGE_URL), api.imageCalls)
        assertTrue("no temp files left behind", imageFiles().none { it.endsWith(".tmp") })
    }

    @Test
    fun `review lessons never get an image`() = runTest {
        bundles.save(sampleBundleWithImages())
        manager.sync()
        assertTrue(api.imageCalls.none { it.endsWith("/never") })
        assertTrue(imageFiles().none { it.startsWith("review") })
    }

    @Test
    fun `a failing image does not fail or block the lesson`() = runTest {
        manager.enqueueLessonRequest("fractions", "en")
        api.lessonResult = { _, _, _ -> ApiResult.Ok(RequestLessonResult("course-1", sampleBundleWithImages())) }
        api.imageResult = { ApiResult.Retryable("HTTP 503") }

        val report = manager.sync()

        assertEquals(SyncStatus.SYNCED, report.status)
        assertEquals(1, report.lessonsDownloaded)
        assertEquals(0, report.imagesDownloaded)
        assertNotNull("the lesson is usable without images", bundles.get("course-1"))
        assertTrue(dao.items.isEmpty())
        assertTrue(imageFiles().isEmpty())
    }

    @Test
    fun `one bad image does not stop the others`() = runTest {
        bundles.save(sampleBundleWithImages())
        api.imageResult = { url -> if (url == COVER_URL) ApiResult.Rejected(404, "gone") else ApiResult.Ok(JPEG_BYTES) }

        val report = manager.sync()

        assertEquals(1, report.imagesDownloaded)
        assertEquals(listOf("lesson-1.jpg"), imageFiles())
    }

    @Test
    fun `missing images are retried on a later pass and present ones are not fetched again`() = runTest {
        bundles.save(sampleBundleWithImages())
        api.imageResult = { ApiResult.Retryable("offline-ish") }
        manager.sync()
        assertTrue(imageFiles().isEmpty())

        api.imageResult = { ApiResult.Ok(JPEG_BYTES) }
        api.imageCalls.clear()
        val second = manager.sync()

        assertEquals(2, second.imagesDownloaded)
        assertEquals(listOf("cover.jpg", "lesson-1.jpg"), imageFiles())

        api.imageCalls.clear()
        manager.sync()
        assertTrue("already downloaded", api.imageCalls.isEmpty())
    }

    @Test
    fun `an image the server says does not exist is not asked for again`() = runTest {
        bundles.save(sampleBundleWithImages())
        api.imageResult = { ApiResult.Rejected(404, "gone") }
        manager.sync()
        val firstRound = api.imageCalls.size
        manager.sync(); manager.sync()
        assertEquals(2, firstRound)
        assertEquals("404s are remembered", firstRound, api.imageCalls.size)
    }

    @Test
    fun `the image step gives up after three failures in a row`() = runTest {
        val many = sampleBundle().let { b ->
            b.copy(
                course = b.course.copy(coverImageUrl = COVER_URL),
                lessons = (1..6).map { i -> b.lessons.single().copy(id = "l$i", order = i, imageUrl = "https://demo.example/m/$i") }
            )
        }
        bundles.save(many)
        api.imageResult = { ApiResult.Retryable("timeout") }

        manager.sync()

        assertEquals(3, api.imageCalls.size)
    }

    @Test
    fun `an old bundle without image fields works and triggers no downloads`() = runTest {
        bundles.save(sampleBundle())

        val report = manager.sync()

        assertEquals(0, report.imagesDownloaded)
        assertTrue(api.imageCalls.isEmpty())
        assertEquals(SyncStatus.SYNCED, report.status)
    }

    @Test
    fun `a bundle pulled from me gets its images too`() = runTest {
        api.meResult = { ApiResult.Ok(MeResponse(courses = listOf(MeCourse("wa-course")))) }
        api.bundleResult = { ApiResult.Ok(sampleBundleWithImages(courseId = it, lessonId = "wl")) }

        manager.sync()

        assertEquals(listOf("cover.jpg", "wl.jpg"), imageFiles("wa-course"))
    }

    @Test
    fun `an image that fails to save is a failure, not a crash`() = runTest {
        bundles.save(sampleBundleWithImages())
        // A file where the course's image folder should be makes the write impossible.
        File(dir, "lessons/course-1").writeText("not a directory")

        val report = manager.sync()

        assertEquals(0, report.imagesDownloaded)
        assertEquals(SyncStatus.SYNCED, report.status)
    }

    @Test
    fun `a 401 on events stops before lessons and images`() = runTest {
        bundles.save(sampleBundleWithImages())
        manager.enqueueQuizAnswer("c", "l", "q", "A", true)
        manager.enqueueLessonRequest("t", "en")
        api.eventsResult = { ApiResult.Unauthorized("expired") }

        val report = manager.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertTrue(api.lessonCalls.isEmpty() && api.imageCalls.isEmpty() && api.meCalls == 0)
        assertEquals(2, dao.items.size)
    }

    // ---- bundleVersion refetch ----

    private fun meWith(vararg c: MeCourse) { api.meResult = { ApiResult.Ok(MeResponse(courses = c.toList())) } }

    @Test
    fun `a stored bundle is refetched when the server's bundleVersion moved on, and its late images download`() = runTest {
        // Stored without images, version v1 (what the phone got when images missed the server's budget).
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = "v1"))
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        api.bundleResult = { ApiResult.Ok(sampleBundleWithImages(it, "lesson-1").copy(version = "v2")) }

        val report = manager.sync()

        assertEquals("v2", bundles.get("course-1")?.version)
        assertEquals(1, report.lessonsDownloaded)
        assertEquals(2, report.imagesDownloaded)
        assertEquals(listOf("cover.jpg", "lesson-1.jpg"), File(dir, "lessons/course-1").listFiles()!!.map { it.name }.sorted())
    }

    @Test
    fun `an up-to-date bundle is not refetched`() = runTest {
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = "v2"))
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        var fetches = 0
        api.bundleResult = { fetches++; ApiResult.Ok(sampleBundle(it)) }

        manager.sync(); manager.sync()

        assertEquals(0, fetches)
    }

    @Test
    fun `a server that never converges is refetched once per version, not every pass`() = runTest {
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = "v1"))
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        var fetches = 0
        // The server keeps handing back v1 although /me says v2.
        api.bundleResult = { fetches++; ApiResult.Ok(sampleBundle(it).copy(version = "v1")) }

        manager.sync(); manager.sync(); manager.sync()

        assertEquals(1, fetches)

        // A newer version later is fetched again.
        meWith(MeCourse("course-1", bundleVersion = "v3"))
        manager.sync()
        assertEquals(2, fetches)
    }

    @Test
    fun `a failed refetch keeps the old bundle and tries again next pass`() = runTest {
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = "v1"))
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        var fetches = 0
        api.bundleResult = { fetches++; ApiResult.Retryable("HTTP 503") }

        manager.sync(); manager.sync()

        assertEquals("v1", bundles.get("course-1")?.version)
        assertEquals(2, fetches)
    }

    @Test
    fun `no bundleVersion from the server means no refetch of a stored bundle`() = runTest {
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = "v1"))
        meWith(MeCourse("course-1", bundleVersion = null))
        var fetches = 0
        api.bundleResult = { fetches++; ApiResult.Ok(sampleBundle(it)) }

        manager.sync()

        assertEquals(0, fetches)
    }

    @Test
    fun `an old stored bundle with no version is refetched once the server reports one`() = runTest {
        bundles.save(sampleBundle("course-1", "lesson-1").copy(version = null))
        meWith(MeCourse("course-1", bundleVersion = "v9"))
        api.bundleResult = { ApiResult.Ok(sampleBundle(it).copy(version = "v9")) }

        manager.sync()

        assertEquals("v9", bundles.get("course-1")?.version)
    }

    @Test
    fun `when the server swaps an image id the stale local file is dropped and the new one fetched`() = runTest {
        val old = sampleBundleWithImages("course-1", "lesson-1").copy(version = "v1")
        bundles.save(old)
        manager.sync() // downloads cover + module image
        api.imageCalls.clear()
        val newCover = "https://demo.example/api/sync/media/cover-NEW"
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        api.bundleResult = { ApiResult.Ok(old.copy(version = "v2", course = old.course.copy(coverImageUrl = newCover))) }

        manager.sync()

        assertEquals("only the changed slot is fetched", listOf(newCover), api.imageCalls)
        assertTrue(bundles.coverFile("course-1").exists())
    }

    // ---- placeholder only while a download is pending ----

    @Test
    fun `an image is pending only while its download is attempted, then never after`() = runTest {
        bundles.save(sampleBundleWithImages())
        val cover = bundles.coverFile("course-1").path
        val module = bundles.lessonImageFile("course-1", "lesson-1").path
        val seen = mutableListOf<Set<String>>()
        api.imageResult = { seen += bundles.imagesPending.value; ApiResult.Ok(JPEG_BYTES) }

        manager.sync()

        assertEquals("both pending before the first download", setOf(cover, module), seen[0])
        assertEquals("cover done, module still pending", setOf(module), seen[1])
        assertTrue("nothing pending afterwards", bundles.imagesPending.value.isEmpty())
    }

    @Test
    fun `a failed download collapses its placeholder`() = runTest {
        bundles.save(sampleBundleWithImages())
        api.imageResult = { ApiResult.Retryable("offline") }

        manager.sync()

        assertTrue(bundles.imagesPending.value.isEmpty())
        val cover = bundles.coverFile("course-1")
        assertEquals(ImageSlot.NOTHING, imageSlot(hasImage = false, decodeSettled = true, fileExists = cover.exists(), downloadPending = cover.path in bundles.imagesPending.value))
    }

    @Test
    fun `images that are not attempted this pass are not left pending`() = runTest {
        val many = sampleBundle().let { b ->
            b.copy(lessons = (1..6).map { i -> b.lessons.single().copy(id = "l$i", order = i, imageUrl = "https://demo.example/m/$i") })
        }
        bundles.save(many)
        api.imageResult = { ApiResult.Retryable("timeout") } // gives up after three in a row

        manager.sync()

        assertEquals(3, api.imageCalls.size)
        assertTrue("the other three must not keep a placeholder", bundles.imagesPending.value.isEmpty())
    }

    @Test
    fun `no placeholder for an image the bundle does not name or the server already said is gone`() = runTest {
        bundles.save(sampleBundle()) // no image urls
        val seen = mutableListOf<Set<String>>()
        manager.sync()
        assertTrue(bundles.imagesPending.value.isEmpty())

        bundles.save(sampleBundleWithImages())
        api.imageResult = { seen += bundles.imagesPending.value; ApiResult.Rejected(404, "gone") }
        manager.sync()
        seen.clear()
        manager.sync() // 404s are remembered: nothing to attempt, nothing pending
        assertTrue(seen.isEmpty())
        assertTrue(bundles.imagesPending.value.isEmpty())
    }

    @Test
    fun `a pass that crashes mid-download still clears pending`() = runTest {
        bundles.save(sampleBundleWithImages())
        api.imageResult = { throw IllegalStateException("boom") }

        runCatching { manager.sync() }

        assertTrue(bundles.imagesPending.value.isEmpty())
    }

    // ---- image identity ----

    @Test
    fun `media ids are compared, not whole urls`() {
        assertEquals("abc-123", LearnerSyncManager.mediaId("https://one.example/api/sync/media/abc-123"))
        assertEquals("abc-123", LearnerSyncManager.mediaId("http://127.0.0.1:3001/api/sync/media/abc-123"))
        assertEquals("abc-123", LearnerSyncManager.mediaId("https://x/api/sync/media/abc-123?v=2"))
        assertEquals("abc-123", LearnerSyncManager.mediaId("https://x/api/sync/media/abc-123/"))
        assertEquals("a url of another shape is compared whole", "https://x/other/1", LearnerSyncManager.mediaId("https://x/other/1"))
    }

    @Test
    fun `a changed base url keeps the valid files and does not download them again`() = runTest {
        val old = sampleBundleWithImages("course-1", "lesson-1").copy(version = "v1")
        bundles.save(old)
        manager.sync() // cover + module image downloaded from demo.example
        api.imageCalls.clear()
        // Same media ids, new host (say the demo moved from adb reverse to Railway).
        fun moved(u: String?) = u?.replace("https://demo.example", "https://new-host.example")
        val moved = old.copy(
            version = "v2",
            course = old.course.copy(coverImageUrl = moved(old.course.coverImageUrl)),
            lessons = old.lessons.map { it.copy(imageUrl = moved(it.imageUrl)) }
        )
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        api.bundleResult = { ApiResult.Ok(moved) }

        manager.sync()

        assertTrue("the files are still there", bundles.coverFile("course-1").exists() && bundles.lessonImageFile("course-1", "lesson-1").exists())
        assertTrue("nothing was fetched again", api.imageCalls.isEmpty())
    }

    @Test
    fun `a different media id replaces the file even when the host also changed`() = runTest {
        val old = sampleBundleWithImages("course-1", "lesson-1").copy(version = "v1")
        bundles.save(old)
        manager.sync()
        api.imageCalls.clear()
        val newCover = "https://new-host.example/api/sync/media/cover-NEW"
        meWith(MeCourse("course-1", bundleVersion = "v2"))
        api.bundleResult = { ApiResult.Ok(old.copy(version = "v2", course = old.course.copy(coverImageUrl = newCover))) }

        manager.sync()

        assertEquals(listOf(newCover), api.imageCalls)
    }

    // ---- learner token: exchange before syncing, re-exchange on 401 ----

    private fun withRefresher(refresher: FakeRefresher, token: String? = null, learner: String? = null, build: String = ""):
        Triple<LearnerSyncManager, FakeTokenStore, SyncConfig> {
        val store = FakeTokenStore(token, learner)
        val config = SyncConfig("http://x", build, store)
        val m = LearnerSyncManager(api, dao, bundles, progress, config, net, refresher, LocalLearnerData(dao, bundles, progress))
        return Triple(m, store, config)
    }

    @Test
    fun `a sync first makes sure there is a token, and runs once it has one`() = runTest {
        lateinit var store: FakeTokenStore
        val refresher = FakeRefresher(ensure = { store.token = "exchanged"; true })
        val (m, s, _) = withRefresher(refresher)
        store = s
        m.enqueueQuizAnswer("c", "l", "q", "A", true)
        assertFalse(m.isConfigured)

        val report = m.sync()

        assertEquals(1, refresher.ensureCalls)
        assertEquals(SyncStatus.SYNCED, report.status)
        assertEquals(1, api.eventBatches.size)
        assertTrue(m.isConfigured)
    }

    @Test
    fun `with no token and nothing to exchange, sync does nothing`() = runTest {
        val refresher = FakeRefresher(ensure = { false })
        val (m, _, _) = withRefresher(refresher)
        m.enqueueQuizAnswer("c", "l", "q", "A", true)

        assertEquals(SyncStatus.NOT_CONFIGURED, m.sync().status)

        assertTrue(api.eventBatches.isEmpty())
        assertEquals(1, dao.items.size)
    }

    @Test
    fun `offline a stored token is used as is and no exchange is attempted`() = runTest {
        val refresher = FakeRefresher(ensure = { true })
        val (m, _, _) = withRefresher(refresher, token = "stored")
        net.online = false

        assertEquals(SyncStatus.OFFLINE, m.sync().status)
        assertEquals("exchange only runs online", 0, refresher.ensureCalls)
    }

    @Test
    fun `a 401 re-exchanges and the sync is retried with the new token`() = runTest {
        var calls = 0
        api.eventsResult = { evs ->
            if (calls++ == 0) ApiResult.Unauthorized("expired") else ApiResult.Ok(EventsResponse(accepted = evs.map { it.clientEventId }))
        }
        lateinit var store: FakeTokenStore
        val refresher = FakeRefresher(refresh = { store.token = "fresh"; true })
        val (m, s, _) = withRefresher(refresher, token = "stale", learner = "learner-1")
        store = s
        m.enqueueQuizAnswer("c", "l", "q", "A", true)

        val report = m.sync()

        assertEquals(1, refresher.refreshCalls)
        assertEquals(SyncStatus.SYNCED, report.status)
        assertEquals("the same event was sent again under the fresh token", 2, api.eventBatches.size)
        assertTrue(dao.items.isEmpty())
        assertEquals("fresh", store.token)
        assertEquals(SyncStatus.SYNCED, m.status.value)
    }

    @Test
    fun `a 401 that a re-exchange does not fix clears the stored token and keeps the data`() = runTest {
        api.eventsResult = { ApiResult.Unauthorized("expired") }
        val refresher = FakeRefresher(refresh = { false })
        val (m, store, _) = withRefresher(refresher, token = "stale", learner = "learner-1")
        bundles.save(sampleBundle("course-1", "lesson-1"))
        m.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)

        val report = m.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertNull(store.token)
        assertEquals("kept, so the same person signing back in loses nothing", "learner-1", store.learner)
        assertEquals(1, dao.items.size)
        assertNotNull(bundles.get("course-1"))
        assertEquals(SyncStatus.NOT_CONFIGURED, m.status.value)
        assertTrue(m.needsToken)
    }

    @Test
    fun `a second 401 after a successful re-exchange does not loop`() = runTest {
        api.eventsResult = { ApiResult.Unauthorized("still no") }
        lateinit var store: FakeTokenStore
        val refresher = FakeRefresher(refresh = { store.token = "fresh"; true })
        val (m, s, _) = withRefresher(refresher, token = "stale")
        store = s

        m.enqueueQuizAnswer("c", "l", "q", "A", true)
        m.sync()

        assertEquals(1, refresher.refreshCalls)
        assertEquals(2, api.eventBatches.size)
        assertNull("the token that failed twice is dropped", store.token)
    }

    @Test
    fun `a 401 on a lesson request or on me also re-exchanges`() = runTest {
        var lessonCalls = 0
        api.lessonResult = { _, _, _ ->
            if (lessonCalls++ == 0) ApiResult.Unauthorized("expired") else ApiResult.Ok(RequestLessonResult("course-1", sampleBundle()))
        }
        val refresher = FakeRefresher(refresh = { true })
        val (m, _, _) = withRefresher(refresher, token = "stale")
        m.enqueueLessonRequest("fractions", "en")

        val report = m.sync()

        assertEquals(1, refresher.refreshCalls)
        assertEquals(1, report.lessonsDownloaded)
        assertNotNull(bundles.get("course-1"))
    }

    @Test
    fun `a 401 with the build-time token keeps it and reports sign-in expired`() = runTest {
        api.eventsResult = { ApiResult.Unauthorized("expired") }
        val refresher = FakeRefresher(refresh = { false })
        val (m, _, _) = withRefresher(refresher, build = "build-token")
        m.enqueueQuizAnswer("c", "l", "q", "A", true)

        val report = m.sync()

        assertEquals(SyncStatus.UNAUTHORIZED, report.status)
        assertEquals(SyncStatus.UNAUTHORIZED, m.status.value)
        assertTrue("the build token is not ours to clear", m.isConfigured)
        assertFalse(m.hasStoredToken)
    }

    // ---- sign-out ----

    @Test
    fun `signing out clears the token, the learner, the lessons, the progress and the learner queue`() = runTest {
        val (m, store, _) = withRefresher(FakeRefresher(), token = "tok", learner = "learner-1")
        bundles.save(sampleBundleWithImages("course-1", "lesson-1"))
        bundles.writeImage(bundles.coverFile("course-1"), JPEG_BYTES)
        progress.recordAnswer("course-1", "lesson-1", "q1", "B", true, "t")
        progress.markCompleted("lesson-1")
        progress.addFailedRequest(FailedLessonRequest("r1", "topic", "en", "bad"))
        m.enqueueQuizAnswer("course-1", "lesson-1", "q1", "B", true)
        m.enqueueLessonRequest("topic", "en")
        dao.insert(org.ekatra.alfred.data.model.SyncQueueItem(type = "chat_analytics", payload = "{}"))

        m.signOut()

        assertNull(store.token); assertNull(store.learner)
        assertFalse(m.isConfigured); assertTrue(m.needsToken)
        assertEquals(SyncStatus.NOT_CONFIGURED, m.status.value)
        assertTrue(bundles.bundles.value.isEmpty())
        assertEquals("images go with the lessons", 0, File(dir, "lessons").listFiles()?.size ?: 0)
        assertTrue(progress.data.answers.isEmpty() && progress.data.completedLessonIds.isEmpty() && progress.data.failedRequests.isEmpty())
        assertTrue(dao.items.none { it.type == "learner_event" || it.type == "lesson_request" })
        assertEquals("analytics belong to a different setting", 1, dao.items.count { it.type == "chat_analytics" })
    }

    @Test
    fun `after sign out nothing more is sent under the old token`() = runTest {
        val (m, _, _) = withRefresher(FakeRefresher(), token = "tok")
        m.enqueueQuizAnswer("c", "l", "q", "A", true)
        m.signOut()

        assertEquals(SyncStatus.NOT_CONFIGURED, m.sync().status)
        assertTrue(api.eventBatches.isEmpty() && api.meCalls == 0)
    }

    @Test
    fun `signing out of a stored token falls back to the build-time token`() = runTest {
        val (m, _, _) = withRefresher(FakeRefresher(), token = "stored", learner = "learner-1", build = "build-token")

        m.signOut()

        assertTrue("the build-time token takes over", m.isConfigured)
        assertFalse(m.hasStoredToken)
        assertEquals(SyncStatus.IDLE, m.status.value)
    }
}
