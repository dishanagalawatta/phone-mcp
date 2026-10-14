package com.phonemcp.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthTokenTest {

    @Test
    fun extractBearer() {
        assertEquals("abc123", AuthToken.extract("Bearer abc123"))
        assertEquals("abc123", AuthToken.extract("bearer abc123"))
        assertEquals("abc123", AuthToken.extract("  Bearer   abc123  "))
    }

    @Test
    fun extractRejectsMalformed() {
        assertNull(AuthToken.extract(null))
        assertNull(AuthToken.extract(""))
        assertNull(AuthToken.extract("abc123"))
        assertNull(AuthToken.extract("Basic abc123"))
        assertNull(AuthToken.extract("Bearer "))
        assertNull(AuthToken.extract("Bearer"))
    }

    @Test
    fun generateIsUrlSafeUnique() {
        val first = AuthToken.generate()
        val second = AuthToken.generate()
        assertTrue(first != second)
        for (token in listOf(first, second)) {
            assertEquals(43, token.length)
            assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(token))
        }
    }
}
