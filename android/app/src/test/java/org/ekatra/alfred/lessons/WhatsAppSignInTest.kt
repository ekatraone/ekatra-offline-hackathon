package org.ekatra.alfred.lessons

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppSignInTest {

    private val phoneApi = FakePhoneAuth()
    private val firebase = FakeFirebase(user = false)
    private var signedInCalls = 0

    private fun flow(scope: TestScope) =
        WhatsAppSignIn(phoneApi, firebase, scope) { signedInCalls++ }

    private fun TestScope.signIn() = flow(TestScope(UnconfinedTestDispatcher(testScheduler)))

    // ---- start, then code, then verify ----

    @Test
    fun `the happy path goes phone, then code, then Firebase sign-in`() = runTest {
        val f = signIn()
        assertEquals(WhatsAppStep.PHONE, f.state.value.step)

        f.updatePhone("98765 43210")
        f.sendCode()

        assertEquals(listOf("+919876543210"), phoneApi.starts)
        assertEquals(WhatsAppStep.CODE, f.state.value.step)
        assertEquals("+919876543210", f.state.value.phone)
        assertEquals(600, f.state.value.expiresInSeconds)
        assertFalse(f.state.value.busy)

        f.updateCode("123456")
        f.verify()

        assertEquals(listOf("+919876543210" to "123456"), phoneApi.verifies)
        assertEquals("the custom token from verify is what Firebase signs in with", listOf("custom-token"), firebase.customTokens)
        assertTrue(f.state.value.signedIn)
        assertEquals("sign-in then continues like Google or email", 1, signedInCalls)
    }

    @Test
    fun `the phone is validated as E164 on the phone before anything is sent`() = runTest {
        val f = signIn()
        for (bad in listOf("", "abc", "12", "+0123456789")) {
            f.updatePhone(bad)
            f.sendCode()
            assertEquals(WhatsAppSignIn.BAD_PHONE_MESSAGE, f.state.value.error)
            assertEquals(WhatsAppStep.PHONE, f.state.value.step)
        }
        assertTrue(phoneApi.starts.isEmpty())
    }

    @Test
    fun `the number defaults to plus 91 and a country code is kept`() = runTest {
        val f = signIn()
        f.updatePhone("09876543210"); f.sendCode()
        f.changeNumber()
        f.updatePhone("+44 7700 900123"); f.sendCode()
        assertEquals(listOf("+919876543210", "+447700900123"), phoneApi.starts)
    }

    @Test
    fun `the code box takes digits only, six at most`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()

        f.updateCode("12ab34-5678")
        assertEquals("123456", f.state.value.code)
        f.updateCode("٣٤٥١٢٣")   // Arabic-Indic digits are not something the server accepts
        assertEquals("", f.state.value.code)
        f.updateCode("12 34")
        assertEquals("1234", f.state.value.code)
    }

    @Test
    fun `verify needs all six digits`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()
        f.updateCode("123")
        assertFalse(f.state.value.canSubmitCode)

        f.verify()

        assertEquals(WhatsAppSignIn.BAD_CODE_MESSAGE, f.state.value.error)
        assertTrue(phoneApi.verifies.isEmpty())
        assertTrue(firebase.customTokens.isEmpty())
    }

    // ---- resend timer ----

    @Test
    fun `resend is locked for resendAfterSeconds and counts down`() = runTest {
        phoneApi.startResult = { StartOutcome.Sent(resendAfterSeconds = 30, expiresInSeconds = 600) }
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()

        assertEquals(30, f.state.value.resendIn)
        assertFalse(f.state.value.canResend)
        f.resend()
        assertEquals("a locked resend does nothing", 1, phoneApi.starts.size)

        advanceTimeBy(10_500)
        assertEquals(20, f.state.value.resendIn)
        advanceTimeBy(19_000)
        assertEquals(1, f.state.value.resendIn)
        assertFalse(f.state.value.canResend)
        advanceTimeBy(1_000)
        assertEquals(0, f.state.value.resendIn)
        assertTrue(f.state.value.canResend)
    }

    @Test
    fun `resend sends again to the same number, clears the old code and restarts the timer`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()
        f.updateCode("111111")
        advanceTimeBy(31_000)
        assertTrue(f.state.value.canResend)

        f.resend()

        assertEquals(listOf("+919876543210", "+919876543210"), phoneApi.starts)
        assertEquals("", f.state.value.code)
        assertEquals(30, f.state.value.resendIn)
        assertFalse(f.state.value.canResend)
    }

    @Test
    fun `the server's resendAfterSeconds is honoured`() = runTest {
        phoneApi.startResult = { StartOutcome.Sent(resendAfterSeconds = 5, expiresInSeconds = 600) }
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()
        assertEquals(5, f.state.value.resendIn)
        advanceTimeBy(5_001)
        assertTrue(f.state.value.canResend)
    }

    @Test
    fun `changing the number stops the timer and returns to the phone box with the text kept`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode()

        f.changeNumber()

        assertEquals(WhatsAppStep.PHONE, f.state.value.step)
        assertEquals("9876543210", f.state.value.phoneInput)
        assertNull(f.state.value.phone)
        assertEquals(0, f.state.value.resendIn)
        advanceTimeBy(60_000)
        assertEquals(0, f.state.value.resendIn)
    }

    // ---- error paths ----

    @Test
    fun `start errors`() = runTest {
        val cases = listOf(
            StartOutcome.BadPhone("phone must be E.164") to WhatsAppSignIn.BAD_PHONE_MESSAGE,
            StartOutcome.RateLimited to "Too many tries — wait a bit",
            StartOutcome.NetworkError("timeout") to WhatsAppSignIn.OFFLINE_MESSAGE,
            StartOutcome.NotConfigured to "Sign-in with WhatsApp isn't set up on this build."
        )
        for ((outcome, message) in cases) {
            val f = signIn()
            phoneApi.startResult = { outcome }
            f.updatePhone("9876543210"); f.sendCode()
            assertEquals(message, f.state.value.error)
            assertEquals(WhatsAppStep.PHONE, f.state.value.step)
            assertFalse(f.state.value.busy)
        }
    }

    @Test
    fun `verify errors`() = runTest {
        val cases = listOf(
            VerifyOutcome.WrongCode to "That code didn't work",
            VerifyOutcome.RateLimited to "Too many tries — wait a bit",
            VerifyOutcome.NetworkError("down") to WhatsAppSignIn.OFFLINE_MESSAGE,
            VerifyOutcome.MalformedCode to WhatsAppSignIn.BAD_CODE_MESSAGE
        )
        for ((outcome, message) in cases) {
            val f = signIn()
            f.updatePhone("9876543210"); f.sendCode(); f.updateCode("123456")
            phoneApi.verifyResult = { _, _ -> outcome }

            f.verify()

            assertEquals(message, f.state.value.error)
            assertEquals("stays on the code step", WhatsAppStep.CODE, f.state.value.step)
            assertFalse(f.state.value.signedIn); assertFalse(f.state.value.busy)
            assertTrue(firebase.customTokens.isEmpty())
        }
        assertEquals(0, signedInCalls)
    }

    @Test
    fun `a wrong code can be retyped and tried again`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode(); f.updateCode("000000")
        phoneApi.verifyResult = { _, code -> if (code == "123456") VerifyOutcome.Verified("ct") else VerifyOutcome.WrongCode }
        f.verify()
        assertEquals("That code didn't work", f.state.value.error)

        f.updateCode("123456")
        assertNull("typing clears the error", f.state.value.error)
        f.verify()

        assertTrue(f.state.value.signedIn)
    }

    @Test
    fun `if Firebase refuses the custom token the sign-in does not complete`() = runTest {
        firebase.customTokenSignIn = false
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode(); f.updateCode("123456")

        f.verify()

        assertEquals(listOf("custom-token"), firebase.customTokens)
        assertEquals(WhatsAppSignIn.FIREBASE_FAILED_MESSAGE, f.state.value.error)
        assertFalse(f.state.value.signedIn)
        assertEquals(0, signedInCalls)
    }

    @Test
    fun `a request already in flight is not started twice`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<StartOutcome>()
        val slow = object : PhoneAuthApi {
            var starts = 0
            override suspend fun whatsappStart(phone: String): StartOutcome { starts++; return gate.await() }
            override suspend fun whatsappVerify(phone: String, code: String) = VerifyOutcome.WrongCode
        }
        val f = WhatsAppSignIn(slow, firebase, TestScope(UnconfinedTestDispatcher(testScheduler))) {}
        f.updatePhone("9876543210")

        f.sendCode(); f.sendCode(); f.sendCode()

        assertEquals(1, slow.starts)
        assertTrue(f.state.value.busy)
        gate.complete(StartOutcome.Sent(30, 600))
        assertFalse(f.state.value.busy)
    }

    @Test
    fun `reset forgets everything`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.sendCode(); f.updateCode("12")

        f.reset()

        assertEquals(WhatsAppState(), f.state.value)
    }
}
