package org.ekatra.alfred.lessons

/**
 * Whether the chat top bar offers Lessons. Only Maya has Lessons. A release build with no lessons server (its
 * address is blank there) hides the icon rather than open a screen that cannot work; debug builds keep it
 * visible so a missing `local.properties` shows "not set up" instead of silently losing the entry point.
 */
fun lessonsEntryVisible(personaKey: String, isDebug: Boolean, syncBaseUrl: String): Boolean =
    personaHasLessons(personaKey) && (isDebug || syncBaseUrl.isNotBlank())
