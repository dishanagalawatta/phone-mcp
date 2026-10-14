package com.phonemcp.server.data

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class BleArgsTest {

    private fun args(vararg pairs: Pair<String, String>): kotlinx.serialization.json.JsonObject =
        buildJsonObject { for ((k, v) in pairs) put(k, v) }

    @Test
    fun addressValidation() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            RadioProvider.parseAddress(args("address" to "aa:bb:cc:dd:ee:ff")),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun addressRejectsGarbage() {
        RadioProvider.parseAddress(args("address" to "not-a-mac"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun addressRequiresField() {
        RadioProvider.parseAddress(buildJsonObject { })
    }

    @Test
    fun gattTargetParses() {
        val target = RadioProvider.parseGattTarget(
            args(
                "address" to "11:22:33:44:55:66",
                "serviceUuid" to "0000180f-0000-1000-8000-00805f9b34fb",
                "charUuid" to "00002a19-0000-1000-8000-00805f9b34fb",
            ),
        )
        assertEquals("11:22:33:44:55:66", target.address)
        assertEquals("0000180f-0000-1000-8000-00805f9b34fb", target.service.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun gattTargetRejectsBadUuid() {
        RadioProvider.parseGattTarget(
            args("address" to "11:22:33:44:55:66", "serviceUuid" to "zzz", "charUuid" to "00002a19-0000-1000-8000-00805f9b34fb"),
        )
    }

    @Test
    fun hexParses() {
        assertEquals(listOf(0x0A.toByte(), 0xFF.toByte()).toByteArray().toList(), RadioProvider.parseHexArg(args("hex" to "0aff")).toList())
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexRejectsOdd() {
        RadioProvider.parseHexArg(args("hex" to "abc"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexRejectsNonHex() {
        RadioProvider.parseHexArg(args("hex" to "zzzz"))
    }

    @Test
    fun hexAcceptsAttMax() {
        // 512 bytes = ATT ceiling.
        assertEquals(512, RadioProvider.parseHexArg(args("hex" to "ab".repeat(512))).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun hexRejectsOversize() {
        RadioProvider.parseHexArg(args("hex" to "ab".repeat(513)))
    }

    @Test
    fun bytePayloadTextWhenPrintable() {
        val payload = RadioProvider.bytePayload("hi".toByteArray(Charsets.UTF_8))
        assertEquals("hi", payload["text"]!!.jsonPrimitive.content)
        assertEquals("6869", payload["hex"]!!.jsonPrimitive.content)
    }

    @Test
    fun bytePayloadOmitsTextWhenBinary() {
        val payload = RadioProvider.bytePayload(byteArrayOf(0x00, 0x01, 0x02))
        assertEquals(false, payload.containsKey("text"))
        assertEquals("000102", payload["hex"]!!.jsonPrimitive.content)
    }
}
