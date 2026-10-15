# Design System: Phone MCP
**Project:** Phone MCP Android dashboard (Compose Material 3, `com.phonemcp.server`)

## 1. Visual Theme & Atmosphere
Modern, clean, professional on-device server console. Airy but dense with live state — calm light surfaces by default with system-driven dark mode, crisp status pills and hero controls carrying the energy. Utilitarian trust aesthetic: every destructive or privacy-sensitive action (kill-switches, rotation, VPN) reads as guarded and explicit, never playful. Terminal-style activity console grounds the design as a tool for developers and agents.

## 2. Color Palette & Roles
Brand gradient runs diagonal -37° from top-right cyan to bottom-left violet (logo mark).

| Descriptive Name | Hex | Functional Role |
|---|---|---|
| Signal Teal-Cyan | `#3AD7CC` | Gradient stop 0% (top-right); live/online accents |
| Sky Cyan | `#22D3EE` | Gradient stop 20%; info chips, endpoint links |
| Vibrant Trust Blue | `#3B82F6` | Gradient center 45%; primary actions, power-on |
| Deep Indigo | `#6366F1` | Gradient stop 70%; secondary surfaces, headers |
| Royal Violet | `#8D4FF3` | Gradient stop 100% (bottom-left); brand depth, QR container |
| Clean White | `#FFFFFF` | App-icon background, notification glyph, card surfaces (light) |
| Secure Ink | `#101418` (M3 `onSurface` dark) | Terminal console background, monospace log stream |

Material You dynamic color (API 33+) tints glyphs from wallpaper; monochrome launcher silhouette enables themed icons. Status semantics: green pulse = Online, neutral grey = Idle, red = denied/blocked, amber = consent required (VPN/NFC tap waiting).

## 3. Typography Rules
System default sans (Roboto) via Material 3 type scale; fluid, no custom font files.
- Headlines (TopBar title, Hero endpoint): semibold, tight letter-spacing, high contrast.
- Body/labels (kill-switch descriptions, VPN privacy explainer): regular, relaxed line-height for legibility.
- Terminal console: monospace stream with syntax highlights; empty state in muted regular.
- Token/QR: masked monospace token with show/hide; fingerprint in compact mono pill.

## 4. Component Stylings
* **Buttons:** Pill-shaped (`rounded-full`) security pills and quick-integration chips; primary power toggle is a large rounded-hero switch with pulsing live beacon; copy/rotate actions are sleek one-tap chips; primary autonomous setup button ("⚡ Grant Full Agent Access") is high-emphasis filled button with high-contrast text; "Revoke All (Safe Mode)" outlined kill-switch for immediate lockdown.
* **Cards/Containers:** Generously rounded large cards (16–28dp) on soft elevated surfaces with whisper-soft diffused shadows (no heavy drops); sections: ServerHero, AgentAccess (one-button autonomous setup command center with 3-tier status beacons: FULL ACCESS, GATES ON • PERMS PENDING, RESTRICTED), AgentPairing, SecurityGuardrails (with batch "Enable all" / "Disable all"), ExternalCapabilities, NetworkTunnel, SystemHealth, ActivityConsole, Appearance. Consistent 12–16dp internal spacing.
* **Inputs/Forms:** Kill-switches as labeled switch rows with professional names (`Microphone Audio`, `Camera & Video`, `Geolocation`, `Bluetooth Hardware`, `Screen & UI Automation`, `Network Diagnostics`, `Third-party Tools`) + explanatory subtitles + status badges; appearance selector as System/Light/Dark chips with preview; token field masked with copy chips.

## 5. Layout Principles
Single scrollable dashboard column (fixes below-fold Rotate button); section navigation with generous whitespace, 12–16dp rhythm, center-weighted hero at top tapering to console at bottom. Safe-zone discipline: launcher foreground keeps logo in central 66dp circle; in-app keeps QR/token unclipped with high-contrast container. Mirror-visible by default; the Secure-screen switch opts into `FLAG_SECURE` for setups that need token/QR hidden from mirrors/recents. WCAG AA contrast throughout; test-tagged semantics preserved (`DashboardUiTest`).

## 6. Decision Log (brainstorming lock)
- **M3 + dynamic color over custom theme:** chosen for Play polish + Material You (ADR-0020); rejected fully custom DS (maintenance cost).
- **Hero + guardrail cards over settings list:** chosen for one-glance server state + explicit kill-switch ownership; rejected nested settings (slower triage).
- **One-button full agent permission over multi-card toggling (ADR-0037):** chosen to eliminate 8+ setup steps for agent autonomy with instant safe-mode revoke; retained granular switches for precise scoping.
- **Decoupled DashboardCategory enum (ADR-0037):** separated presentation navigation from monolithic DashboardScreen for clean modular architecture.
- **Terminal console over plain log list:** chosen for agent-developer mental model + copy-log action; accepted mono legibility trade-off.
- **Secure window default OFF (ADR-0038):** operator drives over screen mirror, so mirror-visible is the default; the Appearance switch (with explicit token-exposed warning) opts back into `FLAG_SECURE`. Supersedes the ON-default for fresh installs; stored ON choices are still honored.
