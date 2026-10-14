package com.phonemcp.server.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcapHarTest {

    private fun packet(tsMs: Long = 1_700_000_000_000L): PacketRecord {
        val ip = ByteArray(20)
        ip[0] = 0x45.toByte()
        ip[2] = 0x00.toByte()
        ip[3] = 0x28.toByte()
        ip[9] = 6.toByte()
        return PacketRecord(tsMs, 10158, "com.example", ip)
    }

    @Test
    fun pcapHasGlobalHeaderAndRecords() {
        val bytes = PcapIO.writePcap(listOf(packet(), packet(1_700_000_001_000L)))
        assertEquals(24 + 2 * (16 + 20), bytes.size)
        val head = ByteBuffer.wrap(bytes, 0, 24).order(ByteOrder.BIG_ENDIAN)
        assertEquals(0xa1b2c3d4.toInt(), head.int)
        assertEquals(2, head.short.toInt())
        assertEquals(4, head.short.toInt())
        assertEquals(101, ByteBuffer.wrap(bytes, 20, 4).order(ByteOrder.BIG_ENDIAN).int)
        // First record header: ts_sec + incl_len
        val rec = ByteBuffer.wrap(bytes, 24, 16).order(ByteOrder.BIG_ENDIAN)
        assertEquals((1_700_000_000_000L / 1000).toInt(), rec.int)
        rec.int // usec
        assertEquals(20, rec.int)
    }

    @Test
    fun pcapEmptyYieldsHeaderOnly() {
        assertEquals(24, PcapIO.writePcap(emptyList()).size)
    }

    @Test
    fun pcapNgHasSectionAndPacketBlocks() {
        val bytes = PcapIO.writePcapNg(listOf(packet()))
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x0A0D0D0A, buf.int) // SHB magic
        val shbLen = buf.int
        assertTrue(shbLen >= 28)
        // SHB occupies bytes [0, shbLen); IDB type follows immediately.
        buf.position(shbLen)
        assertEquals(0x00000001, buf.int)
        // EPB must exist somewhere after
        val asString = String(bytes, Charsets.ISO_8859_1)
        assertTrue(bytes.size > 12 + 12 + 32)
        // EPB block type 6 present
        var foundEpb = false
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val type = ByteBuffer.wrap(bytes, pos, 4).order(ByteOrder.LITTLE_ENDIAN).int
            val len = ByteBuffer.wrap(bytes, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (len < 12 || pos + len > bytes.size) break
            if (type == 0x00000006) {
                foundEpb = true
                break
            }
            pos += len
        }
        assertTrue(foundEpb)
        assertTrue(asString.contains("com.example"))
    }

    @Test
    fun harBuildsValidSkeleton() {
        val entries = listOf(
            HttpTransaction(
                tsMs = 1_700_000_000_000L, method = "GET", url = "http://example.com/a",
                host = "example.com", requestHeaders = mapOf("host" to "example.com"),
                status = 200, responseHeaders = mapOf("content-type" to "text/html"),
                responseBodyBytes = 12, packageName = "com.example",
            ),
        )
        val har = HarBuilder.build(entries)
        assertTrue(har.contains("\"version\":\"1.2\""))
        assertTrue(har.contains("\"method\":\"GET\""))
        assertTrue(har.contains("http://example.com/a"))
        assertTrue(har.contains("\"status\":200"))
        assertTrue(har.contains("com.example"))
    }

    @Test
    fun harEscapesQuotes() {
        val entries = listOf(
            HttpTransaction(
                tsMs = 0, method = "GET", url = "http://x/", host = "x",
                requestHeaders = mapOf("x" to "a\"b\\c"),
            ),
        )
        val har = HarBuilder.build(entries)
        assertTrue(har.contains("a\\\"b\\\\c"))
    }

    @Test
    fun keylogImportExportRoundTrip() {
        KeylogStore.clear()
        val added = KeylogStore.importLines(
            "# comment\nCLIENT_RANDOM aabb ccdd\nGARBAGE_LINE\nCLIENT_TRAFFIC_SECRET_0 aabb dd\n",
        )
        assertEquals(2, added)
        assertEquals(2, KeylogStore.size())
        val out = KeylogStore.export()
        assertTrue(out.contains("CLIENT_RANDOM aabb ccdd"))
        assertTrue(!out.contains("GARBAGE"))
        KeylogStore.clear()
        assertEquals(0, KeylogStore.size())
        assertEquals("", KeylogStore.export())
    }
}
