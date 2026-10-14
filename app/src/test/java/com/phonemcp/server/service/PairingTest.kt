package com.phonemcp.server.service

import org.junit.Assert.assertEquals
import org.junit.Test

class PairingTest {

    @Test
    fun sha256Vector() {
        // SHA-256("abc") — standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Pairing.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun groupedUppercaseColonFormat() {
        assertEquals("AB:CD:01", Pairing.groupedFingerprint("abcd01"))
    }

    @Test
    fun pairingPayloadShape() {
        assertEquals(
            "{\"url\":\"https://1.2.3.4:8080/mcp\",\"token\":\"tok\",\"sha256\":\"fp\"}",
            Pairing.pairingPayload("https://1.2.3.4:8080/mcp", "tok", "fp"),
        )
    }
}
