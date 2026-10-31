package org.ekatra.alfred.lessons

import com.google.gson.JsonParser

/**
 * The `exp` claim of a JWT in milliseconds since the epoch, or null if [token] is not a JWT with one
 * (the build-time demo token may be opaque). The signature is NOT checked: this only schedules a refresh.
 */
fun jwtExpiryMillis(token: String): Long? {
    val payload = token.split('.').getOrNull(1) ?: return null
    val json = decodeBase64Url(payload)?.toString(Charsets.UTF_8) ?: return null
    val exp = runCatching { JsonParser.parseString(json).asJsonObject.get("exp") }.getOrNull() ?: return null
    return runCatching { exp.asLong * 1000L }.getOrNull()
}

/**
 * A small base64url decoder, so this works on API 24 (java.util.Base64 needs 26) and in plain JVM unit
 * tests (android.util.Base64 is a stub there). Accepts missing padding. Null on any bad character.
 */
internal fun decodeBase64Url(input: String): ByteArray? {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val clean = input.trimEnd('=')
    if (clean.length % 4 == 1) return null
    val out = java.io.ByteArrayOutputStream()
    var buffer = 0
    var bits = 0
    for (c in clean) {
        val v = alphabet.indexOf(c)
        if (v < 0) return null
        buffer = (buffer shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((buffer shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}
