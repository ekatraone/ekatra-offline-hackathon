package org.ekatra.alfred

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostAccessTest {

    private val access = HostAccess("secretkey123")

    @Test
    fun `accepts key from header or query parameter`() {
        assertTrue(access.isAuthorized(mapOf(HostAccess.HEADER to "secretkey123"), emptyMap()))
        assertTrue(access.isAuthorized(emptyMap(), mapOf(HostAccess.PARAM to listOf("secretkey123"))))
    }

    @Test
    fun `rejects missing or wrong key`() {
        assertFalse(access.isAuthorized(emptyMap(), emptyMap()))
        assertFalse(access.isAuthorized(mapOf(HostAccess.HEADER to "wrong"), emptyMap()))
        assertFalse(access.isAuthorized(emptyMap(), mapOf(HostAccess.PARAM to listOf(""))))
    }

    @Test
    fun `body size must be declared and small`() {
        assertNull(access.bodySizeError(mapOf("content-length" to "512")))
        assertEquals("Content-Length required", access.bodySizeError(emptyMap()))
        assertEquals("Request too large", access.bodySizeError(mapOf("content-length" to "500000000")))
    }

    @Test
    fun `generated keys are random`() {
        val a = HostAccess.newKey()
        assertEquals(12, a.length)
        assertNotEquals(a, HostAccess.newKey())
    }
}
