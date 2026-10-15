# Play Release — internal testing track

Goal: first upload of `com.phonemcp.server` to Play Console internal testing.
Not a production launch (blockers listed at the bottom).

## 0. What changed in-repo for this track

- Manifest trimmed to foreground-only: `ACCESS_BACKGROUND_LOCATION`, `ACTIVITY_RECOGNITION`,
  `BODY_SENSORS` removed (declared but never used — Play rejects unjustified sensitive perms).
- Adaptive launcher icon configured with production brand assets (`mipmap-anydpi-v26/ic_launcher.xml`,
  `ic_launcher_round.xml`, `drawable/ic_launcher_{foreground,background,monochrome}.xml`, plus mipmap densities).
- `app/build.gradle.kts`: env-based release signing, R8 explicitly off, versionCode scheme.
- `config/proguard-rules.pro`: canonical keeps, inert until R8 is enabled deliberately.

## 1. Upload key (one time, never commit)

```bash
mkdir -p ~/keys
keytool -genkeypair -v -keystore ~/keys/phone-mcp-upload.jks \
  -alias phone-mcp -keyalg RSA -keysize 2048 -validity 10000
export PHONE_MCP_KEYSTORE_FILE=~/keys/phone-mcp-upload.jks
export PHONE_MCP_KEYSTORE_PASSWORD='<store-pass>'
export PHONE_MCP_KEY_ALIAS=phone-mcp
export PHONE_MCP_KEY_PASSWORD='<key-pass>'
```

Back up `~/keys/` offline. Play App Signing re-signs for distribution; this key is only the upload key.

## 2. Build the bundle

```bash
./gradlew :app:bundleRelease   # needs the env above, else debug-signed (rejected on upload)
# artifact: app/build/outputs/bundle/release/app-release.aab
```

Direct install (no Play link needed): `release/phone-mcp-v0.3.0-debug.apk`
(prebuilt debug-signed APK, 73 MB, `versionCode 3` / `0.3.0`, SHA-256 in
`release/phone-mcp-v0.3.0-debug.apk.sha256`) — `adb install -r` it, then run
`docs/device-matrix.md` §4–6. Rebuild with `./gradlew :app:assembleDebug`.

Rules: bump `versionCode` +1 on **every** upload (Play rejects reuse); `versionName` follows
`0.<minor>.<patch>` (`0.1.0` = first internal build).

## 3. Console setup (first upload)

1. Create app → `com.phonemcp.server`, category Productivity/Tools, no ads, no IAP.
2. App signing: enroll in Play App Signing (required for new apps).
3. Internal testing → create release → upload AAB → add tester emails/list.
4. Content rating: utility questionnaire (no user content, no comms) → Everyone.
5. Target audience: 18+ recommended (LAN server tool, open auth — not for children).
6. Privacy policy URL: host `docs/privacy-policy.md` (rendered) somewhere public and paste the link.
7. Data safety form — answer table:

| Question | Answer |
|----------|--------|
| Location (precise) collected? | Yes — on-device GPS only while server runs, sent to the user's own LAN agent |
| Microphone / camera collected? | Yes — clips only on explicit agent call while server runs, to the LAN agent |
| Sensors, BLE, battery? | Yes — device state to the LAN agent |
| Network traffic (per-app connections, DNS/SNI metadata)? | Yes — only after the user enables the Network toggle AND approves the system VPN consent sheet; HTTPS bodies are never decrypted; exports go to the LAN agent only |
| Shared with third parties? | No |
| Encrypted in transit? | Yes — TLS 1.2+ with on-device self-signed cert (TOFU fingerprint in dashboard/QR) |
| Access control? | Yes — bearer token required on all data endpoints (dashboard QR pairing + rotation; ADR-0009) |
| Data deletion requestable? | Media cache auto-evicts (15 min / 100 MB); no account data exists |

8. Permission declarations: location = foreground in-use only (no background declaration present);
   camera/mic = in-use features. **VPN (`BIND_VPN_SERVICE`) needs its own prominent disclosure**
   (system consent sheet + in-app explainer already in the dashboard; the store listing must describe
   per-app diagnostics capture, no TLS breaking, LAN-agent-only exports) — expect heightened review
   combined with the accessibility declaration. (`PACKAGE_USAGE_STATS` is intentionally not
   declared — signature-level, lint-blocking; a future `NetworkStatsManager` upgrade must
   justify it then.) Expect a reviewer demo video request — record: grant flow →
   dashboard toggles → agent `camera_capture` call → photo in agent. Keep the video unlisted for reuse.

## 4. Store listing assets (internal track can be minimal, production cannot)

| Asset | Spec | Status |
|-------|------|--------|
| Icon 512×512 PNG | brand asset (white background, rounded corners) | Ready (`assets/logo/app-icon.png`) |
| Feature graphic 1024×500 | brand asset | TODO |
| Phone screenshots (2–8) | dashboard, toggles, log, Inspector call | TODO — capture on a Pixel / test device |
| Short description / full description | what it does + open-auth warning | TODO |

## 5. Rollout + feedback loop

Internal testers: install from the testing link → run `docs/device-matrix.md` §4–6 → file issues
with API level + tool name + dashboard log lines. Promote to closed testing only after the matrix
is green on 29/33/35 + one physical device.

## 6. Production blockers (do NOT launch publicly until cleared)

1. **TOFU trust UX**: self-signed is declared in data safety; public-listing users need
   in-app fingerprint guidance (already in dashboard) — no further crypto work.
2. **R8: DONE 2026-10-12** — enabled, rules validated by full 14-test suite on the shrunk
   release APK on-device (43 → 21 MB). Keep rules: Kotlin runtime, Hilt/`javax.inject`,
   Guava ListenableFuture, Netty channel/buffer/bootstrap/util, test-visible
   lifecycle/compose/activity/savedstate/tracing. Tighten `com.phonemcp.**` keep-all only
   with another green device-matrix re-run.
3. **Branded listing assets** (icon completed; feature graphic & screenshots pending) + hosted privacy policy.
4. **`dataSync` 6 h ceiling (API 35)** for marathon sessions — document or move to a fitting FGS type.
