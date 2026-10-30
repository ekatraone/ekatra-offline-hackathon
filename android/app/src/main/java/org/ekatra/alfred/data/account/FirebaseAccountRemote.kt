package org.ekatra.alfred.data.account

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import org.ekatra.alfred.data.local.UserProfileDao
import org.ekatra.alfred.data.repository.ChatRepository
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

/** Local side of account deletion: chat history and the cached profile row. */
class RoomAccountData @Inject constructor(
    private val chats: ChatRepository,
    private val profiles: UserProfileDao
) : LocalAccountData {

    override suspend fun clear(uid: String) {
        chats.deleteAllSessions()
        profiles.deleteProfile(uid)
    }
}
