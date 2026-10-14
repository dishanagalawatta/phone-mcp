# Net reference

## Capture lifecycle

```text
net_status -> net_capture_start(clear=true) -> generate traffic ->
net_capture_stop -> net_export_pcap | net_export_har -> GET /media/{id}
```

Rings persist after stop until the next start with `clear=true`.

## Arg clamps

| Tool | Args |
|---|---|
| `net_status` | No args. |
| `net_connections` | `limit` default 200 max 1000; `package` optional; `blockedOnly` default false. Includes tcp6/udp6 and ip6-58 multicast. |
| `net_usage` | `limit` default 100 max 500. TrafficStats per-UID Rx/Tx plus device totals. |
| `net_capture_start` | `clear` default true. Needs VPN consent or returns `vpn_consent_required`. |
| `net_capture_stop` | No args. |
| `net_export_pcap` | `format: pcap\|pcapng` default pcap; `maxPackets` default 5000 max 20000. |
| `net_export_har` | `maxEntries` default 500 max 2000. HAR 1.2. |
| `net_firewall_get` | No args. |
| `net_firewall_set` | `defaultAllow` default true; `blockedHosts[]`, `blockedIpRanges[]` (CIDR), `blockedPorts[]` 0..65535, `blockedPackages[]`, `allowedHosts[]`; each list max 500 (600-entry policy rejected). Empty call resets. |
| `net_blocklist_check` | `host` required. Offline, no VPN needed. |
| `net_geo_lookup` | `ip` required IPv4/IPv6. Offline scope check; public geo/ASN report unknown without a bundled DB. |
| `net_tls_info` | `action: status\|import\|export\|clear`; `keylog` required for import (SSLKEYLOGFILE lines, 300 KB cap). |
| `net_stream` | `intervalMs` 500..5000 default 1000; `durationMs` 1000..60000 default 10000. SSE only, one at a time. |

## Verify exports

```bash
tshark -r capture.pcap | head
python3 -c "import json; json.load(open('capture.har')); print('HAR OK')"
```

Live firewall kills show a `blocked` flow verdict plus client failure (curl 000); reset restores 200.

## Stance

HTTPS is never decrypted: fields carry SNI and host metadata only. `net_tls_info` always reports `interception:false`; no CA install and no MITM exist on this device.
