package com.phonemcp.server.data

import com.phonemcp.server.domain.MediaStore
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.net.HarBuilder
import com.phonemcp.server.net.PcapIO
import com.phonemcp.server.service.NetInspectVpnService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Capture lifecycle + media export (`net_capture_*`, `net_export_*`). Rings stay
 * in [NetInspectRepository]; blobs go through [MediaStore] sidecars.
 */
@Singleton
class NetInspectCapture @Inject constructor(
    private val repo: NetInspectRepository,
    private val media: MediaStore,
) {
    fun captureStart(args: JsonObject): ToolCallResult {
        val clear = ArgParser.boolean(args, "clear", true)
        repo.wantCapture = true
        if (!repo.vpnActive && !NetInspectVpnService.running) {
            return ToolCallResult.text(
                buildJsonObject {
                    put("armed", true)
                    put("capturing", false)
                    put("status", "vpn_consent_required")
                    put(
                        "next",
                        "Approve the VPN profile on the device: dashboard → Network inspection → Start VPN " +
                            "(Android shows a system consent sheet; capture begins automatically).",
                    )
                }.toString(),
            )
        }
        if (clear) repo.clearCapture()
        repo.capturing = true
        if (repo.captureStartedMs.get() == 0L) repo.captureStartedMs.set(System.currentTimeMillis())
        return ToolCallResult.text(
            buildJsonObject {
                put("armed", true)
                put("capturing", true)
                put("status", "capturing")
            }.toString(),
        )
    }

    fun captureStop(): ToolCallResult {
        repo.capturing = false
        repo.wantCapture = false
        return ToolCallResult.text(
            buildJsonObject {
                put("capturing", false)
                put("packets", repo.packetsSnapshot(1).size.let { repo.packetsSnapshot(NetInspectRepository.PcapCap).size })
                put("note", "Rings kept for export until next net_capture_start with clear=true.")
            }.toString(),
        )
    }

    suspend fun exportPcap(args: JsonObject): ToolCallResult = withContext(Dispatchers.IO) {
        val format = (args["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.lowercase() ?: "pcap"
        require(format == "pcap" || format == "pcapng") { "Invalid format (pcap|pcapng)" }
        val max = ArgParser.int(args, "maxPackets", 5000, 1..PcapIO.MAX_PACKETS_PER_EXPORT)
        val packets = repo.packetsSnapshot(max)
        if (packets.isEmpty()) return@withContext ToolCallResult.error("No captured packets (start capture + generate traffic first)")
        val bytes = if (format == "pcapng") PcapIO.writePcapNg(packets) else PcapIO.writePcap(packets)
        val ref = media.put(bytes, format, if (format == "pcapng") "application/x-pcapng" else "application/vnd.tcpdump.pcap")
        ToolCallResult.text(
            buildJsonObject {
                put("packets", packets.size)
                put("bytes", bytes.size)
                put("format", format)
                put("mediaUrl", ref.urlPath)
                put("mimeType", ref.mimeType)
                put("openWith", "Wireshark / tshark -r capture.$format")
            }.toString(),
        )
    }

    suspend fun exportHar(args: JsonObject): ToolCallResult = withContext(Dispatchers.IO) {
        val max = ArgParser.int(args, "maxEntries", 500, 1..PcapIO.MAX_HAR_ENTRIES)
        val entries = repo.httpSnapshot(max)
        if (entries.isEmpty()) {
            return@withContext ToolCallResult.error(
                "No HTTP transactions captured (only plaintext HTTP is recorded; HTTPS yields SNI/host metadata, never bodies)",
            )
        }
        val har = HarBuilder.build(entries)
        val ref = media.put(har.toByteArray(Charsets.UTF_8), "har", "application/json")
        ToolCallResult.text(
            buildJsonObject {
                put("entries", minOf(entries.size, max))
                put("bytes", har.toByteArray(Charsets.UTF_8).size)
                put("mediaUrl", ref.urlPath)
                put("mimeType", ref.mimeType)
            }.toString(),
        )
    }
}
