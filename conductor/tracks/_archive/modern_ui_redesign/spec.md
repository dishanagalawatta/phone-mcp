# Track Specification: Modern UI Redesign

## Goal
Redesign and deeply refactor the Phone MCP Android application's presentation layer to deliver a sleek, clean, modern, professional Material 3 interface with intuitive layouts, polished typography, refined components, responsive status indicators, and crystal-clear feature naming.

## Functional Requirements
1. **Header & Status Banner**:
   - Master App Bar with vector branding logo (`ic_logo`), version indicator, live server beacon pill, and quick Theme Mode switch.
2. **Server Control Hub**:
   - Hero Server Card with live animated/pulsing state indicator (Online / Idle), one-touch power button, local endpoint display with one-tap copy, and TLS security fingerprint.
   - Quick Integrations: Sleek pill buttons for Quick Settings Tile and Home Widget.
3. **Agent Pairing & Security**:
   - Masked Bearer Token with intuitive Show/Hide, one-tap Copy Token, rotated token confirmation, and expandable high-contrast QR code container.
4. **Security Guardrails (Hardware Kill-Switches)**:
   - Clear, professional feature names and explanatory descriptions:
     - Microphone Audio (`toggle_mic`)
     - Camera & Video (`toggle_camera`)
     - Geolocation (`toggle_location`)
     - Bluetooth Hardware (`toggle_blewrite`)
     - Screen & UI Automation (`toggle_ui`)
     - Network Diagnostics (`toggle_net`)
5. **Network Packet Inspection Tunnel**:
   - Dedicated card showing VPN tunnel capture status, privacy assurance, and Start/Stop VPN controls.
6. **System Permissions & Accessibility Health**:
   - Comprehensive status indicator for all required permissions with individual chip badges and deep link to Accessibility Settings.
7. **Live Activity Console**:
   - Monospace terminal stream with syntax highlights, copy log action, and clean empty state.
8. **Appearance & Preferences**:
   - System, Light, and Dark mode selector with preview.

## Non-Functional Requirements & Constraints
- Preserve full backward compatibility with instrumentation and UI tests (`DashboardUiTest.kt` test tags and semantic assertions).
- Zero leaks of Android framework code outside `presentation/` or into `mcp/`.
- Clean M3 styling, accessible contrast (WCAG AA), fluid typography, and consistent 12-16dp spacing.
