package org.ekatra.alfred

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Access control for the host mode LAN server.
 *
 * A fresh key is generated each time the server starts and travels in the QR code
 * URL (`/?k=<key>`). Requests must carry it as the `k` query parameter or the
 * `X-Ekatra-Key` header, so other devices on a shared Wi-Fi cannot use the model.
 */
class HostAccess(val key: String = newKey()) {

    companion object {
        const val HEADER = "x-ekatra-key" // NanoHTTPD lower-cases header names
        const val PARAM = "k"
        const val MAX_BODY_BYTES = 8 * 1024
        const val MAX_MESSAGE_CHARS = 2000

        private const val KEY_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

        fun newKey(length: Int = 12): String {
            val random = SecureRandom()
            return (1..length).map { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] }.joinToString("")
        }
    }

    fun isAuthorized(headers: Map<String, String>, params: Map<String, List<String>>): Boolean {
        val presented = headers[HEADER] ?: params[PARAM]?.firstOrNull() ?: return false
        return MessageDigest.isEqual(presented.toByteArray(), key.toByteArray())
    }

    /** Null if the declared body size is acceptable, otherwise the reason to reject. */
    fun bodySizeError(headers: Map<String, String>): String? {
        val length = headers["content-length"]?.toLongOrNull() ?: return "Content-Length required"
        return if (length > MAX_BODY_BYTES) "Request too large" else null
    }
}
