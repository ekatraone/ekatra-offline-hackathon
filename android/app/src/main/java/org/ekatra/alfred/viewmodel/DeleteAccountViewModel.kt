package org.ekatra.alfred.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.ekatra.alfred.data.account.AccountDeleter
import org.ekatra.alfred.data.account.DeletionResult
import javax.inject.Inject

/**
 * Drives the Settings "Delete account" flow (milestone 2, criterion 5).
 *
 * The confirm button is the only path that starts deletion. Re-authentication is needed when
 * Firebase reports a recent sign-in. Email users are asked for their password. Google users
 * are not supported yet and fail with an error.
 */
@HiltViewModel
class DeleteAccountViewModel @Inject constructor(
    private val deleter: AccountDeleter,
    private val firebaseAuth: FirebaseAuth
) : ViewModel() {

    data class UiState(
        val confirming: Boolean = false,
        val inProgress: Boolean = false,
        val needsPassword: Boolean = false,
        val failedStep: AccountDeleter.Step? = null,
        val deleted: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var passwordReply: CompletableDeferred<String?>? = null

    /** Opens the confirmation dialog. Does not delete anything. */
    fun onDeleteRequested() {
        _uiState.update { it.copy(confirming = true, failedStep = null) }
    }

    fun onCancel() {
        passwordReply?.complete(null)
        _uiState.update { it.copy(confirming = false, needsPassword = false) }
    }

    /** Starts deletion. Called only from the confirm button. */
    fun onConfirm() {
        if (_uiState.value.inProgress) return
        val uid = firebaseAuth.currentUser?.uid ?: return
        _uiState.update { it.copy(confirming = false, inProgress = true, failedStep = null) }
        viewModelScope.launch {
            val result = deleter.delete(uid) { reauthenticateWithPassword() }
            _uiState.update {
                when (result) {
                    DeletionResult.Deleted -> it.copy(inProgress = false, deleted = true)
                    is DeletionResult.Failed -> it.copy(inProgress = false, failedStep = result.step)
                }
            }
        }
    }

    /** Called by the password field after a re-authentication request. */
    fun submitPassword(password: String) {
        passwordReply?.complete(password)
    }

    private suspend fun reauthenticateWithPassword(): Boolean {
        val user = firebaseAuth.currentUser ?: return false
        val isEmailUser = user.providerData.any { it.providerId == EmailAuthProvider.PROVIDER_ID }
        if (!isEmailUser) return false
        val email = user.email ?: return false

        val reply = CompletableDeferred<String?>()
        passwordReply = reply
        _uiState.update { it.copy(needsPassword = true) }
        val password = reply.await()
        _uiState.update { it.copy(needsPassword = false) }
        if (password.isNullOrEmpty()) return false

        return try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
            true
        } catch (e: Exception) {
            false
        }
    }
}
