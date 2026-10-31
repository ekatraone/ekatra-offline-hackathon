package org.ekatra.alfred.lessons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WhatsAppStep {
    /** Type the number. */
    PHONE,
    /** Read the normalised number back and tap to confirm before anything is sent. */
    CONFIRM,
    /** Type the 6-digit code. */
    CODE
}

data class WhatsAppState(
    val step: WhatsAppStep = WhatsAppStep.PHONE,
    /** What the person typed. */
    val phoneInput: String = "",
    /** The E.164 number: shown for confirmation, then the one the code is sent to. */
    val phone: String? = null,
    /** The Lessons notice has to be accepted before the number is sent anywhere (shown on this screen). */
    val needsConsent: Boolean = false,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Seconds until "Resend" works; 0 means it works now. */
    val resendIn: Int = 0,
    val expiresInSeconds: Int = 0,
    /** Firebase accepted the custom token: the screen is done and sign-in continues as for Google or email. */
    val signedIn: Boolean = false
) {
    /** The number as the person should read it back, e.g. "+91 98765 43210". */
    val phoneDisplay: String? get() = phone?.let(::formatPhoneForDisplay)
    val canResend: Boolean get() = step == WhatsAppStep.CODE && resendIn == 0 && !busy && !signedIn
    val canSubmitCode: Boolean get() = step == WhatsAppStep.CODE && code.length == WhatsAppSignIn.CODE_LENGTH && !busy && !signedIn
}

/** "+919876543210" as "+91 98765 43210". Other countries are shown as they are: their code length varies. */
fun formatPhoneForDisplay(e164: String): String =
    if (e164.startsWith("+91") && e164.length == 13) "+91 ${e164.substring(3, 8)} ${e164.substring(8)}" else e164

/**
 * Sign in with a one-time code sent on WhatsApp: phone, a confirmation of the number, then the code, then
 * Firebase `signInWithCustomToken`. The Lessons notice is shown first if it has not been accepted, because
 * sending the number to Ekatra's server is part of Lessons.
 * Driven from a [scope] (the ViewModel's), so the resend countdown runs in whatever clock that scope uses.
 *
 * @param onSignedIn runs after Firebase has signed the user in; the caller carries on exactly as it
 *   does after a Google or email sign-in.
 */
