# External App Capabilities — integration guide

Other Android apps on the same phone can publish tools to AI agents through
Phone MCP — no Phone MCP rebuild needed. This guide is for **app developers**
who want their app's functionality callable by agents. (If you are an **agent**
wanting to *use* third-party tools, read `.agents/skills/phone-mcp/references/external-apps.md`
instead — same concepts, agent framing.)

Decision records: ADR-0032 (module), ADR-0033 (push), ADR-0034 (consent),
ADR-0035 (persistence). Normative wire reference: `docs/api.md`.

## How it fits together

```text
Your app                          Phone MCP                        Agent
──────────                        ─────────                        ─────
PROVIDE service  ←──bind+invoke── ServiceToolInvoker
   (describe+invoke                    │
    over Messenger)                    ▼
                              ExternalCapabilityRegistry
                              (ext_<id>__<tool>, consent,
                               heartbeat TTL, persistence)
                                       │ ▲
announce broadcast ──hint──────────┘ │ │ GET /mcp/events (push)
directory provider ──register/hb─────┘ │ tools/list + tools/call
                                       ▼
                                  dashboard (switch + approvals)
```

- **Namespacing:** your tool `search` in capability `notes` becomes
  `ext_notes__search` (capability id `[a-z0-9]{1,20}`, tool
  `[a-z][a-z0-9_]{0,29}`, full name ≤64 chars).
- **Trust:** invocation needs the dashboard External master switch ON
  (default OFF) **and** per-capability approval (default deny). Your signing
  cert SHA-256 is recorded; a re-registration with a changed key is rejected
  until the old record is removed.
- **Freshness:** records expire after 120 s without a heartbeat; approval
  and pins persist across restarts in encrypted storage.

## Quickstart (5 minutes, with the sample)

`sample-provider/` is a complete zero-dependency example (`demo: echo, add,
time`). Build, install next to Phone MCP, launch it (it announces on start):

```bash
./gradlew :sample-provider:assembleDebug
adb install -r sample-provider/build/outputs/apk/debug/sample-provider-debug.apk
adb shell am start -n com.phonemcp.sampleprovider/.MainActivity
```

Then, as any bearer-authenticated agent (replace `$TOKEN`):

```bash
BASE=https://<phone-lan-ip>:8080
# 1. pull the authoritative descriptor from the sample app
curl -sk -X POST $BASE/mcp -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'MCP-Protocol-Version: 2025-06-18' -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"capabilities_rescan","arguments":{"packageName":"com.phonemcp.sampleprovider"}}}'
# 2. approve it (or tap Approve on the dashboard card)
curl -sk -X POST $BASE/mcp ... \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"capabilities_approve","arguments":{"capabilityId":"demo"}}}'
# 3. call it (needs the dashboard External switch ON)
curl -sk -X POST $BASE/mcp ... \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"ext_demo__echo","arguments":{"text":"hello"}}}'
```

## Integration checklist (your app)

1. **Declare the service** (exported, with the exact action):
   ```xml
   <service android:name=".phonemcp.ProviderService" android:exported="true">
       <intent-filter>
           <action android:name="com.phonemcp.server.action.PROVIDE_CAPABILITY" />
       </intent-filter>
   </service>
   ```
2. **Answer two Messenger messages** (`MSG_DESCRIBE what:1`, `MSG_INVOKE
   what:2`; reply `MSG_RESULT what:3` to `message.replyTo`):
   - describe → extras `{ok:true, descriptorJson:"{…}"}` (schema below).
   - invoke → extras `{tool, argsJson}` → `{ok:true, resultText:"…"}` or
     `{ok:false, error:"…"}`. `resultText` becomes the MCP `text` content.
     Keep handlers fast (20 s bind+execute bound) and side-effect-safe;
     never leak other apps' data into results.
3. **Announce on install/update/launch** (hint only — Phone MCP re-fetches
   the descriptor from your service, never trusts extras):
   ```kotlin
   sendBroadcast(Intent("com.phonemcp.server.action.CAPABILITY_ANNOUNCED")
       .setPackage("com.phonemcp.server")
       .putExtra("com.phonemcp.server.extra.PACKAGE", packageName))
   ```
4. **Stay listed:** re-announce (or call the directory provider's
   `heartbeat`) at least every 2 minutes while your tools should be visible.
5. **Tell the phone owner** to enable the External switch and tap Approve —
   without both, agents get explicit `isError` denials pointing at them.

## Descriptor schema

```json
{
  "capabilityId": "notes",
  "displayName": "Notes",
  "version": "1",
  "tools": [
    {
      "name": "search",
      "description": "Full-text search over notes",
      "inputSchema": {
        "type": "object",
        "properties": { "q": { "type": "string", "description": "Query" } },
        "required": ["q"]
      }
    }
  ]
}
```

Rules enforced on registration (violations are rejected with reasons, never
stored): `capabilityId` `[a-z0-9]{1,20}`; 1–16 tools; tool names
`[a-z][a-z0-9_]{0,29}`, unique, fitting the 64-char namespaced form;
descriptions 1–2000 chars; `inputSchema` an object with `"type":"object"`,
≤8192 bytes. One capability id has exactly one owning package; a different
signing key must unregister first.

## Alternative transports (no service)

- **Directory provider** (`content://com.phonemcp.server.capabilities/…`):
  `query()` lists capabilities/tools publicly; `call("register"|"unregister"|"heartbeat")`
  mutates only your own Binder-verified package's record. Metadata-only —
  invocation still needs the bound service.
- **Agent-driven provisioning:** `capabilities_register` creates the record
  for an installed provider app without any app-side code (invocation still
  binds to that package's service).

## Testing your integration

1. `capabilities_rescan` with your package → `registered:1`.
2. `capabilities_list` → your id, tools, `approved:false`, signer prefix.
3. `capabilities_approve` → `approved:true` in the next list.
4. Enable the External switch → `tools/call ext_<id>__<tool>` returns your
   `resultText`; bad args return your `error` as `isError`.
5. Force-stop Phone MCP, restart, re-list → record, approval, and pin are
   restored (fresh heartbeat window).
6. Hold `GET /mcp/events` open across a re-announce → one
   `notifications/tools/list_changed` frame per change.

## Troubleshooting

| Symptom | Likely cause → fix |
|---|---|
| `rescan` finds 0 | Service action misspelled / not exported / app not installed → compare with the sample manifest literally |
| Registration rejected | Read the reason: bad id/tool name, oversize schema, duplicate names, or id owned by another package |
| `signer changed` rejection | App re-signed → `capabilities_unregister` the old record, re-register |
| `isError` kill-switch | Dashboard External switch OFF → owner enables it |
| `isError` not approved | Consent missing → `capabilities_approve` or dashboard Approve |
| Calls time out (>25 s) | Handler blocks (main thread, network, deadlock) → move work off, answer fast or fail with `error` |
| Record vanishes after 2 min | No heartbeat → re-announce on a timer while serving |
| `Unknown tool` (-32602) | Name doesn't match `ext_<id>__<tool>` or record expired → re-list first |

Limits recap: 32 capabilities device-wide, 16 tools each, 120 s heartbeat
TTL, 25 s per-call bound, 8 KB schema cap. Records (including approval and
signer pins) persist across restarts in encrypted storage.
