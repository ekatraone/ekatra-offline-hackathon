package org.ekatra.alfred.lessons

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwtTest {
    @Test
    fun `reads the exp claim in milliseconds`() {
        assertEquals(1_800_000_000_000L, jwtExpiryMillis(fakeJwt(1_800_000_000L)))
    }

    @Test
    fun `no exp, opaque tokens and junk give null`() {
        assertNull(jwtExpiryMillis(fakeJwt(null)))
        assertNull(jwtExpiryMillis("opaque-demo-token"))
        assertNull(jwtExpiryMillis(""))
        assertNull(jwtExpiryMillis("a.b.c"))
        assertNull(jwtExpiryMillis("a.!!!.c"))
        assertNull(jwtExpiryMillis("x." + java.util.Base64.getUrlEncoder().encodeToString("""{"exp":"soon"}""".toByteArray()) + ".y"))
    }

    @Test
    fun `base64url decodes with and without padding and matches the JDK`() {
        for (text in listOf("", "a", "ab", "abc", "abcd", """{"exp":1,"x":"~~?>>"}""")) {
            val encoded = java.util.Base64.getUrlEncoder().encodeToString(text.toByteArray())
            assertArrayEquals(text, text.toByteArray(), decodeBase64Url(encoded))
            assertArrayEquals(text, text.toByteArray(), decodeBase64Url(encoded.trimEnd('=')))
        }
        assertNull(decodeBase64Url("a"))        // an impossible length
        assertNull(decodeBase64Url("ab+d"))     // '+' is standard base64, not url-safe
    }
}
