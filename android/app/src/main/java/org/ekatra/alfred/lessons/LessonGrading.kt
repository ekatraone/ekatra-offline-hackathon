package org.ekatra.alfred.lessons

/** Local grading. The server regrades from its stored key, so this only drives the UI. */
object LessonGrading {
    private val letters = listOf("A", "B", "C", "D", "E", "F")

    fun letterFor(index: Int): String = letters.getOrElse(index) { "?" }

    fun indexOf(letter: String): Int = letters.indexOf(letter.trim().uppercase())

    fun isCorrect(question: BundleQuestion, answerLetter: String): Boolean =
        answerLetter.trim().equals(question.correctAnswer.trim(), ignoreCase = true)

    fun optionText(question: BundleQuestion, letter: String): String? =
        question.options.getOrNull(indexOf(letter))

    /** Correct / total over [questions] that have been answered, from a lookup of recorded answers. */
    fun score(questions: List<BundleQuestion>, answers: Map<String, StoredAnswer>): Pair<Int, Int> {
        val total = questions.size
        val correct = questions.count { answers[it.id]?.correct == true }
        return correct to total
    }
}
