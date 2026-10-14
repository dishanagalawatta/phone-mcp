package com.phonemcp.server.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Capture-file writers: classic PCAP, next-gen PCAPNG, HTTP Archive (HAR 1.2),
 * and SSLKEYLOGFILE correlation store. Pure Kotlin (JVM-testable); Android code
 * only moves the resulting bytes into [com.phonemcp.server.domain.MediaStore].
 */
object PcapIO {
    const val LINKTYPE_RAW = 101
    const val MAX_PACKETS_PER_EXPORT = 20_000
    const val MAX_HAR_ENTRIES = 2_000

    /** Classic libpcap global header + per-packet records (LINKTYPE_RAW IPv4). */
    fun writePcap(packets: List<PacketRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        val head = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
        head.putInt(0xa1b2c3d4.toInt())
        head.putShort(2)
        head.putShort(4)
        head.putInt(0)
        head.putInt(0)
        head.putInt(65535)
        head.putInt(LINKTYPE_RAW)
        out.write(head.array())
        var count = 0
        for (p in packets) {
            if (count++ >= MAX_PACKETS_PER_EXPORT) break
            val data = p.ipPacket
            val rec = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            rec.putInt((p.tsMs / 1000).toInt())
            rec.putInt(((p.tsMs % 1000) * 1000).toInt())
            rec.putInt(data.size)
            rec.putInt(data.size)
            out.write(rec.array())
            out.write(data)
        }
        return out.toByteArray()
    }

    /**
     * Minimal PCAPNG: Section Header + Interface Description + Enhanced Packets,
     * little-endian. Wireshark/tshark open this directly; the packet comment
     * option embeds package name + uid when present.
     */
    fun writePcapNg(packets: List<PacketRecord>): ByteArray {
        val out = ByteArrayOutputStream()
        fun block(type: Int, body: ByteArray) {
            val total = 12 + body.size
            val head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            head.putInt(type)
            head.putInt(total)
            out.write(head.array())
            out.write(body)
            val tail = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            tail.putInt(total)
            out.write(tail.array())
        }
        // SHB
        val shb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        shb.putInt(0x1A2B3C4D)
        shb.putShort(1)
        shb.putShort(0)
        shb.putLong(-1L)
        block(0x0A0D0D0A, shb.array())
        // IDB: linktype RAW, snaplen 65535
        val idb = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        idb.putShort(LINKTYPE_RAW.toShort())
        idb.putShort(0)
        idb.putInt(65535)
        block(0x00000001, idb.array())
        var count = 0
        for (p in packets) {
            if (count++ >= MAX_PACKETS_PER_EXPORT) break
            val comment = buildString {
                if (p.packageName != null) append(p.packageName)
                if (p.uid >= 0) {
                    if (isNotEmpty()) append(" ")
                    append("uid=${p.uid}")
                }
            }
            val commentBytes = comment.toByteArray(Charsets.UTF_8)
            val paddedData = ((p.ipPacket.size + 3) / 4) * 4
            val paddedComment = ((commentBytes.size + 3) / 4) * 4
            val optsLen = if (comment.isNotEmpty()) 4 + paddedComment else 0
            val body = ByteBuffer.allocate(20 + paddedData + optsLen).order(ByteOrder.LITTLE_ENDIAN)
            body.putInt(0) // interface id
            val tsMicros = p.tsMs * 1000L
            body.putInt((tsMicros ushr 32).toInt())
            body.putInt((tsMicros and 0xFFFFFFFFL).toInt())
            body.putInt(p.ipPacket.size)
            body.putInt(p.ipPacket.size)
            body.put(p.ipPacket)
            repeat(paddedData - p.ipPacket.size) { body.put(0) }
            if (comment.isNotEmpty()) {
                body.putShort(1) // opt_comment
                body.putShort(commentBytes.size.toShort())
                body.put(commentBytes)
                repeat(paddedComment - commentBytes.size) { body.put(0) }
            }
            block(0x00000006, body.array())
        }
        return out.toByteArray()
    }
}

