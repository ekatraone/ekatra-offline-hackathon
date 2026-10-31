package org.ekatra.alfred.lessons

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class WhatsAppStep { PHONE, CODE }

data class WhatsAppState(
    val step: WhatsAppStep = WhatsAppStep.PHONE,
    /** What the person typed. */
    val phoneInput: String = "",
    /** The E.164 number the code was sent to (set once a code is on its way). */
    val phone: String? = null,
    val code: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    /** Seconds until "Resend" works; 0 means it works now. */
    val resendIn: Int = 0,
    val expiresInSeconds: Int = 0,
    /** Firebase accepted the custom token: the screen is done and sign-in continues as for Google or email. */
    val signedIn: Boolean = false
) {
    val canResend: Boolean get() = step == WhatsAppStep.CODE && resendIn == 0 && !busy && !signedIn
    val canSubmitCode: Boolean get() = step == WhatsAppStep.CODE && code.length == WhatsAppSignIn.CODE_LENGTH && !busy && !signedIn
}

/**
 * Sign in with a one-time code sent on WhatsApp: phone, then code, then Firebase `signInWithCustomToken`.
 * Driven from a [scope] (the ViewModel's), so the resend countdown runs in whatever clock that scope uses.
 *
 * @param onSignedIn runs after Firebase has signed the user in; the caller carries on exactly as it
 *   does after a Google or email sign-in.
 */
class WhatsAppSignIn(
    private val api: PhoneAuthApi,
    private val firebase: FirebaseSession,
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

    /** Ask for a code for the number in the box. */
    fun sendCode() {
        val s = _state.value
        if (s.busy) return
        val phone = normalizePhone(s.phoneInput)
        if (phone == null) { _state.update { it.copy(error = BAD_PHONE_MESSAGE) }; return }
        request(phone, isResend = false)
    }

    /** Ask again, once the countdown allows it. */
    fun resend() {
        val s = _state.value
        if (!s.canResend) return
        request(s.phone ?: return, isResend = true)
    }

    private fun request(phone: String, isResend: Boolean) {
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
                is StartOutcome.BadPhone -> fail(BAD_PHONE_MESSAGE)
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

    /** Back to the phone box to fix the number. */
    fun changeNumber() {
        countdown?.cancel()
        _state.update { WhatsAppState(phoneInput = it.phoneInput) }
    }

    /** Throw everything away (the screen was closed). */
    fun reset() {
        countdown?.cancel()
        _state.value = WhatsAppState()
    }

    private fun fail(message: String) = _state.update { it.copy(busy = false, error = message) }

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
