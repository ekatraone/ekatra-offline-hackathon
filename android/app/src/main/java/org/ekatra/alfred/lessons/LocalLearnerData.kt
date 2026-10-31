package org.ekatra.alfred.lessons

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ekatra.alfred.data.local.SyncQueueDao
import javax.inject.Inject
import javax.inject.Singleton

/** The lesson data on this phone that belongs to one learner. Cleared on sign-out or when the learner changes. */
@Singleton
class LocalLearnerData @Inject constructor(
    private val queue: SyncQueueDao,
    private val bundles: LessonStore,
    private val progress: LessonProgressStore
) {
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** Downloaded lessons and their images, local progress, and the unsynced learner rows (not analytics). */
    suspend fun clear() {
        queue.deleteByType(LearnerSyncManager.TYPE_LEARNER_EVENT)
        queue.deleteByType(LearnerSyncManager.TYPE_LESSON_REQUEST)
        withContext(ioDispatcher) {
            bundles.clear()
            progress.clear()
        }
    }
}
