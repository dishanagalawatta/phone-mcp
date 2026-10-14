package com.phonemcp.server.net

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetParsersTest {

    private fun ipPacket(
        proto: Int = 6,
        src: ByteArray = byteArrayOf(10, 0, 0, 2),
        dst: ByteArray = byteArrayOf(93, -122, -92, 36), // 93.134.164.36
        payload: ByteArray = ByteArray(20),
    ): ByteArray {
        val total = 20 + payload.size
        val out = ByteArray(total)
        out[0] = 0x45.toByte()
        out[2] = (total ushr 8).toByte()
        out[3] = total.toByte()
        out[9] = proto.toByte()
        src.copyInto(out, 12)
        dst.copyInto(out, 16)
        payload.copyInto(out, 20)
        return out
    }

    private fun tcpSegment(srcPort: Int = 41000, dstPort: Int = 443, payload: ByteArray = ByteArray(0)): ByteArray {
        val head = ByteArray(20)
        head[0] = (srcPort ushr 8).toByte()
        head[1] = srcPort.toByte()
        head[2] = (dstPort ushr 8).toByte()
        head[3] = dstPort.toByte()
        head[12] = 0x50.toByte()
        head[13] = 0x02.toByte() // SYN
        return head + payload
    }

    @Test
    fun parsesIpv4Header() {
        val pkt = ipPacket()
        val ip = NetParsers.parseIpv4(pkt)!!
        assertEquals(6, ip.protocol)
        assertEquals("10.0.0.2", ip.srcIp)
        assertEquals(20, ip.headerLen)
    }

    @Test
    fun rejectsShortAndNonV4() {
        assertNull(NetParsers.parseIpv4(ByteArray(10)))
        val pkt = ipPacket()
        pkt[0] = 0x60.toByte() // version 6
        assertNull(NetParsers.parseIpv4(pkt))
    }

    @Test
    fun parsesTcpPorts() {
        val seg = tcpSegment()
        val pkt = ipPacket(payload = seg)
        val tcp = NetParsers.parseTcp(pkt, 20)!!
        assertEquals(41000, tcp.srcPort)
        assertEquals(443, tcp.dstPort)
        assertEquals(0x02, tcp.flags)
    }

    @Test
    fun parsesUdpPorts() {
        val udp = ByteArray(8 + 5)
        udp[0] = 0x30.toByte()
        udp[1] = 0x39.toByte() // 12345
        udp[2] = 0x00.toByte()
        udp[3] = 0x35.toByte() // 53
        udp[4] = 0x00.toByte()
        udp[5] = 0x0D.toByte() // len 13
        val pkt = ipPacket(proto = 17, payload = udp)
        val d = NetParsers.parseUdp(pkt, 20)!!
        assertEquals(12345, d.srcPort)
        assertEquals(53, d.dstPort)
        assertEquals(5, d.payload.size)
    }

    @Test
    fun parsesDnsQuery() {
        val name = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        val head = ByteArray(12)
        head[2] = 0x01.toByte()
        head[5] = 0x01.toByte() // QDCOUNT=1
        val q = head + name + byteArrayOf(0, 1, 0, 1) // A IN
        val queries = NetParsers.parseDnsQueries(q)
        assertEquals(1, queries.size)
        assertEquals("example.com", queries[0].name)
        assertEquals(1, queries[0].qtype)
    }

    @Test
    fun rejectsDnsGarbage() {
        assertTrue(NetParsers.parseDnsQueries(ByteArray(4)).isEmpty())
        assertTrue(NetParsers.parseDnsQueries(ByteArray(20)).isEmpty())
    }

    private fun clientHello(sni: String): ByteArray {
        val sniBytes = sni.toByteArray(Charsets.US_ASCII)
        val serverName = byteArrayOf(0) +
            byteArrayOf((sniBytes.size ushr 8).toByte(), sniBytes.size.toByte()) + sniBytes
        val listLen = serverName.size
        val extBody = byteArrayOf((listLen ushr 8).toByte(), listLen.toByte()) + serverName
        val ext = byteArrayOf(0, 0, (extBody.size ushr 8).toByte(), extBody.size.toByte()) + extBody
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) +
            byteArrayOf(0, 2, 0, 0x2F) + byteArrayOf(1, 0) +
            byteArrayOf((ext.size ushr 8).toByte(), ext.size.toByte()) + ext
        val helloLen = body.size
        val handshake = byteArrayOf(1, (helloLen ushr 16).toByte(), (helloLen ushr 8).toByte(), helloLen.toByte()) + body
        return byteArrayOf(0x16.toByte(), 3, 1, (handshake.size ushr 8).toByte(), handshake.size.toByte()) + handshake
    }

    @Test
    fun parsesSni() {
        val hello = clientHello("example.com")
        assertTrue(NetParsers.isTlsClientHello(hello))
        assertEquals("example.com", NetParsers.parseSni(hello))
    }

    @Test
    fun rejectsNonHelloSni() {
        assertNull(NetParsers.parseSni("GET / HTTP/1.1\r\n\r\n".toByteArray()))
        assertNull(NetParsers.parseSni(ByteArray(10)))
    }

    @Test
    fun parsesHttpRequest() {
        val raw = "GET /path?q=1 HTTP/1.1\r\nHost: example.com\r\nUser-Agent: t\r\n\r\nbody".toByteArray()
        val msg = NetParsers.parseHttp(raw)!!
        assertEquals(true, msg.isRequest)
        assertEquals("GET", msg.method)
        assertEquals("/path?q=1", msg.path)
        assertEquals("example.com", msg.headers["host"])
    }

    @Test
    fun parsesHttpResponse() {
        val raw = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: 3\r\n\r\nabc".toByteArray()
        val msg = NetParsers.parseHttp(raw)!!
        assertEquals(false, msg.isRequest)
        assertEquals(200, msg.status)
        assertEquals("text/html", msg.headers["content-type"])
    }

    @Test
    fun rejectsNonHttp() {
        assertNull(NetParsers.parseHttp(ByteArray(30) { 0x10.toByte() }))
        assertNull(NetParsers.parseHttp("HELLO WORLD\r\n\r\n".toByteArray()))
    }

    @Test
    fun ipv4HeaderRoundTrip() {
        val src = NetParsers.ipv4ToBytes("10.0.0.2")!!
        val dst = NetParsers.ipv4ToBytes("8.8.8.8")!!
        val head = NetBuilders.buildIpv4Header(src, dst, 6, 40)
        val parsed = NetParsers.parseIpv4(head + ByteArray(20))!!
        assertEquals("10.0.0.2", parsed.srcIp)
        assertEquals("8.8.8.8", parsed.dstIp)
        assertNotNull(NetParsers.ipv4ToBytes("1.2.3.4"))
        assertNull(NetParsers.ipv4ToBytes("999.1.1.1"))
        assertNull(NetParsers.ipv4ToBytes("not-an-ip"))
    }

    @Test
    fun buildUrlHandlesMissingHost() {
        assertEquals("http://example.com/a", NetBuilders.buildUrl("example.com", "/a"))
        assertTrue(NetBuilders.buildUrl(null, "/a").contains("unknown"))
    }

    @Test
    fun ipHeaderChecksumValidates() {
        val src = NetParsers.ipv4ToBytes("10.0.0.2")!!
        val dst = NetParsers.ipv4ToBytes("8.8.8.8")!!
        val head = NetBuilders.buildIpv4Header(src, dst, 6, 40)
        // A correctly checksummed header sums to zero.
        assertEquals(0, NetBuilders.checksum(head, 0, 20))
    }

    @Test
    fun parsesIpv6Header() {
        val src = NetParsers.ipv6ToBytes("fd00::2")!!
        val dst = NetParsers.ipv6ToBytes("2606:4700:4700::1111")!!
        val seg = ByteArray(20)
        seg[12] = 0x50.toByte()
        val pkt = NetBuilders.buildIpv6Header(src, dst, 6, seg.size) + seg
        val ip = NetParsers.parseIpv6(pkt)!!
        assertEquals(6, ip.nextHeader)
        assertEquals("fd00::2", ip.srcIp)
        assertEquals("2606:4700:4700::1111", ip.dstIp)
        assertEquals(20, ip.payloadLen)
    }

    @Test
    fun rejectsShortAndNonV6() {
        assertNull(NetParsers.parseIpv6(ByteArray(10)))
        val pkt = ByteArray(40)
        pkt[0] = 0x45.toByte()
        assertNull(NetParsers.parseIpv6(pkt))
    }

    @Test
    fun ipv6TextRoundTrip() {
        val cases = mapOf(
            "::1" to "::1",
            "::" to "::",
            "fd00::2" to "fd00::2",
            "2001:0db8:0000:0000:0000:ff00:0042:8329" to "2001:db8::ff00:42:8329",
            "fe80::1e96:f9a5:34f3:26d1" to "fe80::1e96:f9a5:34f3:26d1",
            "::ffff:192.168.1.50" to "::ffff:c0a8:132",
            "1:2:3:4:5:6:7:8" to "1:2:3:4:5:6:7:8",
        )
        for ((input, want) in cases) {
            val bytes = NetParsers.ipv6ToBytes(input)
            assertNotNull("parse $input", bytes)
            assertEquals(want, NetParsers.bytesToIpv6(bytes!!))
        }
        assertNull(NetParsers.ipv6ToBytes(""))
        assertNull(NetParsers.ipv6ToBytes(":::"))
        assertNull(NetParsers.ipv6ToBytes("1:2:3:4:5:6:7:8:9"))
        assertNull(NetParsers.ipv6ToBytes("gggg::1"))
        assertNull(NetParsers.ipv6ToBytes("12345::"))
    }

    @Test
    fun tcp6ChecksumValidates() {
        val src = NetParsers.ipv6ToBytes("fd00::2")!!
        val dst = NetParsers.ipv6ToBytes("2606:4700:4700::1111")!!
        val tcp = ByteArray(20)
        tcp[0] = 0x30.toByte()
        tcp[12] = 0x50.toByte()
        tcp[13] = 0x02.toByte()
        val pkt = NetBuilders.buildIpv6Header(src, dst, 6, tcp.size) + tcp
        NetBuilders.finishTcpPacket6(pkt)
        // Recompute pseudo + segment incl. checksum: must fold to 0xFFFF.
        var sum = 0
        var i = 8
        while (i < 40) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += 0 // length high word
        sum += 20 // length
        sum += 6 // next header
        i = 40
        while (i + 1 < 60) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun udp6ChecksumValidatesAndNeverZero() {
        val src = NetParsers.ipv6ToBytes("fd00::2")!!
        val dst = NetParsers.ipv6ToBytes("2001:4860:4860::8888")!!
        // Minimal DNS query payload.
        val payload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val udp = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        udp.putShort(12345.toShort())
        udp.putShort(53)
        udp.putShort((8 + payload.size).toShort())
        udp.putShort(0)
        udp.put(payload)
        val pkt = NetBuilders.buildIpv6Header(src, dst, 17, udp.array().size) + udp.array()
        NetBuilders.finishUdpPacket6(pkt)
        val csum = ((pkt[46].toInt() and 0xFF) shl 8) or (pkt[47].toInt() and 0xFF)
        assertTrue("udp6 checksum must be non-zero", csum != 0)
        var sum = 0
        var i = 8
        while (i < 40) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += udp.array().size + 17
        i = 40
        while (i + 1 < pkt.size) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < pkt.size) sum += (pkt[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }

    @Test
    fun tcpPacketChecksumsValidate() {
        val src = NetParsers.ipv4ToBytes("10.0.0.2")!!
        val dst = NetParsers.ipv4ToBytes("93.184.216.34")!!
        val tcp = ByteArray(20)
        tcp[0] = 0x30.toByte()
        tcp[1] = 0x39.toByte()
        tcp[12] = 0x50.toByte()
        tcp[13] = 0x02.toByte()
        val pkt = NetBuilders.buildIpv4Header(src, dst, 6, 40) + tcp
        NetBuilders.finishTcpPacket(pkt)
        assertEquals(0, NetBuilders.checksum(pkt, 0, 20))
        // TCP pseudo-header + segment (checksum included) must fold to 0xFFFF.
        var sum = 0
        for (o in listOf(12, 14, 16, 18)) {
            sum += ((pkt[o].toInt() and 0xFF) shl 8) or (pkt[o + 1].toInt() and 0xFF)
        }
        sum += 6 + 20
        var i = 20
        while (i + 1 < 40) {
            sum += ((pkt[i].toInt() and 0xFF) shl 8) or (pkt[i + 1].toInt() and 0xFF)
            i += 2
        }
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        assertEquals(0xFFFF, sum)
    }
}
