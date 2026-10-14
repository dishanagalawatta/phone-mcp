package com.phonemcp.server.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure packet synthesis: header builders + checksum finishers. Everything here is
 * JVM-testable; the VPN relay calls these to write packets back into the TUN fd.
 * Parsing lives in [NetParsers]; shared bit ops in [NetBits].
 */
object NetBuilders {
    fun buildUrl(host: String?, path: String): String {
        val cleanPath = path.takeIf { it.startsWith("/") } ?: "/$path"
        return if (host.isNullOrEmpty()) "http://unknown$cleanPath" else "http://$host$cleanPath"
    }

    /** Builds a minimal IPv4 header (no options) with a correct header checksum. */
    fun buildIpv4Header(srcIp: ByteArray, dstIp: ByteArray, protocol: Int, totalLen: Int): ByteArray {
        require(srcIp.size == 4 && dstIp.size == 4)
        val buf = ByteBuffer.allocate(20).order(ByteOrder.BIG_ENDIAN)
        buf.put(0x45.toByte())
        buf.put(0)
        buf.putShort(totalLen.toShort())
        buf.putShort(0)
        buf.putShort(0)
        buf.put(64.toByte())
        buf.put(protocol.toByte())
        buf.putShort(0) // checksum placeholder
        buf.put(srcIp)
        buf.put(dstIp)
        val head = buf.array()
        val sum = checksum(head, 0, 20)
        head[10] = (sum ushr 8).toByte()
        head[11] = sum.toByte()
        return head
    }

    /** Patches a complete IPv4+TCP packet (header at [ipOffset]) with valid IP + TCP checksums. */
    fun finishTcpPacket(packet: ByteArray, ipOffset: Int = 0) {
        packet[ipOffset + 10] = 0
        packet[ipOffset + 11] = 0
        val ipSum = checksum(packet, ipOffset, 20)
        packet[ipOffset + 10] = (ipSum ushr 8).toByte()
        packet[ipOffset + 11] = ipSum.toByte()
        val totalLen = ((packet[ipOffset + 2].toInt() and 0xFF) shl 8) or (packet[ipOffset + 3].toInt() and 0xFF)
        val tcpLen = totalLen - 20
        packet[ipOffset + 36] = 0
        packet[ipOffset + 37] = 0
        var sum = pseudoSum(packet, ipOffset) + 6 + tcpLen
        var i = ipOffset + 20
        val end = i + tcpLen
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val tcpSum = sum.inv() and 0xFFFF
        packet[ipOffset + 36] = (tcpSum ushr 8).toByte()
        packet[ipOffset + 37] = tcpSum.toByte()
    }

    /** Re-stamps the IP header checksum after manual header edits (UDP checksum stays zero = none). */
    fun finishUdpPacket(packet: ByteArray, ipOffset: Int = 0) {
        packet[ipOffset + 10] = 0
        packet[ipOffset + 11] = 0
        val ipSum = checksum(packet, ipOffset, 20)
        packet[ipOffset + 10] = (ipSum ushr 8).toByte()
        packet[ipOffset + 11] = ipSum.toByte()
    }

    /** Minimal IPv6 header (no extension headers). No header checksum in v6. */
    fun buildIpv6Header(srcIp: ByteArray, dstIp: ByteArray, nextHeader: Int, payloadLen: Int): ByteArray {
        require(srcIp.size == 16 && dstIp.size == 16)
        val buf = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(0x60000000)
        buf.putShort(payloadLen.toShort())
        buf.put(nextHeader.toByte())
        buf.put(64.toByte()) // hop limit
        buf.put(srcIp)
        buf.put(dstIp)
        return buf.array()
    }

    /** Patches a complete IPv6+TCP packet with a valid TCP checksum (v6 pseudo-header). */
    fun finishTcpPacket6(packet: ByteArray, ipOffset: Int = 0) {
        val payloadLen = NetBits.u16(packet, ipOffset + 4)
        packet[ipOffset + 56] = 0
        packet[ipOffset + 57] = 0
        var sum = pseudoSum6(packet, ipOffset, 6, payloadLen)
        var i = ipOffset + 40
        val end = i + payloadLen
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        val tcpSum = sum.inv() and 0xFFFF
        packet[ipOffset + 56] = (tcpSum ushr 8).toByte()
        packet[ipOffset + 57] = tcpSum.toByte()
    }

    /**
     * Patches a complete IPv6+UDP packet with a valid UDP checksum.
     * Unlike IPv4, a zero UDP checksum is ILLEGAL in v6 (dropped as corrupt),
     * so this always computes one (RFC 8200 §8.1).
     */
    fun finishUdpPacket6(packet: ByteArray, ipOffset: Int = 0) {
        val payloadLen = NetBits.u16(packet, ipOffset + 4)
        packet[ipOffset + 46] = 0
        packet[ipOffset + 47] = 0
        var sum = pseudoSum6(packet, ipOffset, 17, payloadLen)
        var i = ipOffset + 40
        val end = i + payloadLen
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (packet[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        var udpSum = sum.inv() and 0xFFFF
        if (udpSum == 0) udpSum = 0xFFFF // zero means "none" — never emit it in v6
        packet[ipOffset + 46] = (udpSum ushr 8).toByte()
        packet[ipOffset + 47] = udpSum.toByte()
    }

    /** IPv6 pseudo-header sum: src(16) + dst(16) + length(32) + zeros(24) + next header(8). */
    private fun pseudoSum6(packet: ByteArray, ipOffset: Int, nextHeader: Int, l4Len: Int): Int {
        var sum = 0
        var i = ipOffset + 8
        val addrEnd = ipOffset + 40
        while (i + 1 < addrEnd) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        sum += (l4Len ushr 16) and 0xFFFF
        sum += l4Len and 0xFFFF
        sum += nextHeader and 0xFF
        return sum
    }

    /** Standard RFC 1071 one's-complement checksum over [length] bytes at [offset]. */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun pseudoSum(packet: ByteArray, ipOffset: Int): Int {
        var sum = 0
        for (o in listOf(ipOffset + 12, ipOffset + 14, ipOffset + 16, ipOffset + 18)) {
            sum += ((packet[o].toInt() and 0xFF) shl 8) or (packet[o + 1].toInt() and 0xFF)
        }
        return sum
    }
}
