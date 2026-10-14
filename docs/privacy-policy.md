# Privacy Policy — Phone MCP (template)

> Host this file (rendered) at a public URL and paste that URL into Play Console.
> Replace `[bracketed]` fields before publishing. Last updated: 2026-10-12.

## What this app does

Phone MCP (`[package: com.phonemcp.server]`, by `[publisher name]`) runs a Model Context
Protocol server **on your own device** so AI agents on your local network can read phone
sensors and trigger phone outputs (camera, microphone, speaker, vibration, flash) at your command.

## Data we access, and why

| Data | When | Where it goes |
|------|------|---------------|
| Location (GPS) | Only while the server is running and the Location toggle is on | To the LAN-connected agent that requested it |
| Microphone / camera | Only on an explicit agent call, while the server runs and toggles are on | To the LAN-connected agent as a short clip |
| Sensors, Bluetooth LE, NFC state, battery, clipboard | On explicit agent call while the server runs | To the LAN-connected agent |
| Photos/audio/video | Cached on-device (~15 min / 100 MB cap, then auto-deleted) | Served to the LAN agent over plain HTTP |
| Per-app network telemetry (connections, DNS/SNI hostnames, usage bytes, PCAP/HAR captures) | Only after you enable the Network toggle AND approve the system VPN consent sheet | To the LAN-connected agent; HTTPS content is never decrypted |

The app has **no accounts, no analytics SDKs, no advertising SDKs**, and sends nothing to
the publisher or any third party. All network traffic stays on your local network.

## Security notes you should understand

- The v1 server uses **plain HTTP on your LAN with no login**. Anyone on the same Wi-Fi
  can invoke *enabled* tools. Only run it on networks you trust, and keep the
  microphone/camera/location toggles off unless actively demoing.
- Clips are stored in the app-private cache and auto-evict; uninstalling removes everything.

## Your controls

- Start/Stop the server, per-category kill-switches, and Android runtime permission
  grants/revokes (system Settings → Apps → Phone MCP) take effect immediately.
- There is no account data to delete; to erase all cached media, stop the server or uninstall.

## Children

Not directed at children under 13 (18+ recommended for the testing track).

## Contact

`[support email]` — data questions, correction/deletion requests (nothing is retained server-side,
but we will confirm what the app stores on-device).
