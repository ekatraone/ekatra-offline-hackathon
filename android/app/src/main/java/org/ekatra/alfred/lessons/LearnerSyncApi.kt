package org.ekatra.alfred.lessons

interface LearnerSyncApi {
    suspend fun requestLesson(topic: String, language: String, clientRequestId: String): ApiResult<RequestLessonResult>
    suspend fun fetchBundle(courseId: String): ApiResult<LessonBundle>
    suspend fun postEvents(events: List<LearnerEvent>): ApiResult<EventsResponse>
    suspend fun getMe(): ApiResult<MeResponse>

    /**
     * Download a lesson image. The media endpoint is public, so this sends NO Authorization header
     * (the token must never go to whatever host an image URL names). Ok holds the JPEG bytes.
     */
    suspend fun downloadImage(url: String): ApiResult<ByteArray>

    /** Trade a Firebase ID token for a learner JWT. Public: sends no Authorization header. */
    suspend fun exchange(idToken: String): ExchangeOutcome

    /**
     * Erase this learner's server-side data (account deletion). Uses [token] as given, not the current
     * one, because a retry may run after the account is gone and a different person has signed in.
     */
    suspend fun deleteMe(token: String): DeleteOutcome

    /**
     * Erase the server-side learner of a Firebase user who has no learner jwt (they never opened Lessons on
     * this phone). `DELETE /api/sync/auth/account` with `{ idToken }`; 200 returns `{ deleted: true|false }`,
     * and both mean there is nothing left. Public: no Authorization header.
     */
    suspend fun deleteAccountByIdToken(idToken: String): DeleteOutcome
}

/** The public WhatsApp one-time-code endpoints. */
interface PhoneAuthApi {
    suspend fun whatsappStart(phone: String): StartOutcome
    suspend fun whatsappVerify(phone: String, code: String): VerifyOutcome
}
