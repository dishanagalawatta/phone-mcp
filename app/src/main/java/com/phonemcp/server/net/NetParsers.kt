package com.phonemcp.server.net

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure L3/L4/L7 parsers over raw bytes. Everything here is JVM-testable;
 * callers pass slices captured from the VPN tunnel or synthesized locally.
 */
object NetParsers {
    data class Ipv4Header(
        val protocol: Int,
        val srcIp: String,
        val dstIp: String,
        val headerLen: Int,
        val totalLen: Int,
    )

    data class Ipv6Header(
        val nextHeader: Int,
        val srcIp: String,
        val dstIp: String,
        val payloadLen: Int,
    )

    data class TcpSegment(
        val srcPort: Int,
        val dstPort: Int,
        val seq: Long,
        val ack: Long,
        val flags: Int,
        val headerLen: Int,
        val payload: ByteArray,
    )

    data class UdpDatagram(
        val srcPort: Int,
        val dstPort: Int,
        val payload: ByteArray,
    )

    data class DnsQuery(val name: String, val qtype: Int)

    data class HttpMessage(
        val isRequest: Boolean,
        val method: String = "",
        val path: String = "",
        val status: Int = 0,
        val headers: Map<String, String> = emptyMap(),
    )

    fun parseIpv4(packet: ByteArray): Ipv4Header? {
        if (packet.size < 20) return null
        if ((packet[0].toInt() ushr 4) != 4) return null
        val headerLen = (packet[0].toInt() and 0x0F) * 4
        if (headerLen < 20 || packet.size < headerLen) return null
        val totalLen = NetBits.u16(packet, 2)
        if (totalLen < headerLen || totalLen > packet.size) return null
        return Ipv4Header(
            protocol = packet[9].toInt() and 0xFF,
            srcIp = "${packet[12].toInt() and 0xFF}.${packet[13].toInt() and 0xFF}." +
                "${packet[14].toInt() and 0xFF}.${packet[15].toInt() and 0xFF}",
            dstIp = "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}",
            headerLen = headerLen,
            totalLen = totalLen,
        )
    }

    fun parseTcp(packet: ByteArray, ipHeaderLen: Int): TcpSegment? {
        if (packet.size < ipHeaderLen + 20) return null
        val o = ipHeaderLen
        val dataOffset = ((packet[o + 12].toInt() ushr 4) and 0x0F) * 4
        if (dataOffset < 20 || packet.size < o + dataOffset) return null
        return TcpSegment(
            srcPort = NetBits.u16(packet, o),
            dstPort = NetBits.u16(packet, o + 2),
            seq = NetBits.u32(packet, o + 4),
            ack = NetBits.u32(packet, o + 8),
            flags = packet[o + 13].toInt() and 0xFF,
            headerLen = dataOffset,
            payload = packet.copyOfRange(o + dataOffset, packet.size),
        )
    }

    fun parseUdp(packet: ByteArray, ipHeaderLen: Int): UdpDatagram? {
        if (packet.size < ipHeaderLen + 8) return null
        val o = ipHeaderLen
        val len = NetBits.u16(packet, o + 4)
        if (len < 8) return null
        return UdpDatagram(
            srcPort = NetBits.u16(packet, o),
            dstPort = NetBits.u16(packet, o + 2),
            payload = packet.copyOfRange(o + 8, packet.size),
        )
    }

    /** Minimal IPv6 header parse (no extension-header chain walk: packets carrying
     * extension headers surface as generic `ip6-<next>` flows, never misparsed). */
    fun parseIpv6(packet: ByteArray): Ipv6Header? {
        if (packet.size < 40) return null
        if ((packet[0].toInt() ushr 4) != 6) return null
        val payloadLen = NetBits.u16(packet, 4)
        if (payloadLen < 0 || 40 + payloadLen > packet.size) return null
        return Ipv6Header(
            nextHeader = packet[6].toInt() and 0xFF,
            srcIp = bytesToIpv6(packet, 8),
            dstIp = bytesToIpv6(packet, 24),
            payloadLen = payloadLen,
        )
    }

    fun isTlsClientHello(payload: ByteArray): Boolean =
        payload.size > 6 && payload[0] == 0x16.toByte() && payload[5] == 0x01.toByte()

