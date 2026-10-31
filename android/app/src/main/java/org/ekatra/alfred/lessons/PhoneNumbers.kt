package org.ekatra.alfred.lessons

/**
 * Turn what a person typed into E.164, defaulting the country code to +91. Null if it cannot be a phone number.
 *
 * `98765 43210`, `09876543210` and `+91 98765-43210` all become `+919876543210`. Anything with its own `+`
 * or `00` prefix keeps its country code. Valid means `+`, then 8 to 15 digits, the first not zero.
 */
fun normalizePhone(input: String): String? {
    val cleaned = input.trim().replace(Regex("[\\s\\-().]"), "")
    if (cleaned.isEmpty()) return null
    val e164 = when {
        cleaned.startsWith("+") -> cleaned
        cleaned.startsWith("00") -> "+" + cleaned.drop(2)
        cleaned.all { it.isDigit() } -> {
            val digits = cleaned.trimStart('0')
            if (digits.length == 10) "+91$digits" else "+$digits"
        }
        else -> return null
    }
    return e164.takeIf { Regex("^\\+[1-9][0-9]{7,14}$").matches(it) }
}
