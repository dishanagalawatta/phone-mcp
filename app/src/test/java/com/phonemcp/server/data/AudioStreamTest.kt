package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamTest {

    @Test
    fun defaults() {
        val request = AudioProvider.parseStreamArgs(buildJsonObject { })
        assertEquals(10_000L, request.durationMs)
        assertEquals(1000L, request.chunkMs)
    }

    @Test
    fun clamps() {
        val request = AudioProvider.parseStreamArgs(
            buildJsonObject {
                put("durationMs", JsonPrimitive(999_999))
                put("chunkMs", JsonPrimitive(10))
            },
        )
        assertEquals(60_000L, request.durationMs)
        assertEquals(250L, request.chunkMs)
    }

    @Test
    fun rmsSilenceIsZero() {
        assertEquals(0.0, AudioProvider.rms(ShortArray(160), 160), 0.0)
        assertEquals(0.0, AudioProvider.rms(ShortArray(160), 0), 0.0)
    }

    @Test
    fun rmsFullScaleNearOne() {
        val full = ShortArray(160) { Short.MAX_VALUE }
        assertTrue(AudioProvider.rms(full, full.size) > 0.99)
    }

    @Test
    fun rmsHalfScaleNearHalf() {
        val half = ShortArray(160) { 16384 }
        assertEquals(0.5, AudioProvider.rms(half, half.size), 0.01)
    }
}
