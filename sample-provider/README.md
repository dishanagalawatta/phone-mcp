# Sample Provider (demo)

Zero-dependency reference app showing how any Android app publishes tools to
Phone MCP agents. Exposes capability `demo` with `echo`, `add`, `time` over
the bound-service Messenger protocol. Full integration guide:
`../docs/external-capabilities.md` (normative contract:
`../app/src/main/java/com/phonemcp/server/mcp/CapabilityIpcContract.kt` —
the string literals here must match it).

## Build + install

```bash
./gradlew :sample-provider:assembleDebug
adb install -r sample-provider/build/outputs/apk/debug/sample-provider-debug.apk
adb shell am start -n com.phonemcp.sampleprovider/.MainActivity  # announces on launch
```

## Try it (as a paired agent)

```bash
# pull the descriptor, approve, call (needs dashboard External switch ON)
tools/call capabilities_rescan        # {"packageName":"com.phonemcp.sampleprovider"}
tools/call capabilities_approve       # {"capabilityId":"demo"}
tools/call ext_demo__echo             # {"text":"hello"} -> "hello"
tools/call ext_demo__add              # {"a":2,"b":3} -> "5"
```

Covered live by `ExternalE2EDeviceTest` (rescan → approve → proxied
`tools/call`, unknown-tool `-32602`, consent cleanup).
