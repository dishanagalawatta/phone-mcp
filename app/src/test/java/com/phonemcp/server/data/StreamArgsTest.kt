package com.phonemcp.server.data

import android.hardware.Sensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamArgsTest {

    @Test
    fun defaultsImuSubset() {
        val request = SensorsProvider.parseStreamArgs(buildJsonObject { })
        assertEquals(SensorsProvider.DEFAULT_STREAM_TYPES, request.types)
        assertEquals(200L, request.intervalMs)
        assertEquals(10_000L, request.durationMs)
        assertEquals(true, request.includeLocation)
    }

    @Test
    fun parsesSubsetAndClamps() {
        val request = SensorsProvider.parseStreamArgs(
            buildJsonObject {
                put("types", JsonArray(listOf(JsonPrimitive("Light"))))
                put("intervalMs", JsonPrimitive(10))
                put("durationMs", JsonPrimitive(999_999))
                put("includeLocation", JsonPrimitive(false))
            },
        )
        assertEquals(listOf(Sensor.TYPE_LIGHT), request.types)
        assertEquals(100L, request.intervalMs)
        assertEquals(60_000L, request.durationMs)
        assertEquals(false, request.includeLocation)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSensorThrows() {
        SensorsProvider.parseStreamArgs(
            buildJsonObject {
                put("types", JsonArray(listOf(JsonPrimitive("telepathy"))))
            },
        )
    }
}
