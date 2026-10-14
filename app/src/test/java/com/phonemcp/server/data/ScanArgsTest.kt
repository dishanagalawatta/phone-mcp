package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanArgsTest {

    @Test
    fun defaultsTo10s() {
        assertEquals(10_000L, RadioProvider.parseScanArgs(buildJsonObject { }))
    }

    @Test
    fun clampsRange() {
        assertEquals(
            2000L,
            RadioProvider.parseScanArgs(buildJsonObject { put("durationMs", JsonPrimitive(100)) }),
        )
        assertEquals(
            30_000L,
            RadioProvider.parseScanArgs(buildJsonObject { put("durationMs", JsonPrimitive(120_000)) }),
        )
    }
}