class WhatsAppSignIn(
    private val api: PhoneAuthApi,
    private val firebase: FirebaseSession,
    private val consent: LessonsConsent,
    private val scope: CoroutineScope,
    private val onSignedIn: suspend () -> Unit
) {
    companion object {
        const val CODE_LENGTH = 6
        const val OFFLINE_MESSAGE = "You seem to be offline. Connect to the internet and try again."
        const val BAD_PHONE_MESSAGE = "Enter a valid WhatsApp number, for example 98765 43210, or +44 7700 900123 for another country."
        const val WRONG_CODE_MESSAGE = "That code didn't work"
        const val RATE_LIMITED_MESSAGE = "Too many tries — wait a bit"
        const val BAD_CODE_MESSAGE = "Enter the 6-digit code"
        const val FIREBASE_FAILED_MESSAGE = "Couldn't sign you in. Please try again."
    }

    private val _state = MutableStateFlow(WhatsAppState())
    val state: StateFlow<WhatsAppState> = _state.asStateFlow()

    private var countdown: Job? = null

    fun updatePhone(input: String) = _state.update { it.copy(phoneInput = input, error = null) }

    /** ASCII digits only, at most six; anything else typed or pasted is dropped. */
    fun updateCode(input: String) = _state.update { it.copy(code = input.filter { it in '0'..'9' }.take(CODE_LENGTH), error = null) }

    /**
     * "Continue" from the number box: check the number, then (if the Lessons notice is outstanding) show it,
     * then read the number back for confirmation. Nothing is sent yet.
     */
    fun submitPhone() {
        val s = _state.value
        if (s.busy) return
        val phone = normalizePhone(s.phoneInput)
        if (phone == null) { _state.update { it.copy(error = BAD_PHONE_MESSAGE) }; return }
        _state.update {
            if (consent.isAccepted()) it.copy(phone = phone, step = WhatsAppStep.CONFIRM, needsConsent = false, error = null)
            else it.copy(phone = phone, needsConsent = true, error = null)
        }
    }

    /** Accept the Lessons notice shown on this screen, then carry on to the confirmation. */
    fun acceptConsent() {
        consent.accept()
        _state.update { if (it.phone != null) it.copy(needsConsent = false, step = WhatsAppStep.CONFIRM) else it.copy(needsConsent = false) }
    }

    /** "Edit": back to the number box with what was typed. */
    fun edit() {
        countdown?.cancel()
        _state.update { WhatsAppState(phoneInput = it.phoneInput) }
    }

    /** The confirmed number: ask for a code. Only works from the confirmation step. */
    fun sendCode() {
        val s = _state.value
        if (s.busy || s.step != WhatsAppStep.CONFIRM) return
        request(s.phone ?: return, isResend = false)
    }

    /** Ask again, once the countdown allows it. */
    fun resend() {
        val s = _state.value
        if (!s.canResend) return
        request(s.phone ?: return, isResend = true)
    }

    private fun request(phone: String, isResend: Boolean) {
        // Defensive: the notice may have been cleared mid-flow. Nothing is sent without it.
        if (!consent.isAccepted()) { _state.update { it.copy(step = WhatsAppStep.PHONE, needsConsent = true) }; return }
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val r = api.whatsappStart(phone)) {
                is StartOutcome.Sent -> {
                    _state.update {
                        it.copy(
                            step = WhatsAppStep.CODE, phone = phone, busy = false, error = null,
                            code = if (isResend) "" else it.code, expiresInSeconds = r.expiresInSeconds
                        )
                    }
                    startCountdown(r.resendAfterSeconds)
                }
                is StartOutcome.BadPhone -> fail(BAD_PHONE_MESSAGE, backTo = WhatsAppStep.PHONE)
                StartOutcome.RateLimited -> fail(RATE_LIMITED_MESSAGE)
                is StartOutcome.NetworkError -> fail(OFFLINE_MESSAGE)
                StartOutcome.NotConfigured -> fail("Sign-in with WhatsApp isn't set up on this build.")
            }
        }
    }

    /** Check the code, then sign in to Firebase with the custom token the server returns. */
    fun verify() {
        val s = _state.value
        val phone = s.phone
        if (s.busy || phone == null) return
        if (s.code.length != CODE_LENGTH) { _state.update { it.copy(error = BAD_CODE_MESSAGE) }; return }
        _state.update { it.copy(busy = true, error = null) }
        scope.launch {
            when (val r = api.whatsappVerify(phone, s.code)) {
                is VerifyOutcome.Verified -> {
                    if (firebase.signInWithCustomToken(r.firebaseCustomToken)) {
                        countdown?.cancel()
                        _state.update { it.copy(busy = false, signedIn = true) }
                        onSignedIn()
                    } else fail(FIREBASE_FAILED_MESSAGE)
                }
                VerifyOutcome.MalformedCode -> fail(BAD_CODE_MESSAGE)
                VerifyOutcome.WrongCode -> fail(WRONG_CODE_MESSAGE)
                VerifyOutcome.RateLimited -> fail(RATE_LIMITED_MESSAGE)
                is VerifyOutcome.NetworkError -> fail(OFFLINE_MESSAGE)
                VerifyOutcome.NotConfigured -> fail("Sign-in with WhatsApp isn't set up on this build.")
            }
        }
    }

    /** From the code step, back to the number box to fix the number. */
    fun changeNumber() = edit()

    /** Throw everything away (the screen was closed). */
    fun reset() {
        countdown?.cancel()
        _state.value = WhatsAppState()
    }

    private fun fail(message: String, backTo: WhatsAppStep? = null) =
        _state.update { it.copy(busy = false, error = message, step = backTo ?: it.step) }

    private fun startCountdown(seconds: Int) {
        countdown?.cancel()
        _state.update { it.copy(resendIn = seconds.coerceAtLeast(0)) }
        countdown = scope.launch {
            while (_state.value.resendIn > 0) {
                delay(1_000)
                _state.update { it.copy(resendIn = (it.resendIn - 1).coerceAtLeast(0)) }
            }
        }
    }
}
