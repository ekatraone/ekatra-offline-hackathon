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
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class WhatsAppSignInTest {

    private val phoneApi = FakePhoneAuth()
    private val firebase = FakeFirebase(user = false)
    private var signedInCalls = 0

    private var consent = acceptedConsent()

    private fun flow(scope: TestScope) =
        WhatsAppSignIn(phoneApi, firebase, consent, scope) { signedInCalls++ }

    private fun TestScope.signIn() = flow(TestScope(UnconfinedTestDispatcher(testScheduler)))

    // ---- start, then code, then verify ----

    @Test
    fun `the happy path goes phone, then code, then Firebase sign-in`() = runTest {
        val f = signIn()
        assertEquals(WhatsAppStep.PHONE, f.state.value.step)

        f.updatePhone("98765 43210")
        f.submitPhone(); f.sendCode()

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
            f.submitPhone(); f.sendCode()
            assertEquals(WhatsAppSignIn.BAD_PHONE_MESSAGE, f.state.value.error)
            assertEquals(WhatsAppStep.PHONE, f.state.value.step)
        }
        assertTrue(phoneApi.starts.isEmpty())
    }

    @Test
    fun `the number defaults to plus 91 and a country code is kept`() = runTest {
        val f = signIn()
        f.updatePhone("09876543210"); f.submitPhone(); f.sendCode()
        f.changeNumber()
        f.updatePhone("+44 7700 900123"); f.submitPhone(); f.sendCode()
        assertEquals(listOf("+919876543210", "+447700900123"), phoneApi.starts)
    }

    @Test
    fun `the code box takes digits only, six at most`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()

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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()
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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()

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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()
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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()
        assertEquals(5, f.state.value.resendIn)
        advanceTimeBy(5_001)
        assertTrue(f.state.value.canResend)
    }

    @Test
    fun `changing the number stops the timer and returns to the phone box with the text kept`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()

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
            // The number itself was refused: back to the number box. The rest are about the attempt, so the
            // confirmation stays and the person can tap Send code again or Edit.
            Triple(StartOutcome.BadPhone("phone must be E.164"), WhatsAppSignIn.BAD_PHONE_MESSAGE, WhatsAppStep.PHONE),
            Triple(StartOutcome.RateLimited, "Too many tries — wait a bit", WhatsAppStep.CONFIRM),
            Triple(StartOutcome.NetworkError("timeout"), WhatsAppSignIn.OFFLINE_MESSAGE, WhatsAppStep.CONFIRM),
            Triple(StartOutcome.NotConfigured, "Sign-in with WhatsApp isn't set up on this build.", WhatsAppStep.CONFIRM)
        )
        for ((outcome, message, step) in cases) {
            val f = signIn()
            phoneApi.startResult = { outcome }
            f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()
            assertEquals(message, f.state.value.error)
            assertEquals(step, f.state.value.step)
            assertFalse(f.state.value.busy)
        }
    }

    @Test
    fun `after a failed send the confirmation can be tried again`() = runTest {
        val f = signIn()
        phoneApi.startResult = { StartOutcome.NetworkError("down") }
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode()
        assertEquals(WhatsAppSignIn.OFFLINE_MESSAGE, f.state.value.error)

        phoneApi.startResult = { StartOutcome.Sent(30, 600) }
        f.sendCode()

        assertEquals(WhatsAppStep.CODE, f.state.value.step)
        assertNull(f.state.value.error)
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
            f.updatePhone("9876543210"); f.submitPhone(); f.sendCode(); f.updateCode("123456")
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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode(); f.updateCode("000000")
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
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode(); f.updateCode("123456")

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
        val f = WhatsAppSignIn(slow, firebase, consent, TestScope(UnconfinedTestDispatcher(testScheduler))) {}
        f.updatePhone("9876543210")

        f.submitPhone(); f.sendCode(); f.submitPhone(); f.sendCode(); f.submitPhone(); f.sendCode()

        assertEquals(1, slow.starts)
        assertTrue(f.state.value.busy)
        gate.complete(StartOutcome.Sent(30, 600))
        assertFalse(f.state.value.busy)
    }

    @Test
    fun `reset forgets everything`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone(); f.sendCode(); f.updateCode("12")

        f.reset()

        assertEquals(WhatsAppState(), f.state.value)
    }

    // ---- confirm the number before anything is sent ----

    @Test
    fun `the normalised number is read back and nothing is sent until it is confirmed`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210")

        f.submitPhone()

        assertEquals(WhatsAppStep.CONFIRM, f.state.value.step)
        assertEquals("+919876543210", f.state.value.phone)
        assertEquals("+91 98765 43210", f.state.value.phoneDisplay)
        assertTrue("nothing sent yet", phoneApi.starts.isEmpty())

        f.sendCode()

        assertEquals(listOf("+919876543210"), phoneApi.starts)
        assertEquals(WhatsAppStep.CODE, f.state.value.step)
    }

    @Test
    fun `a ten digit number with no country code is shown as plus 91 so a wrong guess can be caught`() = runTest {
        val f = signIn()
        f.updatePhone("4155550123")  // really a US number typed without +1
        f.submitPhone()

        assertEquals("+91 41555 50123", f.state.value.phoneDisplay)
        f.edit()
        assertEquals("the person can fix it", "4155550123", f.state.value.phoneInput)
        assertTrue(phoneApi.starts.isEmpty())
    }

    @Test
    fun `Edit goes back to the number box with the text kept and sends nothing`() = runTest {
        val f = signIn()
        f.updatePhone("98765 43210"); f.submitPhone()

        f.edit()

        assertEquals(WhatsAppStep.PHONE, f.state.value.step)
        assertEquals("98765 43210", f.state.value.phoneInput)
        assertNull(f.state.value.phone)
        assertTrue(phoneApi.starts.isEmpty())
    }

    @Test
    fun `sending is impossible from the number box, it needs the confirmation tap`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210")

        f.sendCode()   // not submitted: still on the number step

        assertTrue(phoneApi.starts.isEmpty())
        assertEquals(WhatsAppStep.PHONE, f.state.value.step)
    }

    @Test
    fun `an invalid number never reaches the confirmation`() = runTest {
        val f = signIn()
        f.updatePhone("12"); f.submitPhone()
        assertEquals(WhatsAppSignIn.BAD_PHONE_MESSAGE, f.state.value.error)
        assertEquals(WhatsAppStep.PHONE, f.state.value.step)
    }

    @Test
    fun `the number display handles plus 91 and leaves other countries alone`() {
        assertEquals("+91 98765 43210", formatPhoneForDisplay("+919876543210"))
        assertEquals("+447700900123", formatPhoneForDisplay("+447700900123"))
        assertEquals("+14155550123", formatPhoneForDisplay("+14155550123"))
        assertEquals("+91123", formatPhoneForDisplay("+91123"))
    }

    // ---- the Lessons notice comes before the number is sent ----

    @Test
    fun `someone who has not accepted Lessons v2 sees the notice before the number is sent`() = runTest {
        consent = consentAtVersion(1)          // accepted the old screen: no Lessons wording
        val f = signIn()
        f.updatePhone("9876543210")

        f.submitPhone()

        assertTrue(f.state.value.needsConsent)
        assertEquals("still on the number step", WhatsAppStep.PHONE, f.state.value.step)
        assertTrue("nothing sent", phoneApi.starts.isEmpty())
    }

    @Test
    fun `accepting the notice there records it and moves on to the confirmation`() = runTest {
        consent = consentAtVersion(1)
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone()

        f.acceptConsent()

        assertTrue(consent.isAccepted())
        assertFalse(f.state.value.needsConsent)
        assertEquals(WhatsAppStep.CONFIRM, f.state.value.step)
        assertTrue("still nothing sent until Send code", phoneApi.starts.isEmpty())

        f.sendCode()
        assertEquals(listOf("+919876543210"), phoneApi.starts)
    }

    @Test
    fun `never asked is also sent to the notice`() = runTest {
        consent = consentAtVersion(0)
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone()
        assertTrue(f.state.value.needsConsent)
        assertTrue(phoneApi.starts.isEmpty())
    }

    @Test
    fun `with Lessons v2 already recorded there is no notice`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone()
        assertFalse(f.state.value.needsConsent)
        assertEquals(WhatsAppStep.CONFIRM, f.state.value.step)
    }

    @Test
    fun `if the notice is cleared mid-flow nothing is sent`() = runTest {
        val f = signIn()
        f.updatePhone("9876543210"); f.submitPhone()
        consent.clear()

        f.sendCode()

        assertTrue(phoneApi.starts.isEmpty())
        assertTrue(f.state.value.needsConsent)
        assertEquals(WhatsAppStep.PHONE, f.state.value.step)
    }

    @Test
    fun `an invalid number is refused before the notice is even shown`() = runTest {
        consent = consentAtVersion(1)
        val f = signIn()
        f.updatePhone("abc"); f.submitPhone()
        assertFalse(f.state.value.needsConsent)
        assertEquals(WhatsAppSignIn.BAD_PHONE_MESSAGE, f.state.value.error)
    }

    // ---- the code box ----

    @Test
    fun `the code box asks for a numeric password keypad`() {
        val screen = File("src/main/java/org/ekatra/alfred/ProfileActivity.kt").readText()
        val code = screen.substringAfter("WhatsAppStep.CODE -> OutlinedTextField(").substringBefore("singleLine = true")
        assertTrue(code.contains("KeyboardType.NumberPassword"))
        assertFalse(code.contains("KeyboardType.Number,") && !code.contains("NumberPassword"))
    }
}
