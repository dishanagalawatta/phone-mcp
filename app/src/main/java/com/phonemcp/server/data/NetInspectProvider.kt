package com.phonemcp.server.data

import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.net.FirewallPolicy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Network Inspection & Diagnostics tools (all `net_*`, kill-switched OFF by default).
 *
 * Thin dispatcher: schemas in [NetInspectCatalog], queries in [NetInspectActions],
 * capture/export in [NetInspectCapture], streaming below. Arg parsing stays in the
 * companion (pure, JVM-tested). TLS payloads are NEVER intercepted or decrypted
 * on-device (see ADR-0019 and `net_tls_info`).
 */
@Singleton
class NetInspectProvider @Inject constructor(
    private val actions: NetInspectActions,
    private val capture: NetInspectCapture,
    private val repo: NetInspectRepository,
) : ToolProvider {
    override val id = "netinspect"

    override fun listTools() = NetInspectCatalog.listTools()

    override suspend fun call(name: String, args: JsonObject): ToolCallResult = when (name) {
        "net_status" -> actions.status()
        "net_connections" -> actions.connections(args)
        "net_usage" -> actions.usage(args)
        "net_capture_start" -> capture.captureStart(args)
        "net_capture_stop" -> capture.captureStop()
        "net_export_pcap" -> capture.exportPcap(args)
        "net_export_har" -> capture.exportHar(args)
        "net_firewall_get" -> actions.firewallGet()
        "net_firewall_set" -> actions.firewallSet(args)
        "net_blocklist_check" -> actions.blocklistCheck(args)
        "net_geo_lookup" -> actions.geoLookup(args)
        "net_tls_info" -> actions.tlsInfo(args)
        "net_stream" -> ToolCallResult.error("net_stream requires SSE (POST /mcp with Accept: text/event-stream)")
        else -> throw IllegalArgumentException("unknown tool $name")
    }

    override fun supportsStreaming(name: String): Boolean = name == "net_stream"

    override fun streamNotificationMethod(name: String): String = "notifications/net"

    override fun openStream(name: String, args: JsonObject): Flow<JsonObject> = flow {
        require(name == "net_stream") { "unknown stream $name" }
        val req = parseStreamArgs(args)
        val deadline = System.currentTimeMillis() + req.durationMs
        while (System.currentTimeMillis() < deadline) {
            withTimeoutOrNull(req.intervalMs) {
                kotlinx.coroutines.delay(req.intervalMs)
            }
            val rows = repo.snapshot(limit = 100)
            emit(
                buildJsonObject {
                    put("t", System.currentTimeMillis())
                    put("flows", rows.size)
                    put("packetsSeen", repo.packetsSeen.get())
                    put("dropped", repo.droppedByPolicy.get())
                    put(
                        "top",
                        JsonArray(
                            rows.take(20).map { r ->
                                buildJsonObject {
                                    put("flow", r.key.canonical())
                                    put("package", r.packageName ?: "")
                                    put("host", r.host ?: r.sni ?: "")
                                    put("bytesUp", r.bytesUp)
                                    put("blocked", r.blocked)
                                }
                            },
                        ),
                    )
                },
            )
        }
    }

    companion object {
        const val TLS_NOTE =
            "No on-device TLS interception: HTTPS bodies/paths are never decrypted here. " +
                "Capture records SNI/host/IP metadata only. Import an SSLKEYLOGFILE you own " +
                "for downstream Wireshark correlation."

        fun policyJson(p: FirewallPolicy): JsonObject = buildJsonObject {
            put("defaultAllow", p.defaultAllow)
            put("blockedHosts", JsonArray(p.blockedHosts.map { JsonPrimitive(it) }))
            put("blockedIpRanges", JsonArray(p.blockedIpRanges.map { JsonPrimitive(it) }))
            put("blockedPorts", JsonArray(p.blockedPorts.map { JsonPrimitive(it) }))
            put("blockedPackages", JsonArray(p.blockedPackages.map { JsonPrimitive(it) }))
            put("allowedHosts", JsonArray(p.allowedHosts.map { JsonPrimitive(it) }))
        }

        data class StreamArgs(val intervalMs: Long, val durationMs: Long)

        /** Pure arg parsing; JVM-test safe. */
        fun parseStreamArgs(args: JsonObject): StreamArgs {
            val intervalMs = ArgParser.long(args, "intervalMs", 1000L, 500L..5000L)
            val durationMs = ArgParser.long(args, "durationMs", 10_000L, 1000L..60_000L)
            return StreamArgs(intervalMs, durationMs)
        }

        /** Pure policy parsing; throws IllegalArgumentException. JVM-test safe. */
        fun parsePolicy(args: JsonObject): FirewallPolicy {
            fun strings(key: String): List<String> {
                val arr = args[key] as? JsonArray ?: return emptyList()
                val items = arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim()?.take(253) }
                    .filter { it.isNotEmpty() }
                require(items.size <= FirewallPolicy.MAX_ENTRIES) { "'$key' capped at ${FirewallPolicy.MAX_ENTRIES}" }
                return items
            }
            fun ports(): List<Int> {
                val arr = args["blockedPorts"] as? JsonArray ?: return emptyList()
                val items = arr.map { (it as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("Invalid blockedPorts entry") }
                require(items.size <= FirewallPolicy.MAX_ENTRIES) { "'blockedPorts' capped at ${FirewallPolicy.MAX_ENTRIES}" }
                require(items.all { it in 0..65535 }) { "Ports must be 0..65535" }
                return items
            }
            return FirewallPolicy(
                defaultAllow = ArgParser.boolean(args, "defaultAllow", true),
                blockedHosts = strings("blockedHosts"),
                blockedPorts = ports(),
                blockedPackages = strings("blockedPackages"),
                blockedIpRanges = strings("blockedIpRanges"),
                allowedHosts = strings("allowedHosts"),
            )
        }
    }
}
