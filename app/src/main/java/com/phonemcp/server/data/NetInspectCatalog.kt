package com.phonemcp.server.data

import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Static `net_*` tool catalog (schemas only). Pure declarations so the tool
 * surface is reviewable without scrolling past implementations.
 */
internal object NetInspectCatalog {
    fun listTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "net_status",
            "Capture state: vpn active, capturing, flows/packets/drops, active interface, root note, TLS stance.",
            emptySchema(),
        ),
        ToolDefinition(
            "net_connections",
            "Live connection table (proto, endpoints, uid/package, SNI/host, bytes, block verdict). " +
                "Args: limit (default 200, max 1000), package (optional filter), blockedOnly (default false).",
            objectSchema(
                buildJsonObject {
                    put("limit", prop("integer", "Max rows"))
                    put("package", prop("string", "Filter to one package name"))
                    put("blockedOnly", prop("boolean", "Only blocked flows"))
                },
            ),
        ),
        ToolDefinition(
            "net_usage",
            "Per-app byte counters (TrafficStats uid Rx/Tx + device totals). Args: limit (default 100, max 500).",
            objectSchema(buildJsonObject { put("limit", prop("integer", "Max rows")) }),
        ),
        ToolDefinition(
            "net_capture_start",
            "Arm packet capture. Needs the dashboard VPN approval first (Android shows a system consent sheet); " +
                "without it returns vpn_consent_required. Args: clear (default true, wipe previous rings).",
            objectSchema(buildJsonObject { put("clear", prop("boolean", "Wipe previous capture")) }),
        ),
        ToolDefinition(
            "net_capture_stop",
            "Stop capture (rings kept for export until next start with clear=true).",
            emptySchema(),
        ),
        ToolDefinition(
            "net_export_pcap",
            "Write captured packets to PCAP or PCAPNG (Wireshark-ready) via /media. " +
                "Args: format (pcap|pcapng, default pcap), maxPackets (default 5000, max 20000).",
            objectSchema(
                buildJsonObject {
                    put("format", prop("string", "pcap or pcapng"))
                    put("maxPackets", prop("integer", "Packet cap"))
                },
            ),
        ),
        ToolDefinition(
            "net_export_har",
            "Write captured HTTP transactions to HAR 1.2 via /media. Args: maxEntries (default 500, max 2000).",
            objectSchema(buildJsonObject { put("maxEntries", prop("integer", "Entry cap")) }),
        ),
        ToolDefinition(
            "net_firewall_get",
            "Current agent-controlled filter policy (default-allow + block lists).",
            emptySchema(),
        ),
        ToolDefinition(
            "net_firewall_set",
            "Replace the filter policy. Captured flows matching it are dropped in the tunnel and marked blocked. " +
                "Args: defaultAllow (default true), blockedHosts[] (suffix match), blockedIpRanges[] (CIDR), " +
                "blockedPorts[] (0..65535), blockedPackages[], allowedHosts[] (bypass). " +
                "Each list capped at 500 entries; empty call resets to default-allow.",
            objectSchema(
                buildJsonObject {
                    put("defaultAllow", prop("boolean", "Default verdict"))
                    put("blockedHosts", prop("array", "Suffix-matched hosts"))
                    put("blockedIpRanges", prop("array", "CIDR or exact IPs"))
                    put("blockedPorts", prop("array", "Destination ports"))
                    put("blockedPackages", prop("array", "Package names"))
                    put("allowedHosts", prop("array", "Allow-list bypass"))
                },
            ),
        ),
        ToolDefinition(
            "net_blocklist_check",
            "Check a host against the built-in threat list + custom entries. Args: host (required).",
            objectSchema(buildJsonObject { put("host", prop("string", "Hostname to check")) }),
        ),
        ToolDefinition(
            "net_geo_lookup",
            "Offline IP telemetry: private/public scope without any external fetch. " +
                "Public geo/ASN need a bundled DB (not shipped) and report unknown. Args: ip (required).",
            objectSchema(buildJsonObject { put("ip", prop("string", "IPv4 or IPv6 literal")) }),
        ),
        ToolDefinition(
            "net_tls_info",
            "TLS stance + keylog correlation store. This device does NOT break TLS (no CA install, no MITM). " +
                "Args: action (status|import|export|clear), keylog (required for import, standard SSLKEYLOGFILE lines).",
            objectSchema(
                buildJsonObject {
                    put("action", prop("string", "status, import, export, or clear"))
                    put("keylog", prop("string", "Keylog lines for import"))
                },
            ),
        ),
        ToolDefinition(
            "net_stream",
            "Live SSE stream of connection events (one snapshot batch per interval). Requires Accept: text/event-stream. " +
                "Args: intervalMs (default 1000, clamped 500..5000), durationMs (default 10000, clamped 1000..60000).",
            objectSchema(
                buildJsonObject {
                    put("intervalMs", prop("integer", "Batch period in ms"))
                    put("durationMs", prop("integer", "Total stream cap in ms"))
                },
            ),
        ),
    )
}
