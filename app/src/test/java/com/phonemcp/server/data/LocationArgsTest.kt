package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationArgsTest {

    @Test
    fun defaultsHighAccuracy8s() {
        val request = LocationProvider.parseArgs(buildJsonObject { })
        assertEquals(true, request.highAccuracy)
        assertEquals(8000L, request.timeoutMs)
    }

    @Test
    fun parsesFlagsAndClampsTimeout() {
        val request = LocationProvider.parseArgs(
            buildJsonObject {
                put("highAccuracy", JsonPrimitive(false))
                put("timeoutMs", JsonPrimitive(60_000))
            },
        )
        assertEquals(false, request.highAccuracy)
        assertEquals(30_000L, request.timeoutMs)
    }

    @Test
    fun clampsLowTimeout() {
        assertEquals(
            1000L,
            LocationProvider.parseArgs(buildJsonObject { put("timeoutMs", JsonPrimitive(10)) }).timeoutMs,
        )
    }
}
