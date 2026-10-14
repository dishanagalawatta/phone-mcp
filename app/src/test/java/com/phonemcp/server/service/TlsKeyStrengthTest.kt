package com.phonemcp.server.service

import java.security.KeyPairGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fuzz finding (ADR-0017): Ktor's cert builder defaults to 1024-bit RSA. */
class TlsKeyStrengthTest {
    private fun rsa(bits: Int) = KeyPairGenerator.getInstance("RSA").apply {
        initialize(bits)
    }.generateKeyPair().public

    @Test
    fun floorIs2048() {
        assertEquals(2048, McpHttp.MIN_TLS_RSA_BITS)
    }

    @Test
    fun rsa1024IsWeak() {
        assertTrue(McpHttp.isTlsKeyTooWeak(rsa(1024)))
    }

    @Test
    fun rsa2048IsAccepted() {
        assertFalse(McpHttp.isTlsKeyTooWeak(rsa(2048)))
    }

    @Test
    fun missingOrNonRsaIsWeak() {
        assertTrue(McpHttp.isTlsKeyTooWeak(null))
        val ec = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
            .generateKeyPair().public
        assertTrue(McpHttp.isTlsKeyTooWeak(ec))
    }
}
