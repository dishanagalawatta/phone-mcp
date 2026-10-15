# ADR-0036: USB transport is host-side `adb forward`, not `adb reverse`

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** Docs and help text told USB users to run
  `adb reverse tcp:8080 tcp:8080`. Live-tested Oct 2026: that command fails
  with `cannot bind listener: Address already in use` because the phone's
  Ktor server already holds device:8080 — `reverse` asks the *device* to
  listen on a port that is taken. The correct direction is host-side
  `adb -s <serial> forward tcp:8080 tcp:8080`, then
  `PHONE_MCP_URL=https://127.0.0.1:8080/mcp`. Verified end-to-end:
  same bearer token + same SHA-256 pin (certificate fingerprint identical
  on LAN vs USB; pin/cafile modes skip the hostname check so `127.0.0.1`
  verifies), `/healthz` open, `/mcp` + `/media/*` + `/info` still Bearer,
  QS-tile headless start/stop works over `adb` with no LAN.
- **Decision:** Canonical USB transport is
  `adb -s <serial> forward tcp:8080 tcp:8080` → loopback URL. All docs,
  skills, and help text say `forward`, never `reverse`. With several phones
  attached the serial (from `adb devices -l`) is required; with exactly one
  `device`-state attachment the helpers auto-pick it (`pick_usb_device` /
  `pickUsbDevice` / `usb-mcp.sh pick_device`). Pins managed with
  `adb forward --list` / `--remove tcp:8080` / `--remove-all`. The dashboard
  pairing card shows the same forward command + loopback URL (display-only;
  token/pin unchanged).
- **Alternatives considered:** (a) `adb reverse` with an alternate device
  port — rejected: binds a second listener for no benefit and contradicts
  every existing pairing value (URL, pin flow, healthz checks); the
  conflict is structural, not a port-pick issue. (b) Keep documenting both
  and let users retry — rejected: the failure is deterministic while Ktor
  holds :8080, so documenting `reverse` guarantees a broken first run.
- **Consequences:** + One deterministic USB path (helper:
  `scripts/usb-mcp.sh`; client `usb` command + `--usb` flag wrap the same
  `forward`). + Docs carry the full USB subsection (prereqs, multi-device,
  verify, same-pin TLS note, troubleshooting table: `docs/harness.md` §1,
  `docs/client.md`, `docs/environments.md`). − `scripts/usb-mcp.sh` and
  the client `usb` helpers must stay in sync with this ADR; auth/TLS
  behavior is unchanged (TOFU over USB exactly as LAN).
