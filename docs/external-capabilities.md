# External App Capabilities — provider integration guide

Other Android apps on the same phone can publish tools to agents through
Phone MCP — no Phone MCP rebuild needed. Published tools appear in
`tools/list` as `ext_<capabilityId>__<tool>` once the user enables
**External App Capabilities** on the dashboard (default OFF).

Full decision record: `docs/adr/0032-dynamic-capabilities.md`.

## Concepts

| Term | Meaning |
|---|---|
| Capability | One provider app's named tool bundle (`capabilityId` `[a-z0-9]{1,20}`) |
| Namespaced tool | `ext_<capabilityId>__<tool>` (≤64 chars, MCP tool-name charset) |
| `capabilitiesVersion` | Monotonic counter in `serverInfo`/`GET /info`/`capabilities_list`; bumps on every list change |

Limits: 32 capabilities, 16 tools each, 120 s heartbeat TTL (re-announce or
call `heartbeat` to stay listed), 25 s per-invocation bound.

## Option A — bound service (recommended, invocation + registration)

A complete working example lives in `sample-provider/` (zero-dependency
`SampleProviderService` + one-screen announcer; install it and
`capabilities_rescan` to see the full loop). It exposes `demo: echo, add,
time` — the same app the on-device E2E (`ExternalE2EDeviceTest`) exercises.

Expose a `Service` answering `com.phonemcp.server.action.PROVIDE_CAPABILITY`:

```xml
<service android:name=".phonemcp.ProviderService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.phonemcp.server.action.PROVIDE_CAPABILITY" />
    </intent-filter>
</service>
```

Answer two `Messenger` messages (`what` 1 = describe, 2 = invoke; reply
`what` 3 with a `Bundle`):

- describe → `{ok:true, descriptorJson:"{capabilityId, displayName, version,
  tools:[{name, description, inputSchema}]}"}` (`inputSchema` must be
  `{"type":"object", …}`).
- invoke → extras `{tool, argsJson}` → `{ok:true, resultText:"…"}` or
  `{ok:false, error:"…"}` (`resultText` becomes the MCP `text` content).

Then nudge Phone MCP to fetch it:

```kotlin
context.sendBroadcast(
    Intent("com.phonemcp.server.action.CAPABILITY_ANNOUNCED")
        .setPackage("com.phonemcp.server")
        .putExtra("com.phonemcp.server.extra.PACKAGE", context.packageName)
)
```

## Option B — directory provider `call()` (register without a service)

For metadata-only registration (invocation still needs Option A):

```kotlin
val uri = Uri.parse("content://com.phonemcp.server.capabilities/unused")
val out = contentResolver.call(uri, "register", null, Bundle().apply {
    putString("descriptorJson", descriptorJson)
})
// out.getBoolean("ok"), out.getString("resultText") == "ext_id__tool,…"
```

Identity is Binder-verified: you can only touch your own package's record.
Keep the listing alive with `call(uri, "heartbeat", capabilityId, null)`
at least every 2 minutes.

## Option C — agent-driven (no Android code)

Any bearer-authenticated agent can provision and inspect:

```bash
# list (note capabilitiesVersion)
curl … -d '{"jsonrpc":"2.0","id":1,"method":"tools/call",
  "params":{"name":"capabilities_list","arguments":{}}}'
# rescan installed provider apps
curl … -d '{"jsonrpc":"2.0","id":2,"method":"tools/call",
  "params":{"name":"capabilities_rescan","arguments":{}}}'
```

## Calling a third-party tool

Standard `tools/call` with the namespaced name. Invocation needs two
consents: the dashboard External switch ON (else `isError` "disabled by
kill-switch") AND per-capability approval — dashboard Approve button or
`capabilities_approve` (else `isError` "not approved"). Signer pins are
enforced: a re-registration with a changed signing key is rejected until
the old record is unregistered.

```bash
curl … -d '{"jsonrpc":"2.0","id":3,"method":"tools/call",
  "params":{"name":"ext_notes__search","arguments":{"q":"milk"}}}}'
```

Re-call `tools/list` when `capabilitiesVersion` changes; `initialize`
advertises `capabilities.tools.listChanged: true` per MCP 2025-06-18.
For live updates, hold `GET /mcp/events` (bearer auth, SSE) open — the
server pushes `notifications/tools/list_changed` on every change.
