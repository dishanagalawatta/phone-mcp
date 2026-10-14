package com.phonemcp.server.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.net.FirewallPolicy
import com.phonemcp.server.net.GeoLookup
import com.phonemcp.server.net.KeylogStore
import com.phonemcp.server.service.NetInspectVpnService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Read/query `net_*` implementations (status, connections, usage, firewall,
 * blocklist, geo, TLS stance). Capture/export lives in [NetInspectCapture];
 * dispatch stays in [NetInspectProvider].
 */
@Singleton
class NetInspectActions @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: NetInspectRepository,
    private val media: MediaStore,
) {
    fun status(): ToolCallResult {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = try {
            cm?.getNetworkCapabilities(cm?.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (_: Exception) {
            null
        }
        val ifaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList()?.map { it.name + "/" + it.interfaceAddresses.size }?.take(16)
        } catch (_: Exception) {
            null
        }
        val rooted = detectRoot()
        return ToolCallResult.text(
            buildJsonObject {
                put("vpnActive", repo.vpnActive || NetInspectVpnService.running)
                put("capturing", repo.capturing)
                put("wantCapture", repo.wantCapture)
                put("flows", repo.flowCount())
                put("packetsSeen", repo.packetsSeen.get())
                put("droppedByPolicy", repo.droppedByPolicy.get())
                put("captureStartedMs", repo.captureStartedMs.get())
                put("internet", active ?: false)
                if (ifaces != null) put("interfaces", JsonArray(ifaces.map { JsonPrimitive(it) }))
                put("rooted", rooted)
                put("tlsInterception", false)
                put("note", NetInspectProvider.TLS_NOTE)
            }.toString(),
        )
    }

    fun connections(args: JsonObject): ToolCallResult {
        val limit = ArgParser.int(args, "limit", 200, 1..1000)
        val pkg = (args["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.take(256)
        val blockedOnly = ArgParser.boolean(args, "blockedOnly", false)
        val rows = repo.snapshot(limit, pkg, blockedOnly)
        return ToolCallResult.text(
            buildJsonObject {
                put("count", rows.size)
                put("vpnActive", repo.vpnActive || NetInspectVpnService.running)
                put(
                    "connections",
                    JsonArray(
                        rows.map { r ->
                            buildJsonObject {
                                put("proto", r.key.proto)
                                put("src", "${r.key.srcIp}:${r.key.srcPort}")
                                put("dst", "${r.key.dstIp}:${r.key.dstPort}")
                                put("uid", r.uid)
                                if (r.packageName != null) put("package", r.packageName)
                                if (r.host != null) put("host", r.host)
                                if (r.sni != null) put("sni", r.sni)
                                put("bytesUp", r.bytesUp)
                                put("bytesDown", r.bytesDown)
                                put("packetsUp", r.packetsUp)
                                put("firstSeenMs", r.firstSeenMs)
                                put("lastSeenMs", r.lastSeenMs)
                                put("blocked", r.blocked)
                                if (r.blockReason != null) put("blockReason", r.blockReason)
                            }
                        },
                    ),
                )
            }.toString(),
        )
    }

    fun usage(args: JsonObject): ToolCallResult {
        val limit = ArgParser.int(args, "limit", 100, 1..500)
        val apps = repo.usageSnapshot(limit)
        return ToolCallResult.text(
            buildJsonObject {
                val totals = repo.deviceTotals()
                put("totalRx", totals["totalRx"] ?: -1)
                put("totalTx", totals["totalTx"] ?: -1)
                put(
                    "apps",
                    JsonArray(
                        apps.map { row ->
                            buildJsonObject {
                                put("uid", (row["uid"] as Int))
                                put("package", (row["package"] as String?) ?: "")
                                put("rxBytes", (row["rxBytes"] as Long))
                                put("txBytes", (row["txBytes"] as Long))
                            }
                        },
                    ),
                )
            }.toString(),
        )
    }

    fun firewallGet(): ToolCallResult {
        val p = repo.policy
        return ToolCallResult.text(NetInspectProvider.policyJson(p).toString())
    }

    fun firewallSet(args: JsonObject): ToolCallResult {
        val policy = NetInspectProvider.parsePolicy(args) // throws IllegalArgumentException → -32602
        repo.policy = policy
        return ToolCallResult.text(
            buildJsonObject {
                put("applied", true)
                put("policy", NetInspectProvider.policyJson(policy))
            }.toString(),
        )
    }

    fun blocklistCheck(args: JsonObject): ToolCallResult {
        val host = (args["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        require(host.isNotEmpty() && host.length <= 253) { "Missing 'host'" }
        val result = repo.checkHost(host)
        return ToolCallResult.text(
            buildJsonObject {
                put("host", host)
                put("listed", result["listed"] as Boolean)
                put("defaultList", result["defaultList"] as Boolean)
                put("customList", result["customList"] as Boolean)
            }.toString(),
        )
    }

    fun geoLookup(args: JsonObject): ToolCallResult {
        val ip = (args["ip"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        require(ip.isNotEmpty() && ip.length <= 64) { "Missing 'ip'" }
        val result = GeoLookup.lookup(ip)
        return ToolCallResult.text(
            buildJsonObject {
                put("ip", result["ip"] ?: ip)
                put("scope", result["scope"] ?: "unknown")
                put("country", result["country"] ?: "unknown")
                put("asn", result["asn"] ?: "unknown")
            }.toString(),
        )
    }

    suspend fun tlsInfo(args: JsonObject): ToolCallResult = withContext(Dispatchers.IO) {
        val action = (args["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: "status"
        when (action) {
            "status" -> ToolCallResult.text(
                buildJsonObject {
                    put("interception", false)
                    put("observedSniFlows", repo.snapshot(1000).count { it.sni != null })
                    put("storedKeys", KeylogStore.size())
                    put("note", NetInspectProvider.TLS_NOTE)
                }.toString(),
            )
            "import" -> {
                val raw = (args["keylog"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
                require(raw.isNotEmpty() && raw.length <= 256 * 1024) { "Missing 'keylog' (max 256 KB)" }
                val added = KeylogStore.importLines(raw)
                ToolCallResult.text("imported $added keys (total ${KeylogStore.size()})")
            }
            "export" -> {
                val out = KeylogStore.export()
                if (out.isEmpty()) return@withContext ToolCallResult.error("Keylog store empty (import keys first)")
                val ref = media.put(out.toByteArray(Charsets.UTF_8), "log", "text/plain")
                ToolCallResult.text(
                    buildJsonObject {
                        put("keys", KeylogStore.size())
                        put("mediaUrl", ref.urlPath)
                        put("use", "Wireshark Preferences → Protocols → TLS → (Pre)-Master-Secret log filename")
                    }.toString(),
                )
            }
            "clear" -> {
                KeylogStore.clear()
                ToolCallResult.text("keylog cleared")
            }
            else -> throw IllegalArgumentException("Invalid action (status|import|export|clear)")
        }
    }

    fun detectRoot(): Boolean {
        val paths = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/magisk/.core/bin/su")
        return paths.any { try { java.io.File(it).exists() } catch (_: Exception) { false } } ||
            try {
                Runtime.getRuntime().exec(arrayOf("which", "su")).inputStream.bufferedReader().readLine() != null
            } catch (_: Exception) {
                false
            }
    }
}
