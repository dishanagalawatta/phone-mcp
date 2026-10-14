package com.phonemcp.server.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class ArgParserTest {
    private fun args() = buildJsonObject {
        put("durationMs", 5000L)
        put("flag", true)
        put("name", "hello")
        put("ratio", 0.5)
    }

    @Test
    fun longCoercesIntoRange() {
        val a = args()
        assertEquals(5000L, ArgParser.long(a, "durationMs", 10_000L, 2000L..30_000L))
        assertEquals(10_000L, ArgParser.long(a, "missing", 10_000L, 2000L..30_000L))
        assertEquals(30_000L, ArgParser.long(buildJsonObject { put("durationMs", 99_999L) }, "durationMs", 10_000L, 2000L..30_000L))
    }

    @Test
    fun booleanAndStringHelpers() {
        val a = args()
        assertTrue(ArgParser.boolean(a, "flag", false))
        assertEquals("hello", ArgParser.string(a, "name"))
        assertEquals("fallback", ArgParser.string(a, "missing", "fallback"))
        assertEquals(0.5, ArgParser.double(a, "ratio", 1.0, 0.0..1.0), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiredStringThrowsOnMissing() {
        ArgParser.requiredString(args(), "missing")
    }
}
