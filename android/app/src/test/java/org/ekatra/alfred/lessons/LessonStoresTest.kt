package org.ekatra.alfred.lessons

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

class LessonStoresTest {
    private lateinit var dir: File

    @Before fun setup() { dir = Files.createTempDirectory("lesson-store").toFile() }
    @After fun cleanup() { dir.deleteRecursively() }

    @Test
    fun `bundle round-trips through its json file and leaves no temp file`() {
        val store = LessonStore(File(dir, "lessons"))
        store.save(sampleBundle())

        val reopened = LessonStore(File(dir, "lessons"))
        assertEquals("Fractions", reopened.get("course-1")?.course?.title)
        assertEquals(2, reopened.get("course-1")?.lessons?.single()?.questions?.size)
        assertTrue(File(dir, "lessons").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `saving the same course again replaces it`() {
        val store = LessonStore(File(dir, "lessons"))
        store.save(sampleBundle())
        store.save(sampleBundle().copy(version = "v2"))

        assertEquals(1, store.bundles.value.size)
        assertEquals("v2", store.get("course-1")?.version)
    }

    @Test
    fun `a corrupt file is skipped, not fatal`() {
        val lessons = File(dir, "lessons").apply { mkdirs() }
        File(lessons, "bad.json").writeText("{{{")
        val store = LessonStore(lessons)
        store.save(sampleBundle())

        assertEquals(listOf("course-1"), store.bundles.value.map { it.course.id })
    }

    @Test
    fun `a course id cannot escape the lessons directory`() {
        val lessons = File(dir, "lessons")
        val store = LessonStore(lessons)
        store.save(sampleBundle(courseId = "../../evil"))

        assertFalse(File(dir, "evil.json").exists())
        assertTrue(lessons.listFiles()!!.single().name.endsWith(".json"))
    }

    @Test
    fun `progress survives a restart`() {
        val file = File(dir, "progress.json")
        val store = LessonProgressStore(file)
        store.recordAnswer("c", "l", "q1", "B", true, "2026-11-01T00:00:00.000Z")
        store.markCompleted("l")
        store.saveProfile(ServerProfile(struggles = listOf("l2")))

        val again = LessonProgressStore(file)
        assertEquals("B", again.answerFor("q1")?.answer)
        assertTrue(again.isCompleted("l"))
        assertEquals(listOf("l2"), again.data.profile?.struggles)
        assertNull(again.answerFor("nope"))
    }

    @Test
    fun `an unreadable progress file starts empty`() {
        val file = File(dir, "progress.json").apply { writeText("garbage") }
        assertNotNull(LessonProgressStore(file).data)
        assertTrue(LessonProgressStore(file).data.answers.isEmpty())
    }

    @Test
    fun `grading compares letters case-insensitively`() {
        val q = sampleBundle().lessons.single().questions.first()
        assertTrue(LessonGrading.isCorrect(q, "B"))
        assertTrue(LessonGrading.isCorrect(q, "b"))
        assertFalse(LessonGrading.isCorrect(q, "A"))
        assertEquals("5", LessonGrading.optionText(q, "B"))
        assertEquals("C", LessonGrading.letterFor(2))
    }
}
