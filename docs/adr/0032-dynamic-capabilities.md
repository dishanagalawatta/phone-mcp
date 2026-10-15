# ADR-0032: Dynamic third-party capabilities over local IPC

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** Phone MCP's tool set was fully static: every tool is a Hilt
  `@IntoSet` `ToolProvider` compiled into the APK, so other on-device apps
  could not expose their own tools/resources to agents through this MCP
  server. Agents also had no way to discover app capabilities beyond the 41
  built-ins. The request: local IPC registration + a modular architecture so
  third-party apps plug in dynamically.
- **Decision:** A first-class dynamic capability module:
  - `mcp/CapabilityIpcContract.kt` (pure Kotlin): shared constants —
    `ext_<capId>__<tool>` namespacing (≤64 chars, MCP tool-name charset),
    descriptor validation, Messenger codes, provider paths, registry limits.
  - `mcp/ExternalCapabilityRegistry.kt` (pure Kotlin, Hilt `@Singleton`):
    ownership-checked register/replace, heartbeat TTL (120 s), per-package
    ownership, 32-capability / 16-tool caps, monotonic `version` counter,
    25 s proxied-call bound. `McpRouter` merges registry tools into
    `tools/list`, routes `ext_*` calls outside its static index, advertises
    `capabilities.tools.listChanged: true`, and exposes
    `externalTools/externalCapabilities/capabilitiesVersion` in `serverInfo`
    (+ `GET /info`).
  - Three localhost-only transports: (1) explicit bound-service Messenger
    channel (`data/ServiceToolInvoker.kt`, `ACTION_PROVIDE`) for invocation
    and authoritative descriptor fetch; (2) exported directory
    ContentProvider (`service/CapabilityDirectoryProvider.kt`) for public
    listing + `call()` register/unregister/heartbeat keyed by
    Binder-verified `callingPackage`; (3) announce broadcast
    (`service/CapabilityAnnounceReceiver.kt`) treated as an untrusted rescan
    hint that re-reads the descriptor from the named package itself.
    Discovery is intent-based via a manifest `<queries>` block (no
    `QUERY_ALL_PACKAGES`).
  - Management plane: `data/ExternalCapabilityProvider.kt`
    (`capabilities_list/register/unregister/rescan`, always ungated).
    Invocation plane: new `Gatekeeper.TOOLS_EXT = "ext_"`, default OFF
    (untrusted third-party code), with a dashboard master switch.
  - The directory provider resolves singletons via a Hilt `@EntryPoint`
    (`di/CapabilityDirectoryEntryPoint.kt`, lazily per call):
    `@AndroidEntryPoint` does not support ContentProviders, and the provider
    starts before `Application.onCreate`.
  - Dashboard: guardrail toggle (`toggle_external`), capability directory
    card with remove/rescan, `DashboardViewModel` bound to the registry
    snapshot flow.
- **Alternatives considered:** (a) AIDL interface — rejected: heavier
  provider-side integration (aidl files, Stub) vs Messenger+JSON which any
  app implements in ~30 lines (sample in `docs/external-capabilities.md`).
  (b) ContentProvider-only invocation — rejected: `call()` is synchronous
  Binder with no streaming story and awkward timeouts; kept for
  register/heartbeat where it shines (reliable `callingPackage`).
  (c) Trusting broadcast extras for registration — rejected: sender
  spoofable pre-API-34; broadcasts are hints only.
  (d) Static manifest-declared capabilities without registry — rejected:
  no runtime join/leave, no heartbeat expiry, no agent-driven rescan.
- **Consequences:** + Any app can publish agent-usable tools with no Phone
  MCP rebuild; agents see them via standard `tools/list` (+ version deltas).
  + Security posture preserved: bearer auth, `ext_` default OFF, ownership
  checks, size caps, timeouts, signer-SHA256 audit trail.
  − `notifications/tools/list_changed` is advertised but delivered
  best-effort (poll `capabilitiesVersion`/`tools/list`); live SSE push needs
  a dedicated notification channel (tracked next step). − No provider-side
  SDK yet; integration is documented sample code.
