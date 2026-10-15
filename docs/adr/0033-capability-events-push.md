# ADR-0033: Server-push for capability list changes over SSE

- **Status:** Accepted
- **Date:** 2026-10-15
- **Context:** ADR-0032 made the router advertise
  `capabilities.tools.listChanged: true`, but delivery was poll-only
  (`capabilitiesVersion` in `serverInfo`/`GET /info`). Per MCP 2025-06-18 a
  server declaring `listChanged` SHOULD send
  `notifications/tools/list_changed` — strict clients that never re-list
  would serve stale tools indefinitely.
- **Decision:** `GET /mcp/events` (`text/event-stream`, bearer-gated like
  every data endpoint): clients hold it open and receive the exact spec
  notification (`{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}`,
  no params) on every registry version bump, plus `:ping` comments every
  25 s as an idle keepalive. No per-subscriber server state — disconnect
  ends the stream; reconnect + `tools/list` resyncs. Shape/flow logic lives
  in `McpRouter.externalChangeNotification()`/`externalChanges()` (pure,
  JVM-tested); `McpServerService.serveEvents()` owns only transport
  (mutex-guarded writer, heartbeat child tied to the call scope).
- **Alternatives considered:** (a) Push over the per-call SSE streams —
  rejected: those are finite, one-shot `tools/call` responses, not a
  subscription channel. (b) Notification params carrying the version —
  rejected: spec example is method-only; extra params risk strict-client
  rejection (version stays discoverable via `capabilities_list`).
  (c) WebSocket — rejected: new transport, new auth story; SSE reuses the
  existing TLS + bearer plumbing.
- **Consequences:** + Declared `listChanged` is now honored, not just
  advertised. + Zero new dependencies; one new endpoint. − Clients must
  opt into holding the stream (documented in `docs/api.md` +
  `docs/external-capabilities.md`); poll fallback remains for short-lived
  clients.
