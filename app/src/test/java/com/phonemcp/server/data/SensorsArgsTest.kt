package com.phonemcp.server.data

import android.hardware.Sensor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsArgsTest {

    @Test
    fun defaultsToAllTypesAnd2s() {
        val request = SensorsProvider.parseArgs(buildJsonObject { })
        assertEquals(SensorsProvider.DEFAULT_TYPES, request.types)
        assertEquals(2000L, request.timeoutMs)
    }

    @Test
    fun parsesSubsetCaseInsensitively() {
        val request = SensorsProvider.parseArgs(
            buildJsonObject {
                put("types", JsonArray(listOf(JsonPrimitive("Light"), JsonPrimitive("PRESSURE"))))
                put("timeoutMs", JsonPrimitive(500))
            },
        )
        assertEquals(listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PRESSURE), request.types)
        assertEquals(500L, request.timeoutMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSensorThrows() {
        SensorsProvider.parseArgs(
            buildJsonObject {
                put("types", JsonArray(listOf(JsonPrimitive("telepathy"))))
            },
        )
    }

    @Test
    fun timeoutClamped() {
        assertEquals(
            100L,
            SensorsProvider.parseArgs(buildJsonObject { put("timeoutMs", JsonPrimitive(1)) }).timeoutMs,
        )
        assertEquals(
            10_000L,
            SensorsProvider.parseArgs(buildJsonObject { put("timeoutMs", JsonPrimitive(99_999)) }).timeoutMs,
        )
    }

    @Test
    fun sensorMapCoversExpectedNames() {
        assertTrue(SensorsProvider.SENSOR_TYPES.keys.containsAll(listOf("accelerometer", "gyroscope", "light")))
    }
}
