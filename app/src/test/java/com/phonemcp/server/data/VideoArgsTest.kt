package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoArgsTest {

    @Test
    fun defaults() {
        val request = CameraProvider.parseVideoArgs(buildJsonObject { })
        assertEquals("back", request.lens)
        assertEquals(5000L, request.durationMs)
        assertEquals(true, request.withAudio)
    }

    @Test
    fun parsesAllFields() {
        val request = CameraProvider.parseVideoArgs(
            buildJsonObject {
                put("lens", JsonPrimitive("front"))
                put("durationMs", JsonPrimitive(1000))
                put("withAudio", JsonPrimitive(false))
            },
        )
        assertEquals("front", request.lens)
        assertEquals(2000L, request.durationMs) // clamped to minimum
        assertEquals(false, request.withAudio)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLensThrows() {
        CameraProvider.parseVideoArgs(buildJsonObject { put("lens", JsonPrimitive("sideways")) })
    }
}
