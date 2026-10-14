package com.phonemcp.server.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpHttpTest {

    @Test
    fun missingOriginAllowed() {
        assertTrue(McpHttp.isOriginAllowed(null, "192.168.1.50"))
        assertTrue(McpHttp.isOriginAllowed("", "192.168.1.50"))
    }

    @Test
    fun matchingOriginAllowed() {
        assertTrue(McpHttp.isOriginAllowed("http://192.168.1.50:8080", "192.168.1.50"))
        assertTrue(McpHttp.isOriginAllowed("http://localhost:3000", "192.168.1.50"))
    }

    @Test
    fun foreignOriginRejected() {
        assertFalse(McpHttp.isOriginAllowed("https://evil.example", "192.168.1.50"))
        assertFalse(McpHttp.isOriginAllowed("not-a-uri-%%%", "192.168.1.50"))
    }

    @Test
    fun versionHeaderValidation() {
        assertTrue(McpHttp.isVersionHeaderValid("2025-06-18"))
        assertTrue(McpHttp.isVersionHeaderValid("2025-03-26"))
        assertFalse(McpHttp.isVersionHeaderValid(null))
        assertFalse(McpHttp.isVersionHeaderValid("1999-01-01"))
    }

    @Test
    fun mimeMapping() {
        assertEquals("image/jpeg", McpHttp.mimeTypeFor("abc.jpg"))
        assertEquals("video/mp4", McpHttp.mimeTypeFor("clip.MP4"))
        assertEquals("audio/mp4", McpHttp.mimeTypeFor("rec.m4a"))
        assertEquals("application/octet-stream", McpHttp.mimeTypeFor("blob.bin"))
    }

    @Test
    fun rangeParsing() {
        assertEquals(McpHttp.RangeSpec(0, 99), McpHttp.parseRange("bytes=0-99"))
        assertEquals(McpHttp.RangeSpec(100, null), McpHttp.parseRange("bytes=100-"))
        assertNull(McpHttp.parseRange(null))
        assertNull(McpHttp.parseRange("items=0-10"))
        assertNull(McpHttp.parseRange("bytes=99-10"))
    }

    @Test
    fun bodySizeGate() {
        assertEquals(1024L * 1024L, McpHttp.MAX_MCP_BODY_BYTES)
        assertFalse(McpHttp.isBodyTooLarge(null)) // chunked: residual, allowed
        assertFalse(McpHttp.isBodyTooLarge(0))
        assertFalse(McpHttp.isBodyTooLarge(McpHttp.MAX_MCP_BODY_BYTES))
        assertTrue(McpHttp.isBodyTooLarge(McpHttp.MAX_MCP_BODY_BYTES + 1))
        assertTrue(McpHttp.isBodyTooLarge(-1))
    }
}
