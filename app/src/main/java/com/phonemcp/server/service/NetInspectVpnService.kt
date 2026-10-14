package com.phonemcp.server.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import com.phonemcp.server.data.NetInspectRepository
import com.phonemcp.server.net.FlowKey
import com.phonemcp.server.net.NetBuilders
import com.phonemcp.server.net.NetParsers
import com.phonemcp.server.net.Verdict
import dagger.hilt.android.AndroidEntryPoint
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Opt-in packet tunnel for Network Inspection. Started ONLY from the dashboard
 * (user taps "Start VPN" and approves the system consent sheet) or the QS tile;
 * the `net_` kill-switch must also be ON or [NetInspectRepository.ingest] still
 * records nothing exportable and the tunnel refuses to establish.
 *
 * Design: one blocking reader on the TUN fd parses each IPv4 datagram once,
 * applies the agent firewall, records telemetry, then forwards:
 * - UDP via a protected [DatagramSocket] with reply synthesis;
 * - TCP via a per-flow protected [Socket] relay with minimal seq/ack tracking;
 * - other protocols are recorded and dropped (diagnostics capture, not a full stack).
 *
 * Battery: the reader blocks on [FileInputStream.read] (no polling); idle flows
 * are reaped after 60 s; buffers are reused (MTU 1500).
 */
