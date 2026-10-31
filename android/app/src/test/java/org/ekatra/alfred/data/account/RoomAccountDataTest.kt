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

    @Before
    fun setup() {
        dir = Files.createTempDirectory("account-data").toFile()
        chats = mockk(relaxed = true)
        profiles = mockk(relaxed = true)
        settings = mockk(relaxed = true)
        queue = FakeQueueDao()
        lessons = LessonStore(File(dir, "lessons"))
        progress = LessonProgressStore(File(dir, "lesson_progress.json"))
        data = RoomAccountData(chats, profiles, settings, queue, lessons, progress)
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
