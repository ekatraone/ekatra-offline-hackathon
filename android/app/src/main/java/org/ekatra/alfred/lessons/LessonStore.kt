package org.ekatra.alfred.lessons

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Lesson bundles as JSON files: `<dir>/<courseId>.json`. Written to a temp file and renamed,
 * so a crash mid-write never leaves a half-written bundle. No Room schema change.
 */
class LessonStore(private val dir: File) {

    private val gson = Gson()
    private val _bundles = MutableStateFlow(readAll())
    val bundles: StateFlow<List<LessonBundle>> = _bundles

    @Synchronized
    fun save(bundle: LessonBundle) {
        dir.mkdirs()
        val target = fileFor(bundle.course.id)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(gson.toJson(bundle), Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not move ${tmp.name} into place" }
        }
        _bundles.value = readAll()
    }

    fun get(courseId: String): LessonBundle? = _bundles.value.firstOrNull { it.course.id == courseId }

    fun has(courseId: String): Boolean = fileFor(courseId).exists()

    @Synchronized
    fun delete(courseId: String) {
        fileFor(courseId).delete()
        imageDir(courseId).deleteRecursively()
        _bundles.value = readAll()
    }

    /** Remove every stored bundle (account deletion). */
    @Synchronized
    fun clear() {
        // Bundles are files; each course's images live in a directory next to them.
        dir.listFiles()?.forEach { it.deleteRecursively() }
        _bundles.value = emptyList()
        _imageRevision.value += 1
    }

    // ---- images: <dir>/<courseId>/cover.jpg and <dir>/<courseId>/<lessonId>.jpg ----

    private val _imageRevision = MutableStateFlow(0)
    /** Bumps whenever an image file appears or goes, so the UI knows to decode again. */
    val imageRevision: StateFlow<Int> = _imageRevision

    fun coverFile(courseId: String): File = File(imageDir(courseId), "cover.jpg")

    fun lessonImageFile(courseId: String, lessonId: String): File = File(imageDir(courseId), "${safe(lessonId)}.jpg")

    /** Every image this bundle names: its local file and the URL to fetch it from (null = none). */
    fun imageTargets(bundle: LessonBundle): List<Pair<File, String?>> =
        listOf(coverFile(bundle.course.id) to bundle.course.coverImageUrl) +
            bundle.lessons.filter { !it.isReview }.map { lessonImageFile(bundle.course.id, it.id) to it.imageUrl }

    /** Write via a temp file and rename, so a half-downloaded image is never shown. */
    @Synchronized
    fun writeImage(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "Could not move ${tmp.name} into place" }
        }
        _imageRevision.value += 1
    }

    /** Newest first. Unreadable files are skipped, not fatal. */
    private fun readAll(): List<LessonBundle> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching { gson.fromJson(f.readText(Charsets.UTF_8), LessonBundle::class.java) }
                    .getOrNull()?.takeIf { it.course != null }
            }

    // Ids are UUIDs; strip anything else so an id can never escape the directory.
    private fun safe(id: String) = id.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
    private fun fileFor(courseId: String) = File(dir, safe(courseId) + ".json")
    private fun imageDir(courseId: String) = File(dir, safe(courseId))
}
