package com.phonemcp.server.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.os.SystemClock
import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.net.Blocklist
import com.phonemcp.server.net.ConnectionRecord
import com.phonemcp.server.net.DnsEvent
import com.phonemcp.server.net.FirewallEngine
import com.phonemcp.server.net.FirewallPolicy
import com.phonemcp.server.net.FlowKey
import com.phonemcp.server.net.HttpTransaction
import com.phonemcp.server.net.NetBuilders
import com.phonemcp.server.net.NetParsers
import com.phonemcp.server.net.PacketRecord
import com.phonemcp.server.net.Verdict
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of network-inspection runtime state: live flow table, capped
 * packet/HTTP/DNS rings, firewall policy, and capture flags.
 *
 * Battery stance: the VPN reader thread blocks on the TUN fd (no polling);
 * [ingest] does one linear parse per packet and drops work early on firewall
 * deny. Rings are hard-capped so a long CI run cannot grow memory.
 */
@Singleton
class NetInspectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: LogBuffer,
) {
    private val flows = ConcurrentHashMap<FlowKey, MutableConn>()
    private val packets: ArrayDeque<PacketRecord> = ArrayDeque()
    private val httpLog: ArrayDeque<HttpTransaction> = ArrayDeque()
    private val dnsLog: ArrayDeque<DnsEvent> = ArrayDeque()
    private val lock = Any()

    @Volatile var capturing: Boolean = false
    @Volatile var vpnActive: Boolean = false
    @Volatile var wantCapture: Boolean = false
    @Volatile var policy: FirewallPolicy = FirewallPolicy()
    @Volatile var customBlocklist: Set<String> = emptySet()

    val droppedByPolicy = AtomicLong(0)
    val packetsSeen = AtomicLong(0)
    val captureStartedMs = AtomicLong(0)
    private val lastLogMs = AtomicLong(0)

    private data class MutableConn(
        var uid: Int = -1,
        var packageName: String? = null,
        var host: String? = null,
        var sni: String? = null,
        var bytesUp: Long = 0,
        var bytesDown: Long = 0,
        var packetsUp: Long = 0,
        var packetsDown: Long = 0,
        var firstSeenMs: Long = 0,
        var lastSeenMs: Long = 0,
        var blocked: Boolean = false,
        var blockReason: String? = null,
    )

    data class IngestResult(val verdict: Verdict, val reason: String?)

    /**
     * One raw IPv4 datagram from the TUN fd (device -> network direction).
     * Parses L3/L4 once, applies the firewall, updates telemetry, and stores
     * the packet when [capturing] is on. Returns the verdict so the VPN loop
     * knows whether to forward or drop.
     */
    fun ingest(ipPacket: ByteArray, tsMs: Long = System.currentTimeMillis(), uidHint: Int = -1): IngestResult {
        packetsSeen.incrementAndGet()
        if (ipPacket.isEmpty()) return IngestResult(Verdict.ALLOW, null)
        // L3 dispatch: IPv4 has a variable header, IPv6 is fixed 40 bytes.
        // Anything else (or a v6 packet with extension headers in the L4 slot)
        // degrades to a generic flow — recorded, never misparsed, never blocked.
        val srcIp: String
        val dstIp: String
        val l4proto: Int
        val l4off: Int
        val family: String
        when ((ipPacket[0].toInt() ushr 4)) {
            4 -> {
                val ip = NetParsers.parseIpv4(ipPacket) ?: return IngestResult(Verdict.ALLOW, null)
                srcIp = ip.srcIp
                dstIp = ip.dstIp
                l4proto = ip.protocol
                l4off = ip.headerLen
                family = ""
            }
            6 -> {
                val ip = NetParsers.parseIpv6(ipPacket) ?: return IngestResult(Verdict.ALLOW, null)
                srcIp = ip.srcIp
                dstIp = ip.dstIp
                l4proto = ip.nextHeader
                l4off = 40
                family = "6"
            }
            else -> return IngestResult(Verdict.ALLOW, null)
        }
        val proto: String
        val srcPort: Int
        val dstPort: Int
        val payload: ByteArray
        when (l4proto) {
            6 -> {
                val tcp = NetParsers.parseTcp(ipPacket, l4off) ?: return IngestResult(Verdict.ALLOW, null)
                proto = "tcp$family"
                srcPort = tcp.srcPort
                dstPort = tcp.dstPort
                payload = tcp.payload
            }
            17 -> {
                val udp = NetParsers.parseUdp(ipPacket, l4off) ?: return IngestResult(Verdict.ALLOW, null)
                proto = "udp$family"
                srcPort = udp.srcPort
                dstPort = udp.dstPort
                payload = udp.payload
            }
            else -> {
                proto = "ip$family-${l4proto}"
                srcPort = 0
                dstPort = 0
                payload = ByteArray(0)
            }
        }

        // L7 metadata: DNS queries, TLS SNI, plaintext HTTP head (family-agnostic).
        var host: String? = null
        var sni: String? = null
        if (l4proto == 17 && (dstPort == 53 || srcPort == 53) && payload.isNotEmpty()) {
            val queries = NetParsers.parseDnsQueries(payload)
            if (queries.isNotEmpty()) {
                host = queries.first().name
                synchronized(lock) {
                    for (q in queries) {
                        dnsLog.addLast(DnsEvent(tsMs, q.name, q.qtype, null))
                        while (dnsLog.size > MAX_DNS) dnsLog.removeFirst()
                    }
                }
            }
        }
        if (proto.startsWith("tcp") && payload.size > 6 && NetParsers.isTlsClientHello(payload)) {
            sni = NetParsers.parseSni(payload)
            if (sni != null) host = sni
        }
        var http: NetParsers.HttpMessage? = null
        if (proto.startsWith("tcp") && payload.size >= 12) {
            http = NetParsers.parseHttp(payload)
            val h = http?.headers?.get("host")
            if (host == null && !h.isNullOrEmpty()) host = h
        }

        val key = FlowKey(proto, srcIp, srcPort, dstIp, dstPort)
        val pkg = if (uidHint >= 0) packageForUid(uidHint) else null
        val (verdict, reason) = FirewallEngine.decide(policy, host, dstIp, dstPort, pkg)
        val blocked = verdict != Verdict.ALLOW
        if (blocked) droppedByPolicy.incrementAndGet()

        val conn = flows.computeIfAbsent(key) {
            MutableConn(firstSeenMs = tsMs, uid = uidHint, packageName = pkg)
        }
        synchronized(conn) {
            if (conn.firstSeenMs == 0L) conn.firstSeenMs = tsMs
            conn.lastSeenMs = tsMs
            if (uidHint >= 0 && conn.uid < 0) {
                conn.uid = uidHint
                conn.packageName = pkg
            }
            if (host != null && conn.host == null) conn.host = host
            if (sni != null && conn.sni == null) conn.sni = sni
            conn.bytesUp += ipPacket.size
            conn.packetsUp++
            if (blocked) {
                conn.blocked = true
                if (conn.blockReason == null) conn.blockReason = reason
            }
        }

        if (http != null && http.isRequest) {
            val url = NetBuilders.buildUrl(http.headers["host"], http.path.ifEmpty { "/" })
            synchronized(lock) {
                httpLog.addLast(
                    HttpTransaction(
                        tsMs = tsMs, method = http.method, url = url, host = http.headers["host"],
                        requestHeaders = http.headers, packageName = conn.packageName,
                    ),
                )
                while (httpLog.size > MAX_HTTP) httpLog.removeFirst()
            }
        }

        if (capturing) {
            val rec = PacketRecord(tsMs, conn.uid, conn.packageName, ipPacket.copyOf())
            synchronized(lock) {
                packets.addLast(rec)
                while (packets.size > MAX_PACKETS) packets.removeFirst()
            }
        }

        // Throttled observability: at most one ring-log line per 30 s from the hot path.
        val now = SystemClock.elapsedRealtime()
        if (blocked && now - lastLogMs.get() > 30_000) {
            lastLogMs.set(now)
            log.append("net blocked $proto ${dstIp}:$dstPort (${reason?.take(80)})")
        }
        if (flows.size > MAX_FLOWS) {
            // Evict the oldest flows; keeps the table bounded on long runs.
            val oldest = flows.entries.sortedBy { it.value.lastSeenMs }.take(flows.size - MAX_FLOWS)
            for ((k, _) in oldest) flows.remove(k)
        }
        return IngestResult(verdict, reason)
    }

    fun snapshot(limit: Int = 200, packageFilter: String? = null, blockedOnly: Boolean = false): List<ConnectionRecord> {
        val rows = flows.entries.map { (key, c) ->
            synchronized(c) {
                ConnectionRecord(
                    key = key, uid = c.uid, packageName = c.packageName, host = c.host, sni = c.sni,
                    bytesUp = c.bytesUp, bytesDown = c.bytesDown, packetsUp = c.packetsUp,
                    packetsDown = c.packetsDown, firstSeenMs = c.firstSeenMs, lastSeenMs = c.lastSeenMs,
                    blocked = c.blocked, blockReason = c.blockReason,
                )
            }
        }
        return rows
            .filter { packageFilter.isNullOrEmpty() || it.packageName == packageFilter }
            .filter { !blockedOnly || it.blocked }
            .sortedByDescending { it.lastSeenMs }
            .take(limit.coerceIn(1, 1000))
    }

    fun packetsSnapshot(max: Int = PcapCap): List<PacketRecord> = synchronized(lock) {
        packets.toList().takeLast(max.coerceIn(1, PcapCap))
    }

    fun httpSnapshot(max: Int = 2000): List<HttpTransaction> = synchronized(lock) {
        httpLog.toList().takeLast(max.coerceIn(1, 2000))
    }

    fun dnsSnapshot(max: Int = 500): List<DnsEvent> = synchronized(lock) {
        dnsLog.toList().takeLast(max.coerceIn(1, 2000))
    }

    fun flowCount(): Int = flows.size

    /** Records a synthesized inbound packet (tunnel write-back) against its flow. */
    fun addDownstream(key: FlowKey, bytes: Long) {
        val conn = flows[key] ?: return
        synchronized(conn) {
            conn.bytesDown += bytes
            conn.packetsDown++
            conn.lastSeenMs = System.currentTimeMillis()
        }
    }

    fun clearCapture() {
        synchronized(lock) {
            packets.clear()
            httpLog.clear()
            dnsLog.clear()
        }
        flows.clear()
        packetsSeen.set(0)
        droppedByPolicy.set(0)
    }

    fun packageForUid(uid: Int): String? {
        if (uid < 0) return null
        return try {
            context.packageManager.getPackagesForUid(uid)?.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }

    /** Per-app byte counters from TrafficStats (no special permission). Sorted desc by total. */
    fun usageSnapshot(limit: Int = 100): List<Map<String, Any?>> {
        val pm: PackageManager = context.packageManager
        val rows = mutableListOf<Map<String, Any?>>()
        // Enumerate installed packages without QUERY_ALL_PACKAGES: getPackagesForUid
        // needs a uid; instead walk running UIDs via TrafficStats deltas is not
        // enumerable, so report the well-known UID range + resolve names best-effort.
        val seenUids = linkedSetOf<Int>()
        // Always include our own uid + any uids behind live flows.
        seenUids.add(android.os.Process.myUid())
        for (key in flows.keys()) {
            flows[key]?.uid?.takeIf { it >= 0 }?.let { seenUids.add(it) }
        }
        // Probe the app-uid band (10000..10999 covers third-party apps on most devices).
        for (uid in 10000..10999) {
            val rx = try { TrafficStats.getUidRxBytes(uid) } catch (_: Exception) { TrafficStats.UNSUPPORTED.toLong() }
            val tx = try { TrafficStats.getUidTxBytes(uid) } catch (_: Exception) { TrafficStats.UNSUPPORTED.toLong() }
            if (rx > 0 || tx > 0) seenUids.add(uid)
            if (seenUids.size > 400) break
        }
        for (uid in seenUids) {
            val rx = try { TrafficStats.getUidRxBytes(uid) } catch (_: Exception) { -1L }
            val tx = try { TrafficStats.getUidTxBytes(uid) } catch (_: Exception) { -1L }
            if (rx < 0 && tx < 0) continue
            val pkg = try { pm.getPackagesForUid(uid)?.firstOrNull() } catch (_: Exception) { null }
            rows.add(mapOf("uid" to uid, "package" to pkg, "rxBytes" to maxOf(rx, 0), "txBytes" to maxOf(tx, 0)))
        }
        return rows.sortedByDescending { ((it["rxBytes"] as Long) + (it["txBytes"] as Long)) }
            .take(limit.coerceIn(1, 500))
    }

    fun deviceTotals(): Map<String, Long> = mapOf(
        "totalRx" to try { TrafficStats.getTotalRxBytes() } catch (_: Exception) { -1L },
        "totalTx" to try { TrafficStats.getTotalTxBytes() } catch (_: Exception) { -1L },
        "mobileRx" to try { TrafficStats.getMobileRxBytes() } catch (_: Exception) { -1L },
        "mobileTx" to try { TrafficStats.getMobileTxBytes() } catch (_: Exception) { -1L },
    )

    fun checkHost(host: String): Map<String, Any> = Blocklist.check(host, customBlocklist)

    companion object {
        const val MAX_FLOWS = 4000
        const val MAX_PACKETS = 20_000
        const val MAX_HTTP = 2000
        const val MAX_DNS = 1000
        const val PcapCap = 20_000
    }
}
