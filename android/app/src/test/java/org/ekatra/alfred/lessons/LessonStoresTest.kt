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
    fun `writing an image goes through a temp file`() {
        val store = LessonStore(File(dir, "lessons"))

        store.writeImage(store.coverFile("c"), JPEG_BYTES)

        assertEquals(JPEG_BYTES.toList(), store.coverFile("c").readBytes().toList())
        assertTrue(File(dir, "lessons/c").listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `writing one image changes only that image's stamp`() {
        val store = LessonStore(File(dir, "lessons"))
        val cover = store.coverFile("c"); val a = store.lessonImageFile("c", "a"); val b = store.lessonImageFile("c", "b")
        store.writeImage(cover, JPEG_BYTES); store.writeImage(a, JPEG_BYTES)
        val before = store.imageStamps.value

        store.writeImage(b, JPEG_BYTES)
        assertEquals(before[cover.path], store.imageStamps.value[cover.path])
        assertEquals(before[a.path], store.imageStamps.value[a.path])
        assertTrue(store.imageStamps.value[b.path]!! > 0)

        val coverStamp = store.imageStamps.value[cover.path]!!
        store.writeImage(cover, JPEG_BYTES + 1)
        assertEquals(coverStamp + 1, store.imageStamps.value[cover.path])
        assertEquals("others untouched", before[a.path], store.imageStamps.value[a.path])
    }

    @Test
    fun `deleting an image bumps its stamp so the screen drops it`() {
        val store = LessonStore(File(dir, "lessons"))
        val cover = store.coverFile("c")
        store.writeImage(cover, JPEG_BYTES)
        val stamp = store.imageStamps.value[cover.path]!!

        store.deleteImage(cover)

        assertFalse(cover.exists())
        assertEquals(stamp + 1, store.imageStamps.value[cover.path])
    }

    @Test
    fun `pending images are tracked per path and cleared on finish`() {
        val store = LessonStore(File(dir, "lessons"))
        store.setImagesPending(setOf("/a", "/b"))
        store.imageDownloadFinished("/a")
        assertEquals(setOf("/b"), store.imagesPending.value)
        store.setImagesPending(emptySet())
        assertTrue(store.imagesPending.value.isEmpty())
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

    // ---- decode plan ----

    private val twoMiB = 2L * 1024 * 1024

    @Test
    fun `a full size image on a wide view decodes at its native 1024 and fits in 2 MiB`() {
        val plan = computeDecodePlan(1024, 1024, viewWidthPx = 1080)
        assertEquals(DecodePlan(1, 1024, 1024), plan)
        assertTrue("was ${plan.bytes}", plan.bytes <= twoMiB)
    }

    @Test
    fun `a view about 1000 px wide gets 1000 px, not an upscaled 640`() {
        val plan = computeDecodePlan(1024, 1024, viewWidthPx = 1000)
        assertEquals(1, plan.sampleSize)
        assertEquals(1000, plan.outWidth)
        assertTrue(plan.bytes <= twoMiB)
    }

    @Test
    fun `the cap is 1024 on the shorter side`() {
        assertEquals(1024, MAX_DECODE_SHORT_SIDE)
        assertEquals(1024, computeDecodePlan(2048, 2048, viewWidthPx = 4000).outWidth)
    }

    @Test
    fun `the wide cover banner is sized like any other view of that width, by its shorter side`() {
        val banner = computeDecodePlan(1024, 1024, viewWidthPx = 1080)
        assertTrue(banner.bytes <= twoMiB)
        assertTrue("was 4 MiB as ARGB_8888", banner.bytes <= 4L * 1024 * 1024 / 2)
    }

    @Test
    fun `the reader's two images together are about half the old 8 MB`() {
        val cover = computeDecodePlan(1024, 1024, 1080).bytes
        val module = computeDecodePlan(1024, 1024, 1000).bytes
        assertTrue("cover+module was ${cover + module}", cover + module <= 2 * twoMiB)
    }

    @Test
    fun `a smaller view gets a power-of-two sample and an exact output size`() {
        val plan = computeDecodePlan(1024, 1024, viewWidthPx = 300)
        assertEquals(2, plan.sampleSize) // 512 >= 300; 256 would be too small
        assertEquals(300, plan.outWidth); assertEquals(300, plan.outHeight)

        val thumb = computeDecodePlan(1024, 1024, viewWidthPx = 100)
        assertEquals(8, thumb.sampleSize)
        assertEquals(100, thumb.outWidth)
    }

    @Test
    fun `never upscales, and keeps the aspect ratio of a non-square image`() {
        assertEquals(DecodePlan(1, 400, 300), computeDecodePlan(400, 300, viewWidthPx = 1080))
        val wide = computeDecodePlan(4096, 2048, viewWidthPx = 1000)
        assertEquals(1000, wide.outHeight)           // the shorter side is the one that is sized
        assertEquals(2000, wide.outWidth)
    }

    @Test
    fun `any view width keeps one 1024 image within 2 MiB`() {
        for (w in 1..2400 step 7) {
            val plan = computeDecodePlan(1024, 1024, w)
            assertTrue("view $w -> ${plan.bytes} bytes", plan.bytes <= twoMiB)
            assertTrue("view $w sample ${plan.sampleSize}", plan.sampleSize in listOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024))
        }
    }

    @Test
    fun `bad dimensions fall back to a plain decode`() {
        assertEquals(1, computeDecodePlan(0, 0, 100).sampleSize)
        assertEquals(1, computeDecodePlan(1024, 1024, 0).sampleSize)
        assertTrue(computeDecodePlan(1024, 1024, 0).outWidth >= 1)
    }

    // ---- what a slot draws ----

    @Test
    fun `the placeholder holds through decoding, so the slot does not collapse and then pop back`() {
        // Download finished (not pending any more), file is on disk, bitmap not decoded yet.
        assertEquals(ImageSlot.PLACEHOLDER, imageSlot(hasImage = false, decodeSettled = false, fileExists = true, downloadPending = false))
        // Then the bitmap arrives.
        assertEquals(ImageSlot.SHOW_IMAGE, imageSlot(hasImage = true, decodeSettled = true, fileExists = true, downloadPending = false))
    }

    @Test
    fun `a replaced image keeps showing the previous bitmap while the new file decodes`() {
        assertEquals(ImageSlot.SHOW_IMAGE, imageSlot(hasImage = true, decodeSettled = false, fileExists = true, downloadPending = false))
        assertEquals(ImageSlot.SHOW_IMAGE, imageSlot(hasImage = true, decodeSettled = false, fileExists = true, downloadPending = true))
    }

    @Test
    fun `a bitmap for a file that has since been deleted is not shown`() {
        assertEquals(ImageSlot.NOTHING, imageSlot(hasImage = true, decodeSettled = true, fileExists = false, downloadPending = false))
        assertEquals(ImageSlot.PLACEHOLDER, imageSlot(hasImage = true, decodeSettled = true, fileExists = false, downloadPending = true))
    }

    @Test
    fun `while a download is pending the slot is a placeholder, after a failed one it is nothing`() {
        assertEquals(ImageSlot.PLACEHOLDER, imageSlot(hasImage = false, decodeSettled = false, fileExists = false, downloadPending = true))
        assertEquals(ImageSlot.NOTHING, imageSlot(hasImage = false, decodeSettled = true, fileExists = false, downloadPending = false))
        assertEquals(ImageSlot.NOTHING, imageSlot(hasImage = false, decodeSettled = false, fileExists = false, downloadPending = false))
    }

    @Test
    fun `a file that is on disk but cannot be decoded draws nothing once the decode has settled`() {
        assertEquals(ImageSlot.NOTHING, imageSlot(hasImage = false, decodeSettled = true, fileExists = true, downloadPending = false))
    }

    @Test
    fun `an empty slot is not emitted, so it adds no spacing gap`() {
        assertFalse(slotVisible(fileExists = false, knownBroken = false, downloadPending = false))
        assertFalse(slotVisible(fileExists = true, knownBroken = true, downloadPending = false))
        assertTrue(slotVisible(fileExists = true, knownBroken = false, downloadPending = false))
        assertTrue(slotVisible(fileExists = false, knownBroken = false, downloadPending = true))
        assertTrue("a pending download wins over a stale broken mark", slotVisible(fileExists = true, knownBroken = true, downloadPending = true))
    }

    // ---- bitmap LRU ----

    @Test
    fun `the cache evicts the least recently used and keeps the newest`() {
        val lru = SizedLru<String>(maxBytes = 100)
        lru.put("a", "A", 40); lru.put("b", "B", 40)
        assertEquals("A", lru["a"]) // touch a: now b is the oldest
        lru.put("c", "C", 40)

        assertNull(lru["b"])
        assertEquals("A", lru["a"]); assertEquals("C", lru["c"])
        assertEquals(80, lru.totalBytes)
    }

    @Test
    fun `replacing an entry fixes the byte count and one oversized entry is still kept`() {
        val lru = SizedLru<String>(maxBytes = 100)
        lru.put("a", "A", 60); lru.put("a", "A2", 30)
        assertEquals(30, lru.totalBytes); assertEquals("A2", lru["a"])

        lru.put("big", "BIG", 500)
        assertEquals("BIG", lru["big"])
        assertEquals(1, lru.size)
        lru.remove("big"); assertEquals(0, lru.totalBytes)
        lru.put("x", "X", 10); lru.clear(); assertEquals(0, lru.size)
    }
}
