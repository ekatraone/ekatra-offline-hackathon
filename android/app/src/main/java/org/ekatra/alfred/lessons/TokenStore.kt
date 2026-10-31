package org.ekatra.alfred.lessons

import android.content.SharedPreferences

/** Where the learner JWT obtained from the server is kept. */
interface TokenStore {
    fun token(): String?
    fun learnerId(): String?
    fun save(token: String, learnerId: String?)
    /** Drop the token but remember which learner it was for. */
    fun clearToken()
    fun clearAll()

    /**
     * Tokens kept ONLY so a server-side erase that could not be sent (offline, server down) can be retried
     * after the account is gone. A SET, so a second account deletion can never drop the first one's token.
     * They survive [clearAll] and are never used for anything else.
     */
    fun pendingDeleteTokens(): Set<String>
    fun addPendingDeleteToken(token: String)
    fun removePendingDeleteToken(token: String)

    /**
     * The Firebase uid of an account whose server-side erase had to go by ID token (no learner jwt existed)
     * and could not be sent. An ID token lasts an hour and cannot be kept, so a retry needs this user to still
     * exist; the uid makes sure it is never aimed at a different person who signed in later.
     */
    fun pendingIdEraseUid(): String?
    fun setPendingIdEraseUid(uid: String?)
}

/**
 * App-private SharedPreferences (not readable by other apps). The token is a 30-day learner JWT that
 * only reaches lesson progress, which is why plain private storage was chosen over adding the
 * security-crypto dependency.
 */
class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override fun token(): String? = prefs.getString(KEY_TOKEN, null)
    override fun learnerId(): String? = prefs.getString(KEY_LEARNER, null)

    override fun save(token: String, learnerId: String?) {
        val edit = prefs.edit()
        edit.putString(KEY_TOKEN, token)
        if (learnerId != null) edit.putString(KEY_LEARNER, learnerId)
        edit.commit()
    }

    override fun clearToken() { prefs.edit().remove(KEY_TOKEN).commit() }
    override fun clearAll() { prefs.edit().remove(KEY_TOKEN).remove(KEY_LEARNER).commit() }

    override fun pendingDeleteTokens(): Set<String> = prefs.getStringSet(KEY_PENDING_DELETE, null)?.toSet() ?: emptySet()

    @Synchronized
    override fun addPendingDeleteToken(token: String) {
        prefs.edit().putStringSet(KEY_PENDING_DELETE, pendingDeleteTokens() + token).commit()
    }

    @Synchronized
    override fun removePendingDeleteToken(token: String) {
        prefs.edit().putStringSet(KEY_PENDING_DELETE, pendingDeleteTokens() - token).commit()
    }

    override fun pendingIdEraseUid(): String? = prefs.getString(KEY_PENDING_ID_ERASE, null)
    override fun setPendingIdEraseUid(uid: String?) {
        val edit = prefs.edit()
        if (uid == null) edit.remove(KEY_PENDING_ID_ERASE) else edit.putString(KEY_PENDING_ID_ERASE, uid)
        edit.commit()
    }

    companion object {
        const val FILE = "lessons_auth"
        private const val KEY_TOKEN = "joined_token"
        private const val KEY_LEARNER = "joined_learner_id"
        private const val KEY_PENDING_DELETE = "pending_server_delete_tokens"
        private const val KEY_PENDING_ID_ERASE = "pending_server_erase_uid"
    }
}