object HarBuilder {
    /** HAR 1.2 log with request/response lifecycles. Pure string building (no JSON dep in net/). */
    fun build(entries: List<HttpTransaction>, creatorVersion: String = "0.1.0"): String {
        val capped = entries.take(PcapIO.MAX_HAR_ENTRIES)
        val sb = StringBuilder(4096)
        sb.append("{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"phone-mcp\",\"version\":")
        sb.append(jsonStr(creatorVersion))
        sb.append("},\"entries\":[")
        capped.forEachIndexed { index, e ->
            if (index > 0) sb.append(',')
            sb.append("{\"startedDateTime\":")
            sb.append(jsonStr(iso8601(e.tsMs)))
            sb.append(",\"time\":0,\"request\":{\"method\":")
            sb.append(jsonStr(e.method))
            sb.append(",\"url\":")
            sb.append(jsonStr(e.url))
            sb.append(",\"headers\":")
            appendHeaders(sb, e.requestHeaders)
            sb.append(",\"queryString\":[],\"bodySize\":")
            sb.append(e.requestBodyBytes)
            sb.append("},\"response\":{\"status\":")
            sb.append(e.status)
            sb.append(",\"statusText\":\"\",\"headers\":")
            appendHeaders(sb, e.responseHeaders)
            sb.append(",\"content\":{\"mimeType\":")
            sb.append(jsonStr(e.responseHeaders["content-type"] ?: "application/octet-stream"))
            sb.append(",\"size\":")
            sb.append(e.responseBodyBytes)
            sb.append("}},\"timings\":{\"send\":0,\"wait\":0,\"receive\":0}")
            if (e.packageName != null) {
                sb.append(",\"_package\":")
                sb.append(jsonStr(e.packageName))
            }
            sb.append('}')
        }
        sb.append("]}}")
        return sb.toString()
    }

    private fun appendHeaders(sb: StringBuilder, headers: Map<String, String>) {
        sb.append('[')
        var first = true
        for ((k, v) in headers.entries.take(100)) {
            if (!first) sb.append(',')
            first = false
            sb.append("{\"name\":")
            sb.append(jsonStr(k))
            sb.append(",\"value\":")
            sb.append(jsonStr(v))
            sb.append('}')
        }
        sb.append(']')
    }

    private fun jsonStr(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s.take(4096)) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun iso8601(tsMs: Long): String {
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = tsMs
        return "%04d-%02d-%02dT%02d:%02d:%02d.%03dZ".format(
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND),
            cal.get(java.util.Calendar.MILLISECOND),
        )
    }
}

/**
 * SSLKEYLOGFILE correlation store.
 *
 * Security stance: this app deliberately performs NO on-device TLS interception
 * (no CA install, no MITM proxy — that would break the TLS/TOFU model in
 * ADR-0010 and invite Play-policy removal). The store only holds keylog lines
 * the agent explicitly imports (e.g. `SSLKEYLOGFILE` from a desktop browser it
 * owns) so captured flows can be correlated in Wireshark downstream, and can
 * export them back verbatim.
 */
object KeylogStore {
    private val lines = java.util.concurrent.CopyOnWriteArrayList<String>()

    fun importLines(raw: String): Int {
        var added = 0
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            if (t.length > 4096) continue
            // Accept standard labels only; reject anything else to bound abuse.
            if (t.startsWith("CLIENT_RANDOM ") || t.startsWith("CLIENT_HANDSHAKE_TRAFFIC_SECRET ") ||
                t.startsWith("SERVER_HANDSHAKE_TRAFFIC_SECRET ") || t.startsWith("CLIENT_TRAFFIC_SECRET_0 ") ||
                t.startsWith("SERVER_TRAFFIC_SECRET_0 ")
            ) {
                if (lines.size < 10_000) {
                    lines.add(t)
                    added++
                }
            }
        }
        return added
    }

    fun export(): String = lines.joinToString("\n", postfix = if (lines.isEmpty()) "" else "\n")

    fun size(): Int = lines.size

    fun clear() = lines.clear()
}
