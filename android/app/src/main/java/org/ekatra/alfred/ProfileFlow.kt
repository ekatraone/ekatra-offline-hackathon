package org.ekatra.alfred

/** What ProfileActivity should do right now. */
enum class ProfileAction {
    /** Do nothing yet (edit mode, or waiting). */
    STAY,

    /** Show the consent screen and wait for it to finish. */
    ASK_CONSENT,

    /** Not signed in: the sign-in screen is showing. */
    SHOW_SIGN_IN,

    /** Signed in and consent handled: open the next screen and finish. */
    GO_ONWARD
}

/**
 * Consent comes before any onward navigation, including for users who are already signed in
 * (upgraders whose consent_shown is absent). Edit mode (Settings > Profile) has no gate.
 */
fun profileAction(needsConsent: Boolean, isAuthenticated: Boolean, isEditMode: Boolean): ProfileAction = when {
    isEditMode -> ProfileAction.STAY
    needsConsent -> ProfileAction.ASK_CONSENT
    isAuthenticated -> ProfileAction.GO_ONWARD
    else -> ProfileAction.SHOW_SIGN_IN
}