    /**
     * Extracts the SNI hostname from a TLS ClientHello. Returns null when the
     * payload is not a ClientHello or carries no server_name extension.
     */
    fun parseSni(payload: ByteArray): String? {
        try {
            if (!isTlsClientHello(payload)) return null
            var pos = 5 + 4 // handshake header
            pos += 2 // client version
            pos += 32 // random
            if (pos >= payload.size) return null
            val sessionLen = payload[pos].toInt() and 0xFF
            pos += 1 + sessionLen
            if (pos + 2 > payload.size) return null
            val suitesLen = NetBits.u16(payload, pos)
            pos += 2 + suitesLen
            if (pos >= payload.size) return null
            val compLen = payload[pos].toInt() and 0xFF
            pos += 1 + compLen
            if (pos + 2 > payload.size) return null
            val extTotal = NetBits.u16(payload, pos)
            pos += 2
            val extEnd = minOf(pos + extTotal, payload.size)
            while (pos + 4 <= extEnd) {
                val extType = NetBits.u16(payload, pos)
                val extLen = NetBits.u16(payload, pos + 2)
                pos += 4
                if (pos + extLen > extEnd) break
                if (extType == 0) {
                    var sp = pos + 2 // skip server_name_list length
                    if (sp + 3 <= pos + extLen) {
                        val nameType = payload[sp].toInt() and 0xFF
                        val nameLen = NetBits.u16(payload, sp + 1)
                        sp += 3
                        if (nameType == 0 && sp + nameLen <= pos + extLen) {
                            val name = String(payload, sp, nameLen, Charsets.US_ASCII)
                            if (name.isNotEmpty() && name.length <= 253) return name
                        }
                    }
                    return null
                }
                pos += extLen
            }
        } catch (_: Exception) {
            return null
        }
        return null
    }

    /** Parses DNS queries (QDCOUNT entries) from a UDP payload. Pure. */
    fun parseDnsQueries(payload: ByteArray): List<DnsQuery> {
        try {
            if (payload.size < 12) return emptyList()
            val qdCount = NetBits.u16(payload, 4)
            if (qdCount == 0 || qdCount > 8) return emptyList()
            var pos = 12
            val out = mutableListOf<DnsQuery>()
            repeat(qdCount) {
                val labels = mutableListOf<String>()
                var jumps = 0
                while (pos < payload.size) {
                    val len = payload[pos].toInt() and 0xFF
                    if (len == 0) {
                        pos++
                        break
                    }
                    if (len and 0xC0 == 0xC0) {
                        // Compressed pointer: follow once, then stop (queries rarely compress).
                        if (pos + 1 >= payload.size || jumps > 4) return out
                        val ptr = ((len and 0x3F) shl 8) or (payload[pos + 1].toInt() and 0xFF)
                        val pointed = readName(payload, ptr)
                        if (pointed != null) labels.add(pointed)
                        pos += 2
                        break
                    }
                    if (len > 63 || pos + 1 + len > payload.size) return out
                    labels.add(String(payload, pos + 1, len, Charsets.US_ASCII))
                    pos += 1 + len
                    if (++jumps > 16) return out
                }
                if (pos + 4 > payload.size) return out.ifEmpty { out }
                val qtype = NetBits.u16(payload, pos)
                pos += 4 // qtype + qclass
                val name = labels.joinToString(".").trim('.')
                if (name.isNotEmpty() && name.length <= 253) out.add(DnsQuery(name, qtype))
            }
            return out
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun readName(payload: ByteArray, at: Int): String? {
        var pos = at
        val labels = mutableListOf<String>()
        var steps = 0
        while (pos < payload.size && steps++ < 8) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) break
            if (len and 0xC0 == 0xC0) return labels.joinToString(".")
            if (len > 63 || pos + 1 + len > payload.size) return null
            labels.add(String(payload, pos + 1, len, Charsets.US_ASCII))
            pos += 1 + len
        }
        return labels.joinToString(".").ifEmpty { null }
    }

