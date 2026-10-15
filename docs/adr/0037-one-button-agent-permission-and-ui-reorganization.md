# ADR-0037: One-Button Full Agent Permission & UI Component Reorganization

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** Previously, preparing an Android device for AI agent autonomy required tedious manual steps: toggling seven separate hardware kill-switches (Microphone, Camera, Geolocation, Bluetooth LE Write, Screen & UI Automation, Network Inspection, and External Capability proxies), manually discovering missing system runtime permissions, approving external app capabilities individually, and inspecting accessibility service state. Furthermore, presentation-level components were mixed, with `DashboardCategory` defined directly inside `DashboardScreen.kt`.
- **Decision:**
  1. **One-Button Autonomous Agent Permission (`AgentAccessCard`)**: Introduced a dedicated primary command component (`AgentAccessCard`) in the Overview, Guardrails, and Settings sections. A single tap on "⚡ Grant Full Agent Access":
     - Activates all 7 Gatekeeper hardware kill-switches simultaneously (`Gatekeeper.enableAll()`).
     - Approves all registered third-party external capabilities.
     - Automatically invokes Android's runtime permission sheet for any missing permissions (`Manifest.permission.CAMERA`, `RECORD_AUDIO`, `ACCESS_FINE_LOCATION`, `BLUETOOTH_CONNECT`, etc.).
     - Surfaces a direct 1-tap deep-link to Android Accessibility Settings if UI automation remains unconfigured.
     - Provides a matching "Revoke All (Safe Mode)" kill-switch to immediately reset all gates to restricted mode.
  2. **Component Reorganization**:
     - Extracted `DashboardCategory` enum into its own dedicated file `presentation/DashboardCategory.kt`.
     - Added computed access assessment properties to `DashboardUiState` (`allGatesEnabled`, `allPermissionsGranted`, `hasFullAgentAccess`, `activeGateCount`, `grantedPermissionCount`) to keep UI composables clean and purely declarative.
     - Enhanced `SecurityGuardrailsCard` with top quick-actions ("Enable all" / "Disable all").
     - Structured `DashboardScreen` so the One-Button command card is prominently accessible in Overview, Guardrails, and Settings tabs without visual clutter.
- **Consequences:**
  + Eliminates 8+ friction steps when pairing an agent for testing or full automation.
  + Clean separation of concerns with `DashboardCategory.kt` and `AgentAccessCard.kt`.
  + 100% backward-compatible with all existing semantic test tags (`toggle_mic`, `toggle_camera`, `toggle_location`, etc.) and automated test suites.
  + Full test coverage in `GatekeeperTest`, `DashboardCategoryTest`, and `DashboardUiTest`.
