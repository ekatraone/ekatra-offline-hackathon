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

    @Test
    fun `image paths and targets follow the bundle, review lessons excluded`() {
        val store = LessonStore(File(dir, "lessons"))
        val targets = store.imageTargets(sampleBundleWithImages())

        assertEquals(listOf(store.coverFile("course-1"), store.lessonImageFile("course-1", "lesson-1")), targets.map { it.first })
        assertEquals(listOf(COVER_URL, LESSON_IMAGE_URL), targets.map { it.second })
        assertEquals("cover.jpg", store.coverFile("course-1").name)
        assertEquals("lesson-1.jpg", store.lessonImageFile("course-1", "lesson-1").name)
    }

    @Test
    fun `an old bundle has image slots with no urls`() {
        val store = LessonStore(File(dir, "lessons"))
        assertTrue(store.imageTargets(sampleBundle()).all { it.second == null })
    }

    @Test
    fun `writing an image goes through a temp file and bumps the revision`() {
        val store = LessonStore(File(dir, "lessons"))
        val before = store.imageRevision.value

        store.writeImage(store.coverFile("c"), JPEG_BYTES)

        assertEquals(JPEG_BYTES.toList(), store.coverFile("c").readBytes().toList())
        assertTrue(File(dir, "lessons/c").listFiles()!!.none { it.name.endsWith(".tmp") })
        assertEquals(before + 1, store.imageRevision.value)
    }

    @Test
    fun `an image survives a restart and does not look like a bundle`() {
        val store = LessonStore(File(dir, "lessons"))
        store.save(sampleBundleWithImages())
        store.writeImage(store.coverFile("course-1"), JPEG_BYTES)

        val reopened = LessonStore(File(dir, "lessons"))
        assertEquals(1, reopened.bundles.value.size)
        assertTrue(reopened.coverFile("course-1").exists())
    }

    @Test
    fun `clear removes bundles and their image folders`() {
        val store = LessonStore(File(dir, "lessons"))
        store.save(sampleBundleWithImages("c1", "l1"))
        store.save(sampleBundleWithImages("c2", "l2"))
        store.writeImage(store.coverFile("c1"), JPEG_BYTES)
        store.writeImage(store.lessonImageFile("c2", "l2"), JPEG_BYTES)

        store.clear()

        assertEquals(0, File(dir, "lessons").listFiles()?.size ?: 0)
        assertTrue(store.bundles.value.isEmpty())
    }

    @Test
    fun `deleting a course removes its images`() {
        val store = LessonStore(File(dir, "lessons"))
        store.save(sampleBundleWithImages("c1", "l1"))
        store.writeImage(store.coverFile("c1"), JPEG_BYTES)

        store.delete("c1")

        assertFalse(File(dir, "lessons/c1").exists())
        assertNull(store.get("c1"))
    }

    @Test
    fun `an image id cannot escape the lessons folder`() {
        val store = LessonStore(File(dir, "lessons"))
        val f = store.lessonImageFile("../../evil", "../x")
        assertTrue(f.canonicalPath.startsWith(File(dir, "lessons").canonicalPath))
    }

    @Test
    fun `sample size halves until the image would drop below the view`() {
        assertEquals(1, computeSampleSize(1024, 1024, 1024, 1024))
        assertEquals(2, computeSampleSize(1024, 1024, 512, 512))
        assertEquals("256 px would be too small for a 300 px view", 2, computeSampleSize(1024, 1024, 300, 300))
        assertEquals(4, computeSampleSize(1024, 1024, 250, 250))
        assertEquals(1, computeSampleSize(1024, 1024, 700, 700))
        assertEquals(8, computeSampleSize(1024, 1024, 128, 128))
        assertEquals("never upscale", 1, computeSampleSize(100, 100, 400, 400))
        assertEquals("a tall view is limited by its height, not its width", 2, computeSampleSize(2048, 2048, 300, 1000))
        assertEquals(1, computeSampleSize(0, 0, 100, 100))
        assertEquals(1, computeSampleSize(1024, 1024, 0, 0))
    }
}
