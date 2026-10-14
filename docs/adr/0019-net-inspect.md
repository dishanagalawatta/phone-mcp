# ADR-0019: Network Inspection & Diagnostics via opt-in VPN tunnel (new capability)

## Status
Accepted

## Context
Agents debugging mobile backends need per-app connection telemetry, usage
accounting, and Wireshark-grade exports (PCAP/PCAPNG/HAR) without leaving the
MCP surface. Android offers exactly two non-root primitives: per-UID counters
(`TrafficStats`, always available) and a user-consented `VpnService` tunnel
(the only API that sees other apps' packets). TLS interception would require a
user-installed CA + MITM proxy — a direct break of the TLS/TOFU model
(ADR-0010), a Play-policy removal risk, and a betrayal of the dashboard safety
model (ADR-0007). Root-only paths (`/proc` scraping beyond world-readable
`/proc/net`, iptables, multi-interface diversion under an active VPN) are
unavailable on stock devices and must degrade gracefully, never escalate.

## Decision
- One `NetInspectVpnService` (`BIND_VPN_SERVICE`, FGS `specialUse`) started
  ONLY from the dashboard "Start VPN" button or an explicit agent-requested
  flow that still lands on the system consent sheet (`VpnService.prepare` —
  never headless). It parses each IPv4 datagram once (TCP/UDP/DNS/SNI/plaintext
  HTTP head), applies the agent firewall, records telemetry into
  `NetInspectRepository` rings (flows 4000 / packets 20k / HTTP 2k / DNS 1k),
  then forwards UDP via a protected socket and TCP via a per-flow protected
  relay with TUN write-back. Non-TCP/UDP is recorded and dropped.
- Twelve `net_*` tools behind a new `net_` kill-switch default OFF with a
  dashboard toggle: `net_status`, `net_connections`, `net_usage`
  (TrafficStats UID walk of the 10000..10999 band + live-flow UIDs),
  `net_capture_start/stop` (returns `vpn_consent_required` until the tunnel
  runs), `net_export_pcap` (PCAP LINKTYPE_RAW / PCAPNG with package/uid
  comments), `net_export_har` (HAR 1.2, plaintext HTTP only),
  `net_firewall_get/set` (default-allow + suffix hosts, CIDR IPs, ports,
  packages, allow-bypass; 500-entry caps), `net_blocklist_check` (built-in
  sample + custom), `net_geo_lookup` (offline private/public scope only —
  no bundled MaxMind DB, no external fetch), `net_tls_info`
  (status/import/export/clear of an SSLKEYLOGFILE correlation store), and
  `net_stream` (SSE live flow batches, same single-stream guard as sensors).
- TLS stance (hard rule): NO on-device decryption, NO CA install, NO MITM.
  HTTPS yields SNI/host/IP metadata only; bodies/paths never leave the tunnel
  as plaintext because they never exist as plaintext. `KeylogStore` only holds
  agent-imported keylog lines for downstream Wireshark correlation.
- Attribution without `QUERY_ALL_PACKAGES` (Play-restricted): UID→package via
  `PackageManager.getPackagesForUid` + world-readable `/proc/net/{tcp,udp}`
  port→uid parsing (pure, unit-tested). A future `NetworkStatsManager` upgrade
  would need signature-level `PACKAGE_USAGE_STATS` (deliberately NOT declared —
  lint `ProtectedPermissions` fails the build for non-system apps; re-add only
  with the upgrade, per 2026-10-13 amendment).
- Exports ride the existing `/media` sidecar (auth + TTL + 100 MB cap);
  arg values never enter the log ring; firewall drops log at most one line
  per 30 s from the hot path.

## Amendment 2026-10-13 (same-day, pre-release): IPv6 + idle-flow survival
- Tunnel now dual-stack (`fd00:db8::2/64`, `::/0` route): v6 datagrams parse
  into `tcp6`/`udp6`/`ip6-*` flows with SNI/DNS/HTTP extraction, CIDR + geo
  cover v6, PCAP linktype is family-agnostic, and the relay synthesizes valid
  v6 checksums (UDP checksum mandatory per RFC 8200). Verified on-device to
  flow-table level (live `ip6-58` multicast flow); upstream v6 relay is
  unit-vector-proven but awaits a v6-capable network (test LAN is v4-only —
  direct `curl -6` fails identically with the tunnel off).
- Idle-timeout fix: the 8 s socket read timeout no longer tears down quiet
  flows (it killed mtalk-style push connections); only real EOF/errors FIN.

## Consequences
Positive: full capture→analyze→block→export loop with one consent sheet and
one toggle; every parser/writer/matcher is pure Kotlin with JVM tests.
Negative: VPN slot is exclusive (one active VPN — ours or another app's);
TCP relay is a diagnostics-grade simplification (no retransmission/window
scaling — bulk throughput may dip while capturing); Play review will scrutinize
the VPN + accessibility combination (prominent disclosure updated in
play-release); rooted multi-interface capture reports `rooted:true` but does
not divert outside the tunnel.
