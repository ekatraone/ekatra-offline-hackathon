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
}
