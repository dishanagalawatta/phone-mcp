# Implementation Plan: Modern UI Redesign

## Phase 1: Theme & Color Palette Modernization
- [x] Task 1.1: Verify and enhance brand color palette, surface container elevations, and typography scale in `theme/Color.kt` and `theme/Theme.kt`.

## Phase 2: Modular Modern UI Components
- [x] Task 2.1: Implement reusable `StatusBadge` and `PhoneMcpTopBar` with live status indicators and quick theme actions.
- [x] Task 2.2: Implement `ServerHeroCard` with modern power toggle, live network endpoint chip, TLS certificate pill, and quick integration actions.
- [x] Task 2.3: Implement `AgentPairingCard` with masked token, copy chips, and sleek QR code presentation.
- [x] Task 2.4: Implement `SecurityGuardrailsCard` with relabeled hardware kill-switches, clear feature descriptions, and status badges.
- [x] Task 2.5: Implement `NetworkTunnelCard` with dedicated VPN controls and privacy explainer.
- [x] Task 2.6: Implement `SystemHealthCard` with permissions status, individual chips, and accessibility settings integration.
- [x] Task 2.7: Implement `ActivityConsoleCard` with terminal console styling, copy logs action, and empty state.

## Phase 3: Master Dashboard Assembly & ViewModel Sync
- [x] Task 3.1: Refactor `DashboardScreen.kt` to assemble the new layout hierarchy with section navigation, spacing, and accessibility tags.
- [x] Task 3.2: Verify `MainActivity.kt` and `DashboardViewModel.kt` integration.

## Phase 4: Testing, Verification & ADR Documentation
- [x] Task 4.1: Synchronize and run unit tests (`ThemeModeTest`, `DashboardCategoryTest`) and ensure compilation.
- [x] Task 4.2: Record Architecture Decision Record `docs/adr/0020-modern-ui-redesign.md` and update `docs/adr/README.md`.
- [x] Task 4.3: Update project documentation (`README.md`).
