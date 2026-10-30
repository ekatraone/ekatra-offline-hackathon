package org.ekatra.alfred.viewmodel

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
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
        Dispatchers.resetMain()
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