    /**
     * Minimal HTTP/1.x head parser. Handles origin-form requests and status
     * responses; returns null for non-HTTP payloads. Header block is capped at
     * 32 KB to bound allocations on adversarial input.
     */
    fun parseHttp(payload: ByteArray): HttpMessage? {
        try {
            if (payload.size < 12) return null
            val headEnd = indexOfDoubleCrlf(payload) ?: return null
            if (headEnd > 32 * 1024) return null
            val head = String(payload, 0, headEnd, Charsets.ISO_8859_1)
            val lines = head.split("\r\n")
            if (lines.isEmpty()) return null
            val start = lines[0]
            val headers = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim().take(4096)
                }
                if (headers.size > 100) break
            }
            if (start.startsWith("HTTP/")) {
                val status = start.split(" ").getOrNull(1)?.toIntOrNull() ?: return null
                if (status !in 100..599) return null
                return HttpMessage(isRequest = false, status = status, headers = headers)
            }
            val parts = start.split(" ")
            if (parts.size < 3) return null
            val method = parts[0].uppercase()
            if (method !in HTTP_METHODS) return null
            if (!parts[2].startsWith("HTTP/")) return null
            return HttpMessage(isRequest = true, method = method, path = parts[1].take(4096), headers = headers)
        } catch (_: Exception) {
            return null
        }
    }

    fun ipv4ToBytes(ip: String): ByteArray? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0..3) {
            val n = parts[i].toIntOrNull() ?: return null
            if (n !in 0..255) return null
            out[i] = n.toByte()
        }
        return out
    }

    fun bytesToIpv4(b: ByteArray, at: Int = 0): String =
        "${b[at].toInt() and 0xFF}.${b[at + 1].toInt() and 0xFF}." +
            "${b[at + 2].toInt() and 0xFF}.${b[at + 3].toInt() and 0xFF}"

    /** Canonical compressed IPv6 text (RFC 5952 style: longest zero run as `::`). Pure. */
    fun bytesToIpv6(b: ByteArray, at: Int = 0): String {
        require(b.size >= at + 16)
        val g = IntArray(8) { ((b[at + it * 2].toInt() and 0xFF) shl 8) or (b[at + it * 2 + 1].toInt() and 0xFF) }
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) {
                    bestLen = j - i
                    bestStart = i
                }
                i = j
            } else {
                i++
            }
        }
        if (bestLen < 2) {
            return g.joinToString(":") { it.toString(16) }
        }
        val head = g.sliceArray(0 until bestStart).joinToString(":") { it.toString(16) }
        val tail = g.sliceArray(bestStart + bestLen until 8).joinToString(":") { it.toString(16) }
        return when {
            head.isEmpty() && tail.isEmpty() -> "::"
            head.isEmpty() -> "::$tail"
            tail.isEmpty() -> "$head::"
            else -> "$head::$tail"
        }
    }

    /** Parses full/compressed IPv6 text (incl. `::ffff:1.2.3.4` tails, `%zone` stripped). Pure. */
    fun ipv6ToBytes(ip: String): ByteArray? {
        try {
            var s = ip.trim().lowercase().substringBefore('%')
            if (s.isEmpty() || s.length > 45) return null
            if (s.contains('.')) {
                val lastColon = s.lastIndexOf(':')
                if (lastColon < 0) return null
                val v4 = ipv4ToBytes(s.substring(lastColon + 1).trim()) ?: return null
                s = s.substring(0, lastColon) +
                    ":%02x%02x:%02x%02x".format(v4[0], v4[1], v4[2], v4[3])
            }
            val halves = s.split("::")
            if (halves.size > 2) return null
            fun groups(part: String): List<Int>? {
                if (part.isEmpty()) return emptyList()
                return part.split(':').map { piece ->
                    if (piece.isEmpty() || piece.length > 4) return null
                    piece.toIntOrNull(16) ?: return null
                }
            }
            val out = ByteArray(16)
            fun stamp(groups: List<Int>, atGroup: Int) {
                groups.forEachIndexed { i, v ->
                    out[(atGroup + i) * 2] = (v ushr 8).toByte()
                    out[(atGroup + i) * 2 + 1] = v.toByte()
                }
            }
            if (halves.size == 1) {
                val full = groups(s) ?: return null
                if (full.size != 8) return null
                stamp(full, 0)
            } else {
                val left = groups(halves[0]) ?: return null
                val right = groups(halves[1]) ?: return null
                if (left.size + right.size > 7) return null
                stamp(left, 0)
                stamp(right, 8 - right.size)
            }
            return out
        } catch (_: Exception) {
            return null
        }
    }

    private fun indexOfDoubleCrlf(payload: ByteArray): Int? {
        var i = 0
        while (i + 3 < payload.size) {
            if (payload[i] == '\r'.code.toByte() && payload[i + 1] == '\n'.code.toByte() &&
                payload[i + 2] == '\r'.code.toByte() && payload[i + 3] == '\n'.code.toByte()
            ) return i
            i++
            if (i > 33 * 1024) return null
        }
        return null
    }

    private val HTTP_METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "TRACE", "CONNECT")
}

/**
 * Shared big-endian bit primitives for the net package. One home (not a private
 * copy per object) so parsers and builders cannot drift.
 */
internal object NetBits {
    fun u16(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)

    fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)
}
