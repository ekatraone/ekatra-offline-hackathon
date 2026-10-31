package org.ekatra.alfred.lessons

/**
 * Wire and storage models for the learner sync API (see .claude/hackathon/CONTRACT.md).
 * Gson bypasses constructors, so any field the server may omit is nullable.
 */
data class Generator(val provider: String? = null, val model: String? = null)

data class BundleCourse(
    val id: String,
    val title: String,
    val description: String? = null,
    val language: String? = null,
    val generator: Generator? = null,
    /** Optional absolute URL (public, no auth) of the cover image; null or absent on older bundles. */
    val coverImageUrl: String? = null
)

data class BundleQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    /** Letter A-D, the index into [options]. */
    val correctAnswer: String,
    val explanation: String? = null
)

data class BundleLesson(
    val id: String,
    /** "module" or "review". */
    val kind: String,
    val title: String,
    val content: String? = null,
    val day: Int = 1,
    val order: Int = 0,
    val questions: List<BundleQuestion> = emptyList(),
    /** Optional absolute URL of this module's illustration; null or absent on older bundles and on reviews. */
    val imageUrl: String? = null
) {
    val isReview: Boolean get() = kind == "review"
}

data class LessonBundle(
    val version: String? = null,
    val course: BundleCourse,
    val lessons: List<BundleLesson> = emptyList()
)

data class ProfileTotals(
    val events: Int = 0,
    val quizAnswered: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val lessonsCompleted: Int = 0
)

data class ServerProfile(
    val mastery: Map<String, Double> = emptyMap(),
    val struggles: List<String> = emptyList(),
    val preferredHours: List<Int> = emptyList(),
    val velocity: Double = 0.0,
    val totals: ProfileTotals = ProfileTotals()
)

data class MeLearner(
    val id: String? = null,
    val name: String? = null,
    val phone: String? = null,
    val language: String? = null
)

data class MeCourse(
    val courseId: String,
    val title: String? = null,
    val language: String? = null,
    val status: String? = null,
    val progress: Double? = null,
    val generator: Generator? = null,
    /** Hash of the server's current bundle, covering image ids; differs from a stored bundle's `version` when images attached late. */
    val bundleVersion: String? = null
)

data class MeResponse(
    val learner: MeLearner? = null,
    val courses: List<MeCourse> = emptyList(),
    val profile: ServerProfile? = null,
    val lessonTitles: Map<String, String> = emptyMap()
)

data class RequestLessonResult(val courseId: String, val bundle: LessonBundle)

data class LearnerEvent(
    val clientEventId: String,
    /** quiz_answered or lesson_completed; the server accepts no others. */
    val type: String,
    val courseId: String,
    val lessonId: String,
    val questionId: String? = null,
    val answer: String? = null,
    val clientCorrect: Boolean? = null,
    val occurredAt: String
) {
    companion object {
        const val QUIZ_ANSWERED = "quiz_answered"
        const val LESSON_COMPLETED = "lesson_completed"
    }
}

data class RejectedEvent(val clientEventId: String, val reason: String? = null)

data class EventsResponse(
    val accepted: List<String> = emptyList(),
    val duplicates: List<String> = emptyList(),
    val rejected: List<RejectedEvent> = emptyList(),
    val profile: ServerProfile? = null
) {
    /** Every id the server has dealt with; the client deletes exactly these from its queue. */
    fun resolvedIds(): Set<String> =
        (accepted + duplicates + rejected.map { it.clientEventId }).toSet()
}

/** Queue payload for a `lesson_request` row. */
data class LessonRequestPayload(
    val clientRequestId: String,
    val topic: String,
    val language: String,
    val occurredAt: String
)
