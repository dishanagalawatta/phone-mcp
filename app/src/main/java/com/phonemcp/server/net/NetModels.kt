package com.phonemcp.server.net

/**
 * Pure-Kotlin network-inspection models (no android.* imports — JVM-testable).
 *
 * A [FlowKey] identifies one L3/L4 conversation. [ConnectionRecord] is the live
 * telemetry row surfaced by `net_connections`; [PacketRecord] is the raw unit
 * stored for PCAP/PCAPNG export; [HttpTransaction] feeds HAR export.
 */
data class FlowKey(
    val proto: String,
    val srcIp: String,
    val srcPort: Int,
    val dstIp: String,
    val dstPort: Int,
) {
    fun canonical(): String = "$proto $srcIp:$srcPort -> $dstIp:$dstPort"
}

enum class Verdict { ALLOW, BLOCK_POLICY, BLOCK_BLOCKLIST }

data class ConnectionRecord(
    val key: FlowKey,
    val uid: Int = -1,
    val packageName: String? = null,
    val host: String? = null,
    val sni: String? = null,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val packetsUp: Long = 0,
    val packetsDown: Long = 0,
    val firstSeenMs: Long = 0,
    val lastSeenMs: Long = 0,
    val blocked: Boolean = false,
    val blockReason: String? = null,
)

data class PacketRecord(
    val tsMs: Long,
    val uid: Int = -1,
    val packageName: String? = null,
    /** Raw IPv4 datagram bytes (LINKTYPE_RAW payload). */
    val ipPacket: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketRecord) return false
        return tsMs == other.tsMs && uid == other.uid &&
            packageName == other.packageName && ipPacket.contentEquals(other.ipPacket)
    }

    override fun hashCode(): Int {
        var result = tsMs.hashCode()
        result = 31 * result + uid
        result = 31 * result + (packageName?.hashCode() ?: 0)
        result = 31 * result + ipPacket.contentHashCode()
        return result
    }
}

data class HttpTransaction(
    val tsMs: Long,
    val method: String,
    val url: String,
    val host: String?,
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBodyBytes: Int = 0,
    val status: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBodyBytes: Int = 0,
    val packageName: String? = null,
)

data class DnsEvent(
    val tsMs: Long,
    val query: String,
    val qtype: Int,
    val packageName: String? = null,
)

/**
 * Agent-controlled filter policy. Empty lists match nothing; when [defaultAllow]
 * is true everything not listed passes, otherwise everything not listed drops.
 */
data class FirewallPolicy(
    val defaultAllow: Boolean = true,
    val blockedHosts: List<String> = emptyList(),
    val blockedIpRanges: List<String> = emptyList(),
    val blockedPorts: List<Int> = emptyList(),
    val blockedPackages: List<String> = emptyList(),
    val allowedHosts: List<String> = emptyList(),
) {
    companion object {
        const val MAX_ENTRIES = 500
    }
}
