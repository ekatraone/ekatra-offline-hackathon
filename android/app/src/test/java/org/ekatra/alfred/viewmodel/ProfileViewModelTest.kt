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
import org.junit.Assert.assertTrue
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

    private val hooks = mockk<org.ekatra.alfred.lessons.LearnerSessionHooks>(relaxed = true)
    private val phoneApi = org.ekatra.alfred.lessons.FakePhoneAuth()
    private val session = org.ekatra.alfred.lessons.FakeFirebase(user = false)

    private fun viewModel() = ProfileViewModel(
        auth, mockk(relaxed = true), dao, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
        phoneApi, session, hooks, org.ekatra.alfred.lessons.acceptedConsent()
    )

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

    // ---- Continue with WhatsApp number ----

    @Test
    fun `the WhatsApp screens open and close without signing anyone in`() = runTest(dispatcher) {
        val vm = viewModel()

        vm.openWhatsApp()
        assertTrue(vm.uiState.value.showWhatsApp)
        assertEquals(AuthStep.EMAIL_INPUT, vm.uiState.value.authStep)

        vm.closeWhatsApp()
        assertFalse(vm.uiState.value.showWhatsApp)
        assertTrue(session.customTokens.isEmpty())
    }

    @Test
    fun `a verified code signs in to Firebase with the custom token and then carries on like any sign-in`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openWhatsApp()
        vm.whatsApp.updatePhone("98765 43210")
        vm.whatsApp.submitPhone(); vm.whatsApp.sendCode()
        advanceUntilIdle()
        vm.whatsApp.updateCode("123456")

        vm.whatsApp.verify()
        advanceUntilIdle()

        assertEquals(listOf("custom-token"), session.customTokens)
        assertFalse("the WhatsApp screens are done", vm.uiState.value.showWhatsApp)
        // The signed-in user has a local profile, so it ends where Google or email sign-in would.
        assertEquals(AuthStep.AUTHENTICATED, vm.uiState.value.authStep)
        assertEquals(profile, vm.uiState.value.userProfile)
        io.mockk.coVerify(exactly = 1) { hooks.afterSignIn() }
    }

    @Test
    fun `a wrong code stays on the WhatsApp screens and signs nobody in`() = runTest(dispatcher) {
        phoneApi.verifyResult = { _, _ -> org.ekatra.alfred.lessons.VerifyOutcome.WrongCode }
        val vm = viewModel()
        vm.openWhatsApp()
        vm.whatsApp.updatePhone("9876543210"); vm.whatsApp.submitPhone(); vm.whatsApp.sendCode(); advanceUntilIdle()
        vm.whatsApp.updateCode("000000"); vm.whatsApp.verify(); advanceUntilIdle()

        assertTrue(vm.uiState.value.showWhatsApp)
        assertEquals("That code didn't work", vm.whatsApp.state.value.error)
        assertEquals(AuthStep.EMAIL_INPUT, vm.uiState.value.authStep)
        assertTrue(session.customTokens.isEmpty())
        io.mockk.coVerify(exactly = 0) { hooks.afterSignIn() }
    }

    @Test
    fun `reopening the WhatsApp screens starts from a clean slate`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.openWhatsApp()
        vm.whatsApp.updatePhone("9876543210"); vm.whatsApp.submitPhone(); vm.whatsApp.sendCode(); advanceUntilIdle()
        vm.closeWhatsApp()

        vm.openWhatsApp()

        assertEquals(org.ekatra.alfred.lessons.WhatsAppState(), vm.whatsApp.state.value)
    }

    // ---- the other sign-in methods trigger the exchange too ----

    @Test
    fun `resuming a session does not exchange, but signing in with Google does`() = runTest(dispatcher) {
        val vm = viewModel()
        vm.checkExistingAuth(); advanceUntilIdle()
        io.mockk.coVerify(exactly = 0) { hooks.afterSignIn() }

        // continueAfterAuth is what every sign-in method ends in; the Google path reaches it through signInWithCredential.
        val googleVm = viewModel()
        every { auth.signInWithCredential(any()) } returns com.google.android.gms.tasks.Tasks.forResult(
            mockk<com.google.firebase.auth.AuthResult> { every { this@mockk.user } returns this@ProfileViewModelTest.user }
        )
        googleVm.signInWithGoogle("google-id-token")
        advanceUntilIdle()

        io.mockk.coVerify(exactly = 1) { hooks.afterSignIn() }
    }

    // ---- sign-out ----

    @Test
    fun `signing out of Firebase clears the lessons token and data too`() = runTest(dispatcher) {
        every { auth.signOut() } returns Unit
        coEvery { dao.deleteProfile(any()) } returns Unit
        val vm = viewModel()

        vm.signOut()
        advanceUntilIdle()

        io.mockk.verify { auth.signOut() }
        io.mockk.coVerify(exactly = 1) { hooks.afterSignOut() }
    }
}
