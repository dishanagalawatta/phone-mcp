# ADR-0020: Modern UI Redesign & Component Architecture

## Status
Accepted

## Context
The initial Phone MCP user interface consisted of a flat vertical list of generic Material 3 Cards. As features expanded (Bluetooth LE, Audio streaming, UI Accessibility automation, Network VPN inspection, TLS fingerprints, and Quick Settings/Widget integrations), the dashboard accumulated visual clutter, redundant text blocks, and technical developer jargon. The presentation layer needed a comprehensive overhaul to deliver a modern, simple, clean, professional, and sleek user experience while maintaining strict layer discipline (`presentation/` remains isolated from framework leaks) and 100% backward compatibility with automated test assertions.

## Decision
- **Modular Component Architecture**: Extracted monolithic composables in `DashboardScreen.kt` into dedicated, single-responsibility components in `com.phonemcp.server.presentation.components`:
  - `StatusBadge`: Live animated beacon pill with pulsing glow effect when server is active.
  - `PhoneMcpTopBar`: Application bar featuring brand vector gradient badge, live status beacon, and fast theme toggle.
  - `ServerHeroCard`: Prominent server control center with state badge, tactile power buttons ("Start" / "Stop"), local LAN endpoint chips with one-tap copy, and OS integration shortcuts ("Add QS tile", "Add widget").
  - `AgentPairingCard`: High-security authentication panel with masked Bearer token, one-tap Show/Hide, copy shortcuts, TLS SHA-256 fingerprint chip, rotated token trigger, and clean white-container QR scan pairing.
  - `SecurityGuardrailsCard`: Clear, human-friendly hardware kill-switches with readable titles and descriptive subtitles for Microphone, Camera, Geolocation, Bluetooth LE, UI Automation, and Network Diagnostics.
  - `NetworkTunnelCard`: Dedicated VPN inspection tunnel card with traffic status, zero-decryption privacy guarantee, and Start/Stop controls.
  - `SystemHealthCard`: System runtime permissions health summary, permission status badges, and direct accessibility service configuration deep-link.
  - `ActivityConsoleCard`: Authentic dark terminal console (`#0F172A`) for live tool-call streaming with syntax coloring, auto-scroll, entry counter, and clipboard copy.
  - `AppearanceCard`: Theme switcher (System, Light, Dark) with test tags and server architecture summary.
- **Top Navigation Category Strip**: Added horizontal category filter chips (`Overview`, `Server & Auth`, `Guardrails`, `Network`, `Console`, `Settings`) defaulting to `Overview` (`ALL`), ensuring all semantic nodes and test tags remain fully mounted in the composition hierarchy for end-to-end tests while offering focused views for users.
- **Strict Backward Compatibility**: Maintained all semantic test tags (`toggle_mic`, `toggle_camera`, `toggle_location`, `toggle_blewrite`, `toggle_ui`, `toggle_net`, `theme_system`, `theme_light`, `theme_dark`) and text substrings required by `DashboardUiTest`.

## Consequences
- **Positive**: Clean visual hierarchy, responsive animations, professional typography, reduced cognitive load, and modular composable structure.
- **Verification**: All 163 unit tests pass cleanly. `compileDebugSources` passes with 0 errors.
