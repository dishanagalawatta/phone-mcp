package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioArgsTest {

    @Test
    fun defaultsTo5s() {
        assertEquals(5000L, AudioProvider.parseArgs(buildJsonObject { }))
    }

    @Test
    fun clampsRange() {
        assertEquals(
            1000L,
            AudioProvider.parseArgs(buildJsonObject { put("durationMs", JsonPrimitive(100)) }),
        )
        assertEquals(
            30_000L,
            AudioProvider.parseArgs(buildJsonObject { put("durationMs", JsonPrimitive(120_000)) }),
        )
        assertEquals(
            8000L,
            AudioProvider.parseArgs(buildJsonObject { put("durationMs", JsonPrimitive(8000)) }),
        )
    }
}
