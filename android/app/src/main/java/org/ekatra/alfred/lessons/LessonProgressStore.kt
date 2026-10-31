package org.ekatra.alfred.lessons

import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class StoredAnswer(
    val courseId: String,
    val lessonId: String,
    val answer: String,
    val correct: Boolean,
    val answeredAt: String
)

data class ProgressData(
    /** questionId -> the learner's latest answer. */
    val answers: Map<String, StoredAnswer> = emptyMap(),
    val completedLessonIds: Set<String> = emptySet(),
    /** Last profile the server sent (from /me or an events response). */
    val me: MeResponse? = null,
    val profile: ServerProfile? = null,
    /** Courses the bundle endpoint refused with 404/422; not asked for again. */
    val unavailableCourseIds: Set<String> = emptySet()
)

/** Small JSON store for local answers, completion and the cached server profile. */
class LessonProgressStore(private val file: File) {

    private val gson = Gson()
    private val _state = MutableStateFlow(read())
    val state: StateFlow<ProgressData> = _state

    val data: ProgressData get() = _state.value

    fun answerFor(questionId: String): StoredAnswer? = data.answers[questionId]

    fun isCompleted(lessonId: String): Boolean = lessonId in data.completedLessonIds

    @Synchronized
    fun recordAnswer(courseId: String, lessonId: String, questionId: String, answer: String, correct: Boolean, at: String) {
        update { it.copy(answers = it.answers + (questionId to StoredAnswer(courseId, lessonId, answer, correct, at))) }
    }

    @Synchronized
    fun markCompleted(lessonId: String) {
        update { it.copy(completedLessonIds = it.completedLessonIds + lessonId) }
    }

    @Synchronized
    fun saveMe(me: MeResponse) {
        update { it.copy(me = me, profile = me.profile ?: it.profile) }
    }

    @Synchronized
    fun markCourseUnavailable(courseId: String) {
        update { it.copy(unavailableCourseIds = it.unavailableCourseIds + courseId) }
    }

    @Synchronized
    fun saveProfile(profile: ServerProfile) {
        update { it.copy(profile = profile) }
    }

    /** Forget all answers, completions and the cached server profile (account deletion). */
    @Synchronized
    fun clear() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
        _state.value = ProgressData()
    }

    private fun update(change: (ProgressData) -> ProgressData) {
        val next = change(_state.value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(gson.toJson(next), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "Could not move ${tmp.name} into place" }
        }
        _state.value = next
    }

    private fun read(): ProgressData = runCatching {
        gson.fromJson(file.readText(Charsets.UTF_8), ProgressData::class.java)
            // Gson skips constructors, so a hand-edited or older file can leave these null.
            ?.let { it.copy(answers = it.answers ?: emptyMap(), completedLessonIds = it.completedLessonIds ?: emptySet(),
                unavailableCourseIds = it.unavailableCourseIds ?: emptySet()) }
    }.getOrNull() ?: ProgressData()
}
