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
        _bundles.value = readAll()
    }

    /** Remove every stored bundle (account deletion). */
    @Synchronized
    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
        _bundles.value = emptyList()
    }

    /** Newest first. Unreadable files are skipped, not fatal. */
    private fun readAll(): List<LessonBundle> =
        (dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { f ->
                runCatching { gson.fromJson(f.readText(Charsets.UTF_8), LessonBundle::class.java) }
                    .getOrNull()?.takeIf { it.course != null }
            }

    // Course ids are UUIDs; strip anything else so an id can never escape the directory.
    private fun fileFor(courseId: String) =
        File(dir, courseId.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")
}
