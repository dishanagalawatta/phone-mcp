---
name: phone-mcp-net
description: Use when inspecting phone network state, capturing packets, exporting pcap or HAR, managing the VPN firewall policy, checking blocklists, or streaming connection events over Phone MCP.
---

# Phone MCP Net

## Overview

Inspect LAN traffic metadata without breaking TLS. Capture through the opt-in VPN tunnel, export evidence via `/media`, and enforce firewall policy.

## Preconditions

- Complete `phone-mcp` pairing and smoke first.
- Enable the `net_*` kill-switch (default OFF) before any `net_*` call.
- Obtain dashboard VPN consent before `net_capture_start`; without it the tool returns `vpn_consent_required` and the system consent sheet must be accepted.

## Workflow

### 1. Read live state

Call `net_status` for VPN, capture, flow, interface, and TLS stance. Call `net_connections` with `limit` (default 200, max 1000), optional `package` filter, `blockedOnly` (default false) for `{proto, endpoints, uid/package, SNI/host, bytes, verdict}`. Call `net_usage` with `limit` (default 100, max 500) for per-UID counters. Call `net_firewall_get` for the current default-allow plus block lists.

Completion criterion: status, connections, usage, and firewall policy are recorded; HTTPS appears as SNI or host metadata only.

### 2. Capture and export

Call `net_capture_start` with `clear` (default true wipes prior rings), generate traffic, then call `net_capture_stop` (rings persist until the next `clear=true` start). Call `net_export_pcap` with `format: pcap|pcapng` (default `pcap`) and `maxPackets` (default 5000, max 20000); validate with `tshark -r` after downloading via `/media`. Call `net_export_har` with `maxEntries` (default 500, max 2000) for HAR 1.2 (captured HTTP; HTTPS stays SNI metadata).

Completion criterion: exported file downloads and parses (pcap opens in Wireshark/tshark; HAR validates as 1.2).

### 3. Enforce and probe offline

Call `net_firewall_set` with `defaultAllow` (default true), `blockedHosts[]` (suffix match), `blockedIpRanges[]` (CIDR), `blockedPorts[]`, `blockedPackages[]`, `allowedHosts[]` bypass; each list caps at 500 entries and an empty call resets to default-allow. Verify live kills (blocked flow plus client failure) and reset to restore. Call `net_blocklist_check` with required `host` and `net_geo_lookup` with required `ip` for offline probes (no VPN needed). Call `net_tls_info` with `status|import|export|clear` (`keylog` required for import); stance is always `interception:false` (no CA install, no MITM).

Completion criterion: firewall verdicts are observed live and offline probes return without capture.

### 4. Stream events

Call `net_stream` only over SSE with `intervalMs` 500..5000 (default 1000) and `durationMs` 1000..60000 (default 10000). One stream at a time; a second fails with `-32603`.

Completion criterion: snapshot batches arrive per interval and end with the final envelope.

## Error handling

Treat `vpn_consent_required` as a human-consent gate, not a retry. Treat oversize policy or keylog as `-32602`. Never claim HTTPS payload visibility; report SNI and host only.

## Resources

- `references/net-reference.md`: capture lifecycle, firewall shapes, offline probes, TLS stance.
