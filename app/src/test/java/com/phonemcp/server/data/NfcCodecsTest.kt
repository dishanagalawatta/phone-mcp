package com.phonemcp.server.data

import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NfcCodecsTest {

    @Test
    fun toHexFormats() {
        assertEquals("04a2ff", NfcReader.toHex(byteArrayOf(0x04, 0xA2.toByte(), 0xFF.toByte())))
        assertEquals("", NfcReader.toHex(null))
    }

    @Test
    fun decodeTextUtf8() {
        // status 0x02: UTF-8, language length 2 ("en") + "hi".
        val payload = byteArrayOf(0x02, 'e'.code.toByte(), 'n'.code.toByte(), 'h'.code.toByte(), 'i'.code.toByte())
        assertEquals("hi", NfcReader.decodeTextPayload(payload))
    }

    @Test
    fun decodeTextUtf16() {
        val text = "héllo".toByteArray(Charsets.UTF_16)
        val payload = byteArrayOf((0x80 or 2).toByte(), 'e'.code.toByte(), 'n'.code.toByte()) + text
        assertEquals("héllo", NfcReader.decodeTextPayload(payload))
    }

    @Test
    fun decodeTextRejectsTruncated() {
        assertNull(NfcReader.decodeTextPayload(byteArrayOf()))
        assertNull(NfcReader.decodeTextPayload(byteArrayOf(0x3F)))
    }

    @Test
    fun decodeUriPrefixes() {
        // type 'U' (0x55), prefix 0x01 = http://www.
        val payload = byteArrayOf(0x01) + "x.test".toByteArray(Charsets.UTF_8)
        assertEquals("http://www.x.test", NfcReader.decodeUriPayload(byteArrayOf(0x55), payload))
        assertNull(NfcReader.decodeUriPayload(byteArrayOf(0x54), payload))
        assertNull(NfcReader.decodeUriPayload(byteArrayOf(0x55), byteArrayOf()))
    }

    @Test
    fun decodeRecordShapeAndTruncation() {
        val big = ByteArray(NfcReader.MAX_PAYLOAD + 10) { 0x41 }
        val record = NfcReader.decodeRecord(1, byteArrayOf(0x54), big)
        assertEquals(true, record["truncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(NfcReader.MAX_PAYLOAD * 2, record["payloadHex"]!!.jsonPrimitive.content.length)
    }
}
