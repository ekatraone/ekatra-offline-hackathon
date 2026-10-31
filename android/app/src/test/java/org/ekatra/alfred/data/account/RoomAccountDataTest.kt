package org.ekatra.alfred.data.account

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.ekatra.alfred.data.AppSettings
import org.ekatra.alfred.data.local.UserProfileDao
import org.ekatra.alfred.data.model.SyncQueueItem
import org.ekatra.alfred.data.repository.ChatRepository
import org.ekatra.alfred.lessons.FakeQueueDao
import org.ekatra.alfred.lessons.LessonProgressStore
import org.ekatra.alfred.lessons.LessonStore
import org.ekatra.alfred.lessons.ServerProfile
import org.ekatra.alfred.lessons.sampleBundle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Account deletion must take the learner's lessons with it: bundles, progress and unsynced learner rows. */
class RoomAccountDataTest {

    private lateinit var dir: File
    private lateinit var chats: ChatRepository
    private lateinit var profiles: UserProfileDao
    private lateinit var settings: AppSettings
    private lateinit var queue: FakeQueueDao
    private lateinit var lessons: LessonStore
    private lateinit var progress: LessonProgressStore
    private lateinit var data: RoomAccountData
    private lateinit var tokens: org.ekatra.alfred.lessons.FakeTokenStore
    private lateinit var config: org.ekatra.alfred.lessons.SyncConfig
    private lateinit var consent: org.ekatra.alfred.lessons.LessonsConsent

    @Before
    fun setup() {
        dir = Files.createTempDirectory("account-data").toFile()
        chats = mockk(relaxed = true)
        profiles = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        queue = FakeQueueDao()
        lessons = LessonStore(File(dir, "lessons"))
        progress = LessonProgressStore(File(dir, "lesson_progress.json"))
        tokens = org.ekatra.alfred.lessons.FakeTokenStore("joined-token", "learner-1")
        config = org.ekatra.alfred.lessons.SyncConfig("http://x", "", tokens)
        consent = org.ekatra.alfred.lessons.acceptedConsent()
        data = RoomAccountData(chats, profiles, settings, queue, lessons, progress, config, consent)
    }

    @After
    fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `clear removes lesson bundles, progress file and the learner queue rows`() = runBlocking {
        lessons.save(sampleBundle("c1", "l1"))
        lessons.save(sampleBundle("c2", "l2"))
        progress.recordAnswer("c1", "l1", "q1", "B", true, "2026-11-01T00:00:00.000Z")
        progress.markCompleted("l1")
        progress.saveProfile(ServerProfile(struggles = listOf("l1")))
        progress.addFailedRequest(org.ekatra.alfred.lessons.FailedLessonRequest("r1", "topic", "en", "bad"))
        queue.insert(SyncQueueItem(type = "learner_event", payload = "{}"))
        queue.insert(SyncQueueItem(type = "lesson_request", payload = "{}"))

        data.clear("uid1")

        assertTrue(queue.items.none { it.type == "learner_event" || it.type == "lesson_request" })
        assertTrue(lessons.bundles.value.isEmpty())
        assertNull(lessons.get("c1"))
        assertEquals(0, File(dir, "lessons").listFiles()?.size ?: 0)
        assertFalse(File(dir, "lesson_progress.json").exists())
        assertTrue(progress.data.answers.isEmpty())
        assertTrue(progress.data.completedLessonIds.isEmpty())
        assertNull(progress.data.profile)
        assertTrue(progress.data.failedRequests.isEmpty())
    }

    @Test
    fun `queued chat analytics are cleared too so nothing uploads under another uid`() = runBlocking {
        queue.insert(SyncQueueItem(type = "chat_analytics", payload = "{}"))
        queue.insert(SyncQueueItem(type = "chat_analytics", payload = "{}"))
        queue.insert(SyncQueueItem(type = "learner_event", payload = "{}"))

        data.clear("uid1")

        assertTrue("sync queue should be empty but has ${queue.items.map { it.type }}", queue.items.isEmpty())
    }

    @Test
    fun `downloaded lesson images are wiped with the bundles`() = runBlocking {
        lessons.save(org.ekatra.alfred.lessons.sampleBundleWithImages("c1", "l1"))
        lessons.writeImage(lessons.coverFile("c1"), org.ekatra.alfred.lessons.JPEG_BYTES)
        lessons.writeImage(lessons.lessonImageFile("c1", "l1"), org.ekatra.alfred.lessons.JPEG_BYTES)
        assertTrue(File(dir, "lessons/c1/cover.jpg").exists())

        data.clear("uid1")

        assertFalse(File(dir, "lessons/c1").exists())
        assertEquals(0, File(dir, "lessons").listFiles()?.size ?: 0)
    }

    @Test
    fun `the lessons token goes with the account, so the next person has to sign in again`() = runBlocking {
        assertTrue(config.isConfigured)

        data.clear("uid1")

        assertNull(tokens.token); assertNull(tokens.learner)
        assertFalse(config.isConfigured)
        assertTrue(config.needsToken)
    }

    @Test
    fun `the Lessons consent is forgotten with the account, so the next person is asked`() = runBlocking {
        assertTrue(consent.isAccepted())

        data.clear("uid1")

        assertFalse(consent.isAccepted())
        assertEquals(org.ekatra.alfred.lessons.ConsentState.NEEDED, consent.state.value)
    }

    @Test
    fun `a pending server erase survives the local wipe, because it is the only way to retry it`() = runBlocking {
        tokens.pendingDeletes += "token-of-the-deleted-account"

        data.clear("uid1")

        assertEquals(setOf("token-of-the-deleted-account"), tokens.pendingDeletes)
        assertNull("the active token is gone", tokens.token)
    }

    @Test
    fun `a restart after deletion finds nothing`() = runBlocking {
        lessons.save(sampleBundle())
        progress.markCompleted("lesson-1")

        data.clear("uid1")

        assertTrue(LessonStore(File(dir, "lessons")).bundles.value.isEmpty())
        assertTrue(LessonProgressStore(File(dir, "lesson_progress.json")).data.completedLessonIds.isEmpty())
    }

    @Test
    fun `the existing local wipe still happens`() = runBlocking {
        data.clear("uid1")

        coVerify { chats.deleteAllSessions() }
        coVerify { profiles.deleteProfile("uid1") }
        verify { settings.clearAllExceptConsent() }
    }

    @Test
    fun `clearing with nothing stored is harmless`() = runBlocking {
        data.clear("uid1")
        data.clear("uid1")
        assertTrue(lessons.bundles.value.isEmpty())
    }
}
