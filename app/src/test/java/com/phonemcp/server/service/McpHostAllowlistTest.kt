package com.phonemcp.server.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHostAllowlistTest {
    @Test
    fun loopbackAlwaysAllowed() {
        assertTrue(McpHttp.isHostAllowed("localhost:8080"))
        assertTrue(McpHttp.isHostAllowed("127.0.0.1"))
        assertTrue(McpHttp.isHostAllowed("[::1]:8080"))
    }

    @Test
    fun blankHostRejected() {
        assertFalse(McpHttp.isHostAllowed(null))
        assertFalse(McpHttp.isHostAllowed(""))
        assertFalse(McpHttp.isHostAllowed("  "))
    }

    @Test
    fun pinEnforcedWhenConfigured() {
        assertTrue(McpHttp.isHostAllowed("192.168.1.50:8080", setOf("192.168.1.50")))
        assertFalse(McpHttp.isHostAllowed("evil.example", setOf("192.168.1.50")))
    }

    @Test
    fun openWhenNoPinConfigured() {
        // Origin check remains the guard; host pin is opt-in hardening.
        assertTrue(McpHttp.isHostAllowed("192.168.1.50:8080"))
    }
}
