package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class VibrateArgsTest {

    @Test
    fun defaultsTo200ms() {
        assertEquals(200L, OutputProvider.parseVibrateArgs(buildJsonObject { }))
    }

    @Test
    fun clampsRange() {
        assertEquals(1L, OutputProvider.parseVibrateArgs(buildJsonObject { put("durationMs", JsonPrimitive(0)) }))
        assertEquals(
            5000L,
            OutputProvider.parseVibrateArgs(buildJsonObject { put("durationMs", JsonPrimitive(60_000)) }),
        )
        assertEquals(
            350L,
            OutputProvider.parseVibrateArgs(buildJsonObject { put("durationMs", JsonPrimitive(350)) }),
        )
    }
}
