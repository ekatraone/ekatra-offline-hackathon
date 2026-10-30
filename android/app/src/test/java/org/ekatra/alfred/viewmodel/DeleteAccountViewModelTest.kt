package org.ekatra.alfred.viewmodel

import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserInfo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.ekatra.alfred.data.account.AccountDeleter
import org.ekatra.alfred.data.account.DeletionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalCoroutinesApi::class)
class DeleteAccountViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val deleter = mockk<AccountDeleter>()
    private val user = mockk<FirebaseUser>()
    private val auth = mockk<FirebaseAuth>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.currentUser } returns user
        every { user.uid } returns "uid1"
    }

    @After
    fun tearDown() {
        unmockkStatic(GoogleAuthProvider::class)
        unmockkStatic("kotlinx.coroutines.tasks.TasksKt")
        Dispatchers.resetMain()
    }

    private fun userWithProvider(providerId: String) {
        val info = mockk<UserInfo>()
        every { info.providerId } returns providerId
        every { user.providerData } returns listOf(info)
    }

    /** Makes the mocked deleter ask for re-authentication once and record the answer. */
    private fun deleterReauthenticates(answer: (Boolean) -> Unit) {
        coEvery { deleter.delete("uid1", any()) } coAnswers {
            val ok = secondArg<suspend () -> Boolean>().invoke()
            answer(ok)
            if (ok) DeletionResult.Deleted
            else DeletionResult.Failed(AccountDeleter.Step.REAUTHENTICATION, RuntimeException("no"))
        }
    }

    @Test
    fun `google user reauthenticates with the id token and deletion succeeds`() {
        mockkStatic(GoogleAuthProvider::class)
        mockkStatic("kotlinx.coroutines.tasks.TasksKt")
        userWithProvider(GoogleAuthProvider.PROVIDER_ID)
        val credential = mockk<AuthCredential>()
        val task = mockk<Task<Void>>()
        every { GoogleAuthProvider.getCredential("token-1", null) } returns credential
        every { user.reauthenticate(credential) } returns task
        coEvery { task.await() } returns mockk<Void>()
        var reauthenticated: Boolean? = null
        deleterReauthenticates { reauthenticated = it }
        val vm = DeleteAccountViewModel(deleter, auth)

        vm.onConfirm()
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.uiState.value.needsGoogleSignIn)
        vm.submitGoogleIdToken("token-1")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(true, reauthenticated)
        verify(exactly = 1) { user.reauthenticate(credential) }
        assertFalse(vm.uiState.value.needsGoogleSignIn)
        assertTrue(vm.uiState.value.deleted)
    }

    @Test
    fun `google sign-in cancelled fails re-authentication without calling firebase`() {
        mockkStatic(GoogleAuthProvider::class)
        userWithProvider(GoogleAuthProvider.PROVIDER_ID)
        var reauthenticated: Boolean? = null
        deleterReauthenticates { reauthenticated = it }
        val vm = DeleteAccountViewModel(deleter, auth)

        vm.onConfirm()
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitGoogleIdToken(null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, reauthenticated)
        verify(exactly = 0) { user.reauthenticate(any()) }
        assertEquals(AccountDeleter.Step.REAUTHENTICATION, vm.uiState.value.failedStep)
    }

    @Test
    fun `an unsupported provider fails re-authentication without any prompt`() {
        userWithProvider("phone")
        var reauthenticated: Boolean? = null
        deleterReauthenticates { reauthenticated = it }
        val vm = DeleteAccountViewModel(deleter, auth)

        vm.onConfirm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(false, reauthenticated)
        assertFalse(vm.uiState.value.needsGoogleSignIn)
        assertFalse(vm.uiState.value.needsPassword)
        assertEquals(AccountDeleter.Step.REAUTHENTICATION, vm.uiState.value.failedStep)
    }

    @Test
    fun `requesting deletion only opens the dialog`() {
        val vm = DeleteAccountViewModel(deleter, auth)

        vm.onDeleteRequested()

        assertTrue(vm.uiState.value.confirming)
        assertFalse(vm.uiState.value.inProgress)
        coVerify(exactly = 0) { deleter.delete(any(), any()) }
    }

    @Test
    fun `cancel closes the dialog and deletes nothing`() {
        val vm = DeleteAccountViewModel(deleter, auth)
        vm.onDeleteRequested()

        vm.onCancel()
        dispatcher.scheduler.advanceUntilIdle()

        assertFalse(vm.uiState.value.confirming)
        coVerify(exactly = 0) { deleter.delete(any(), any()) }
    }

    @Test
    fun `confirm starts deletion and marks the account deleted on success`() {
        val vm = DeleteAccountViewModel(deleter, auth)
        io.mockk.coEvery { deleter.delete("uid1", any()) } returns DeletionResult.Deleted
        vm.onDeleteRequested()

        vm.onConfirm()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.uiState.value.deleted)
        assertFalse(vm.uiState.value.inProgress)
        coVerify(exactly = 1) { deleter.delete("uid1", any()) }
    }

    @Test
    fun `a failed step is shown and the dialog stays closed`() {
        val vm = DeleteAccountViewModel(deleter, auth)
        io.mockk.coEvery { deleter.delete("uid1", any()) } returns
            DeletionResult.Failed(AccountDeleter.Step.PROFILE, RuntimeException("denied"))

        vm.onConfirm()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(AccountDeleter.Step.PROFILE, vm.uiState.value.failedStep)
        assertFalse(vm.uiState.value.deleted)
        assertFalse(vm.uiState.value.confirming)
    }
}
