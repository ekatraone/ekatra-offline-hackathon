package org.ekatra.alfred.lessons

interface LearnerSyncApi {
    suspend fun requestLesson(topic: String, language: String, clientRequestId: String): ApiResult<RequestLessonResult>
    suspend fun fetchBundle(courseId: String): ApiResult<LessonBundle>
    suspend fun postEvents(events: List<LearnerEvent>): ApiResult<EventsResponse>
    suspend fun getMe(): ApiResult<MeResponse>
}