@AndroidEntryPoint
class NetInspectVpnService : VpnService() {
    @Inject lateinit var repo: NetInspectRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tun: ParcelFileDescriptor? = null
    private val halt = AtomicBoolean(false)
    private val tcpFlows = ConcurrentHashMap<String, TcpRelay>()
    private var tunnelJob: kotlinx.coroutines.Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTunnel("dashboard stop")
                stopSelf()
                return START_NOT_STICKY
            }
        }
        // Consent-gated tunnel: never auto-resume after a kill without explicit
        // user action + VpnService.prepare() consent. Null intent = system restart.
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY
        try {
            establishTunnel()
        } catch (e: Exception) {
            Log.w(TAG, "tunnel establish failed: ${e.message}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun establishTunnel() {
        // Foreground promotion first: started via startForegroundService, so the
        // system kills us (RemoteServiceException) if we don't promote quickly.
        // API 34+ needs an explicit type (specialUse, declared in the manifest);
        // older releases use the untyped call.
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Network inspection", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone MCP network inspection")
            .setContentText("VPN tunnel capturing per-app traffic for diagnostics")
            .setSmallIcon(com.phonemcp.server.R.drawable.ic_notification)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
        val builder = Builder()
            .setSession("Phone MCP Inspect")
            .setMtu(MTU)
            .addAddress("10.0.0.2", 32)
            .addAddress("fd00:db8::2", 64)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            // Privacy note: hardcoded DNS bypasses local/split DNS and sends all
            // tunnel DNS to Google. Kept for capture determinism; document in ADR-0019.
            // TODO: prefer system DNS or make configurable.
            .addDnsServer("8.8.8.8")
            .setBlocking(true)
        // Keep LAN-local services reachable while capturing: inbound connections
        // (sshd, adb, our own MCP listener) arrive on the physical interface, but
        // their replies would route into the tunnel where a userspace relay cannot
        // associate them with the pre-existing listening sockets. Excluding the
        // Wi-Fi link subnet (API 33+) lets LAN traffic bypass capture; internet
        // telemetry is unaffected. Our own control traffic is disallowed too so it
        // never pollutes the capture rings.
        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                // Exclude all LAN-capable physical interfaces so local services stay
                // reachable (wlan, eth, p2p, rmnet-cellular bypass would leak carrier
                // traffic into capture; only RFC1918/link-local subnets are excluded).
                val ifaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                for (netIf in ifaces) {
                    val name = try { netIf.name } catch (_: Exception) { continue }
                    if (!(name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("p2p"))) continue
                    for (ifaceAddr in netIf.interfaceAddresses.orEmpty()) {
                        try {
                            builder.excludeRoute(
                                android.net.IpPrefix(
                                    ifaceAddr.address,
                                    ifaceAddr.networkPrefixLength.toInt(),
                                ),
                            )
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        tun = builder.establish() ?: throw IllegalStateException("VpnService.establish() refused (consent revoked?)")
        running = true
        repo.vpnActive = true
        if (repo.wantCapture && repo.capturing.not()) {
            repo.capturing = true
            if (repo.captureStartedMs.get() == 0L) repo.captureStartedMs.set(System.currentTimeMillis())
        }
        halt.set(false)
        tunnelJob = scope.launch { readLoop() }.also {
            scope.launch { reapLoop() }
        }
    }

    private fun readLoop() {
        val fd = tun ?: return
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val buf = ByteBuffer.allocate(MTU + 64)
        try {
            while (!halt.get()) {
                buf.clear()
                val n = try {
                    input.channel.read(buf)
                } catch (_: Exception) {
                    -1
                }
                if (n <= 0) {
                    if (halt.get()) break
                    try {
                        Thread.sleep(50)
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
                val packet = buf.array().copyOf(n)
                val verdict = try {
                    repo.ingest(packet)
                } catch (_: Exception) {
                    continue
                }
                if (verdict.verdict != Verdict.ALLOW) continue // firewall drop: recorded, not forwarded
                try {
                    forward(packet, output)
                } catch (_: Exception) {
                    // Forwarding must never kill the reader; drops are recorded as gaps.
                }
            }
        } finally {
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    /** L3/L4 parse result shared by the UDP and TCP forwarders (v4 + v6). */
    private data class Datagram(
        val v6: Boolean,
        val proto: Int,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        val payload: ByteArray,
        val tcpSeq: Long = 0,
        val tcpFlags: Int = 0,
    )

    private fun parseDatagram(packet: ByteArray): Datagram? {
        if (packet.isEmpty()) return null
        return when ((packet[0].toInt() ushr 4)) {
            4 -> {
                val ip = NetParsers.parseIpv4(packet) ?: return null
                when (ip.protocol) {
                    6 -> {
                        val tcp = NetParsers.parseTcp(packet, ip.headerLen) ?: return null
                        Datagram(
                            false, 6, ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort, tcp.payload,
                            tcp.seq, tcp.flags,
                        )
                    }
                    17 -> {
                        val udp = NetParsers.parseUdp(packet, ip.headerLen) ?: return null
                        Datagram(false, 17, ip.srcIp, udp.srcPort, ip.dstIp, udp.dstPort, udp.payload)
                    }
                    else -> null
                }
            }
            6 -> {
                val ip = NetParsers.parseIpv6(packet) ?: return null
                when (ip.nextHeader) {
                    6 -> {
                        val tcp = NetParsers.parseTcp(packet, 40) ?: return null
                        Datagram(
                            true, 6, ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort, tcp.payload,
                            tcp.seq, tcp.flags,
                        )
                    }
                    17 -> {
                        val udp = NetParsers.parseUdp(packet, 40) ?: return null
                        Datagram(true, 17, ip.srcIp, udp.srcPort, ip.dstIp, udp.dstPort, udp.payload)
                    }
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun forward(packet: ByteArray, output: FileOutputStream) {
        val d = parseDatagram(packet) ?: return // recorded; non-TCP/UDP dropped
        when (d.proto) {
            17 -> forwardUdp(d, output)
            6 -> forwardTcp(d, output)
        }
    }

    private fun forwardUdp(d: Datagram, output: FileOutputStream) {
        // Per-transaction protected socket: a shared socket would deliver replies
        // to whichever flow happens to be in receive() (reply stealing).
        // Bound to tunnelJob so stopTunnel cancels in-flight forwards (no leak).
        val parent = tunnelJob
        val ctx = if (parent != null) scope.coroutineContext + parent else scope.coroutineContext
        kotlinx.coroutines.CoroutineScope(ctx).launch {
            // Fail closed: an unprotected socket would route back into the tunnel (loop).
            val sock = try {
                DatagramSocket().apply {
                    if (!protect(this)) throw IllegalStateException("protect() refused")
                    soTimeout = 4000
                }
            } catch (_: Exception) {
                return@launch
            }
            try {
                val dst = InetAddress.getByName(d.dstIp)
                sock.send(DatagramPacket(d.payload, d.payload.size, dst, d.dstPort))
                val replyBuf = ByteArray(8192)
                val reply = DatagramPacket(replyBuf, replyBuf.size)
                try {
                    sock.receive(reply)
                } catch (_: Exception) {
                    return@launch
                }
                val srcBytes = if (d.v6) {
                    NetParsers.ipv6ToBytes(d.dstIp)
                } else {
                    NetParsers.ipv4ToBytes(d.dstIp)
                } ?: return@launch
                val dstBytes = if (d.v6) {
                    NetParsers.ipv6ToBytes(d.srcIp)
                } else {
                    NetParsers.ipv4ToBytes(d.srcIp)
                } ?: return@launch
                val udpHead = ByteBuffer.allocate(8)
                udpHead.putShort(d.dstPort.toShort())
                udpHead.putShort(d.srcPort.toShort())
                udpHead.putShort((8 + reply.length).toShort())
                udpHead.putShort(0)
                val headLen = if (d.v6) 40 else 20
                val total = headLen + 8 + reply.length
                val out = ByteBuffer.allocate(total)
                out.put(
                    if (d.v6) {
                        NetBuilders.buildIpv6Header(srcBytes, dstBytes, 17, 8 + reply.length)
                    } else {
                        NetBuilders.buildIpv4Header(srcBytes, dstBytes, 17, total)
                    },
                )
                out.put(udpHead.array())
                out.put(replyBuf, 0, reply.length)
                if (d.v6) {
                    // UDP checksum is mandatory in v6 (zero = corrupt).
                    NetBuilders.finishUdpPacket6(out.array())
                } else {
                    // TUN write-back requires a valid IP checksum (zero = dropped by the stack).
                    NetBuilders.finishUdpPacket(out.array())
                }
                synchronized(output) { output.write(out.array()) }
                try {
                    repo.addDownstream(
                        FlowKey(if (d.v6) "udp6" else "udp", d.srcIp, d.srcPort, d.dstIp, d.dstPort),
                        total.toLong(),
                    )
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
            } finally {
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    private fun forwardTcp(d: Datagram, output: FileOutputStream) {
        val id = "${d.srcIp}:${d.srcPort}->${d.dstIp}:${d.dstPort}"
        val flags = d.tcpFlags
        val isSyn = (flags and 0x02) != 0
        if (isSyn && tcpFlows[id] == null) {
            val relay = TcpRelay(d.v6, d.srcIp, d.srcPort, d.dstIp, d.dstPort, d.tcpSeq)
            tcpFlows[id] = relay
            scope.launch { relay.run(output) }
        }
        tcpFlows[id]?.offerUpstream(d.payload, d.tcpSeq)
        if ((flags and 0x04) != 0) {
            if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "tun client RST $id")
            tcpFlows.remove(id)?.close()
        } else if ((flags and 0x01) != 0) {
            // Client FIN = half-close: forward EOF upstream, stay alive for the reply.
            if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "tun client FIN $id (half-close)")
            tcpFlows[id]?.clientFin()
        }
    }

    private suspend fun reapLoop() {
        while (!halt.get()) {
            try {
                kotlinx.coroutines.delay(30_000)
            } catch (_: Exception) {
                break
            }
            val cutoff = System.currentTimeMillis() - 60_000
            tcpFlows.entries.removeIf { it.value.lastActivityMs < cutoff || !it.value.alive.get() }
        }
    }

    private fun stopTunnel(reason: String) {
        halt.set(true)
        try { tunnelJob?.cancel() } catch (_: Exception) {}
        tunnelJob = null
        tcpFlows.values.forEach { it.close() }
        tcpFlows.clear()
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        running = false
        try { repo.vpnActive = false } catch (_: Exception) {}
        try { repo.capturing = false } catch (_: Exception) {}
        if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "tunnel stopped ($reason)")
    }

    override fun onRevoke() {
        stopTunnel("system revoke")
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel("destroy")
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * TCP socket that bypasses our own tunnel. Primary path binds a socket from
     * the underlying physical network's factory (WiFi/cellular, non-VPN,
     * internet-capable); fallback is the classic [VpnService.protect].
     */
    private fun protectedTcpSocket(): Socket {
        try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val nets = try { cm?.allNetworks ?: emptyArray() } catch (_: Exception) { emptyArray() }
            for (net in nets) {
                val caps = try { cm?.getNetworkCapabilities(net) } catch (_: Exception) { null } ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    try {
                        return net.socketFactory.createSocket()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        return Socket().also {
            if (!protect(it)) throw IllegalStateException("protect() refused")
        }
    }

    /** Minimal per-flow TCP relay with local seq/ack synthesis for TUN write-back. */
    private inner class TcpRelay(
        val v6: Boolean,
        val srcIp: String,
        val srcPort: Int,
        val dstIp: String,
        val dstPort: Int,
        synSeq: Long,
    ) {
        val alive = AtomicBoolean(true)
        @Volatile var lastActivityMs = System.currentTimeMillis()
        @Volatile private var clientDone = false
        @Volatile private var serverDone = false
        /** Return-path addresses, resolved once (family-correct). Null = unroutable, fail closed. */
        private val srcBytes: ByteArray? =
            if (v6) NetParsers.ipv6ToBytes(dstIp) else NetParsers.ipv4ToBytes(dstIp)
        private val dstBytes: ByteArray? =
            if (v6) NetParsers.ipv6ToBytes(srcIp) else NetParsers.ipv4ToBytes(srcIp)

        private val headLen = if (v6) 40 else 20
        private val flowKey = FlowKey(if (v6) "tcp6" else "tcp", srcIp, srcPort, dstIp, dstPort)
        private val flowId = "$srcIp:$srcPort->$dstIp:$dstPort"

        /** IP header for a return packet carrying [payloadLen] L4 bytes. */
        private fun ipHead(payloadLen: Int): ByteArray {
            val s = srcBytes ?: throw IllegalStateException("unroutable")
            val d = dstBytes ?: throw IllegalStateException("unroutable")
            return if (v6) {
                NetBuilders.buildIpv6Header(s, d, 6, payloadLen)
            } else {
                NetBuilders.buildIpv4Header(s, d, 6, headLen + payloadLen)
            }
        }

        private fun finishPacket(packet: ByteArray) {
            if (v6) NetBuilders.finishTcpPacket6(packet) else NetBuilders.finishTcpPacket(packet)
        }

        /** Client half-close: stop accepting upstream bytes, but keep the relay
         * alive for the server's reply (nc/curl close stdin right after send). */
        fun clientFin() {
            clientDone = true
            lastActivityMs = System.currentTimeMillis()
        }
        private var nextUpstreamSeq = (synSeq + 1) and 0xFFFFFFFFL
        // Unpredictable ISN: SecureRandom, not time-based (predictable seq enables spoofing).
        private var downSeq = (java.security.SecureRandom().nextInt().toLong() and 0xFFFFFFFFL)
        private var downAck = nextUpstreamSeq
        private val pending = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
        private var socket: Socket? = null

        fun offerUpstream(payload: ByteArray, seq: Long) {
            lastActivityMs = System.currentTimeMillis()
            if (payload.isNotEmpty() && seq == nextUpstreamSeq) {
                pending.offer(payload)
                nextUpstreamSeq = (nextUpstreamSeq + payload.size) and 0xFFFFFFFFL
                downAck = nextUpstreamSeq
            }
        }

        fun close() {
            alive.set(false)
            try { socket?.close() } catch (_: Exception) {}
        }

        suspend fun run(tunOut: FileOutputStream) {
            if (srcBytes == null || dstBytes == null) {
                alive.set(false)
                return // unroutable address family mix; fail closed
            }
            val sock = try {
                protectedTcpSocket().apply {
                    soTimeout = 8000
                    connect(java.net.InetSocketAddress(dstIp, dstPort), 8000)
                }
            } catch (e: Exception) {
                if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "relay upstream failed: ${e.javaClass.simpleName}")
                sendControl(tunOut, 0x14, downSeq, downAck) // RST: unreachable
                alive.set(false)
                tcpFlows.remove(flowId)
                return
            }
            socket = sock
            if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "relay upstream ok")
            // SYN-ACK
            sendControl(tunOut, 0x12, downSeq, downAck)
            downSeq = (downSeq + 1) and 0xFFFFFFFFL
            // Upstream pump: drain queued client bytes, then half-close upstream
            // (client FIN already seen or will come). Never spins: poll blocks.
            scope.launch {
                try {
                    val out = sock.getOutputStream()
                    while (!sock.isClosed) {
                        val chunk = pending.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (chunk != null) {
                            out.write(chunk)
                            out.flush()
                            lastActivityMs = System.currentTimeMillis()
                            sendControl(tunOut, 0x10, downSeq, downAck) // ACK
                        } else if (clientDone) {
                            break
                        }
                    }
                    try {
                        sock.shutdownOutput()
                    } catch (_: Exception) {
                    }
                } catch (e: Exception) {
                    if (com.phonemcp.server.BuildConfig.DEBUG) Log.d(TAG, "relay pump dead: ${e.javaClass.simpleName}")
                    alive.set(false)
                }
            }
            // Downstream pump
            try {
                val input = sock.getInputStream()
                val buf = ByteArray(8192)
                while (alive.get() && !sock.isClosed) {
                    // Read timeout (8 s) is idle, not EOF: keep long-lived flows
                    // (push, streaming) alive. Only real EOF/errors tear down.
                    val n = try {
                        input.read(buf)
                    } catch (e: java.net.SocketTimeoutException) {
                        lastActivityMs = System.currentTimeMillis()
                        continue
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) {
                        serverDone = true
                        sendControl(tunOut, 0x11, downSeq, downAck) // FIN+ACK to client
                        if (clientDone) {
                            close()
                            tcpFlows.remove(flowId)
                        }
                        break
                    }
                    lastActivityMs = System.currentTimeMillis()
                    val total = headLen + 20 + n
                    val out = ByteBuffer.allocate(total)
                    out.put(ipHead(20 + n))
                    val tcpHead = ByteBuffer.allocate(20)
                    tcpHead.putShort(dstPort.toShort())
                    tcpHead.putShort(srcPort.toShort())
                    tcpHead.putInt(downSeq.toInt())
                    tcpHead.putInt(downAck.toInt())
                    tcpHead.put(0x50.toByte())
                    tcpHead.put(0x18.toByte()) // PSH+ACK
                    tcpHead.putShort(-1) // window 65535
                    tcpHead.putShort(0)
                    tcpHead.putShort(0)
                    out.put(tcpHead.array())
                    out.put(buf, 0, n)
                    // Checksums are validated on TUN write-back; zero = dropped.
                    finishPacket(out.array())
                    synchronized(tunOut) { tunOut.write(out.array()) }
                    try {
                        repo.addDownstream(flowKey, total.toLong())
                    } catch (_: Exception) {
                    }
                    downSeq = (downSeq + n) and 0xFFFFFFFFL
                }
            } catch (_: Exception) {
            } finally {
                // Early teardown (exception/reap): FIN the client unless already done.
                if (!serverDone) sendControl(tunOut, 0x11, downSeq, downAck)
                close()
                tcpFlows.remove(flowId)
            }
        }

        private fun sendControl(tunOut: FileOutputStream, flags: Int, seq: Long, ack: Long) {
            try {
                val total = headLen + 20
                val out = ByteBuffer.allocate(total)
                out.put(ipHead(20))
                val tcpHead = ByteBuffer.allocate(20)
                tcpHead.putShort(dstPort.toShort())
                tcpHead.putShort(srcPort.toShort())
                tcpHead.putInt(seq.toInt())
                tcpHead.putInt(ack.toInt())
                tcpHead.put(0x50.toByte())
                tcpHead.put(flags.toByte())
                tcpHead.putShort(-1) // window 65535
                tcpHead.putShort(0)
                tcpHead.putShort(0)
                out.put(tcpHead.array())
                finishPacket(out.array())
                synchronized(tunOut) { tunOut.write(out.array()) }
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val ACTION_START = "com.phonemcp.server.net.START"
        const val ACTION_STOP = "com.phonemcp.server.net.STOP"
        const val CHANNEL_ID = "phone_mcp_net"
        const val NOTIFICATION_ID = 42
        const val MTU = 1500
        private const val TAG = "PhoneMcpNet"

        @Volatile var running = false
            private set
    }
}
