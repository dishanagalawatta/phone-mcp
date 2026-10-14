package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLensTest {

    @Test
    fun defaultsToBack() {
        assertEquals("back", CameraProvider.parseLens(buildJsonObject { }))
    }

    @Test
    fun parsesFrontCaseInsensitively() {
        assertEquals(
            "front",
            CameraProvider.parseLens(buildJsonObject { put("lens", JsonPrimitive("Front")) }),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidLensThrows() {
        CameraProvider.parseLens(buildJsonObject { put("lens", JsonPrimitive("sideways")) })
    }
}
