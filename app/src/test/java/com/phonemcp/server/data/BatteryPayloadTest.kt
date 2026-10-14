package com.phonemcp.server.data

import android.os.BatteryManager
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryPayloadTest {

    @Test
    fun computesPercentAndCharging() {
        val payload = RadioProvider.batteryPayload(
            level = 42,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_AC,
            temperatureTenths = 285,
        )
        assertEquals(42, payload["percent"]!!.jsonPrimitive.content.toInt())
        assertEquals(true, payload["charging"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, payload["full"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, payload["pluggedAc"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(28.5, payload["temperatureC"]!!.jsonPrimitive.content.toDouble(), 0.001)
    }

    @Test
    fun handlesUnknownLevel() {
        val payload = RadioProvider.batteryPayload(
            level = -1,
            scale = 100,
            status = BatteryManager.BATTERY_STATUS_UNKNOWN,
            plugged = 0,
            temperatureTenths = Int.MIN_VALUE,
        )
        assertEquals(-1, payload["percent"]!!.jsonPrimitive.content.toInt())
        assertEquals(false, payload["charging"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, payload.containsKey("temperatureC"))
    }
}
