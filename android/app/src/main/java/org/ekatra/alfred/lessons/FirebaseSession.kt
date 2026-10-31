package org.ekatra.alfred.lessons

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** The few Firebase Auth operations the learner sign-in needs, behind an interface so tests need no Firebase. */
interface FirebaseSession {
    fun hasUser(): Boolean

    /** The signed-in user's Firebase uid, or null. */
    fun uid(): String?

    /** A Firebase ID token for the signed-in user, or null if nobody is signed in or it cannot be fetched. */
    suspend fun idToken(forceRefresh: Boolean): String?

    /** @return true if the custom token signed the user in. */
    suspend fun signInWithCustomToken(customToken: String): Boolean

    fun signOut()
}

@Singleton
class FirebaseAuthSession @Inject constructor(private val auth: FirebaseAuth) : FirebaseSession {
    override fun hasUser(): Boolean = auth.currentUser != null
    override fun uid(): String? = auth.currentUser?.uid

    override suspend fun idToken(forceRefresh: Boolean): String? = try {
        auth.currentUser?.getIdToken(forceRefresh)?.await()?.token
    } catch (e: Exception) {
        null
    }

    override suspend fun signInWithCustomToken(customToken: String): Boolean = try {
        auth.signInWithCustomToken(customToken).await().user != null
    } catch (e: Exception) {
        false
    }

    override fun signOut() = auth.signOut()
}
