package org.ekatra.alfred.viewmodel

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.ekatra.alfred.data.local.UserProfileDao
import org.ekatra.alfred.data.model.UserProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val auth = mockk<FirebaseAuth>()
    private val user = mockk<FirebaseUser>()
    private val dao = mockk<UserProfileDao>()
    private val profile = UserProfile(uid = "u1", name = "Sara", country = "India", grade = "")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { auth.currentUser } returns user
        every { user.uid } returns "u1"
        every { user.email } returns "sara@example.com"
        every { user.phoneNumber } returns null
        coEvery { dao.getProfile("u1") } returns profile
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ProfileViewModel(auth, mockk(relaxed = true), dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))

    /**
     * Settings > Profile opens ProfileActivity in edit mode, which treats AUTHENTICATED as
     * "saved, go back" and finishes. A signed-in user with a local profile must therefore reach
     * EDIT_PROFILE without ever passing through AUTHENTICATED.
     */
    @Test
    fun `edit mode reaches EDIT_PROFILE and never AUTHENTICATED for a signed-in user`() = runTest(dispatcher) {
        val vm = viewModel()
        val seen = mutableListOf<AuthStep>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.uiState.collect { seen += it.authStep }
        }

        vm.loadExistingProfile()
        advanceUntilIdle()

        assertFalse("AUTHENTICATED was emitted in edit mode: $seen", AuthStep.AUTHENTICATED in seen)
        assertEquals(AuthStep.EDIT_PROFILE, vm.uiState.value.authStep)
        assertEquals("Sara", vm.uiState.value.name)
    }

    @Test
    fun `creating the view model does not sign anyone in by itself`() = runTest(dispatcher) {
        val vm = viewModel()
        advanceUntilIdle()

        assertEquals(AuthStep.EMAIL_INPUT, vm.uiState.value.authStep)
    }

    @Test
    fun `login flow still resumes a signed-in user with a local profile`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.checkExistingAuth()
        advanceUntilIdle()

        assertEquals(AuthStep.AUTHENTICATED, vm.uiState.value.authStep)
        assertEquals(profile, vm.uiState.value.userProfile)
    }

    @Test
    fun `checkExistingAuth runs once per view model`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.checkExistingAuth()
        vm.checkExistingAuth()
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { dao.getProfile("u1") }
    }
}
