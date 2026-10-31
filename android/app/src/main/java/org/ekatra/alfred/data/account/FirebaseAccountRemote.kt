package org.ekatra.alfred.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.ekatra.alfred.data.AppSettings
import org.ekatra.alfred.data.local.SyncQueueDao
import org.ekatra.alfred.data.local.UserProfileDao
import org.ekatra.alfred.data.repository.ChatRepository
import org.ekatra.alfred.data.sync.SyncManager
import org.ekatra.alfred.lessons.LearnerSyncManager
import org.ekatra.alfred.lessons.LessonProgressStore
import org.ekatra.alfred.lessons.LessonStore
import javax.inject.Inject

/** Firestore and Firebase Auth side of account deletion. */
class FirebaseAccountRemote @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) : AccountRemote {

    override suspend fun deleteAnalytics(uid: String) {
        val docs = firestore.collection("users").document(uid).collection("analytics").get().await()
        for (doc in docs) doc.reference.delete().await()
    }

    override suspend fun deleteProfile(uid: String) {
        firestore.collection("users").document(uid).delete().await()
    }

    override suspend fun deleteAuthUser() {
        val user = auth.currentUser ?: return
        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            throw RecentLoginRequiredException(e)
        }
    }
}

/**
 * Local side of account deletion: chat history, the cached profile row, cached settings, and the
 * learner's lessons (downloaded bundles, local progress, and unsynced quiz answers and lesson requests),
 * and every queued sync row, including analytics. ("Delete Cloud Data" is separate and only clears
 * queued analytics.)
 */
class RoomAccountData @Inject constructor(
    private val chats: ChatRepository,
    private val profiles: UserProfileDao,
    private val settings: AppSettings,
    private val syncQueue: SyncQueueDao,
    private val lessons: LessonStore,
    private val lessonProgress: LessonProgressStore
) : LocalAccountData {

    override suspend fun clear(uid: String) {
        chats.deleteAllSessions()
        profiles.deleteProfile(uid)
        settings.clearAllExceptConsent()
        // Queued analytics too: nothing a deleted account queued may upload under another uid.
        syncQueue.deleteByType(SyncManager.TYPE_CHAT_ANALYTICS)
        syncQueue.deleteByType(LearnerSyncManager.TYPE_LEARNER_EVENT)
        syncQueue.deleteByType(LearnerSyncManager.TYPE_LESSON_REQUEST)
        withContext(Dispatchers.IO) {
            lessons.clear()
            lessonProgress.clear()
        }
    }
}
