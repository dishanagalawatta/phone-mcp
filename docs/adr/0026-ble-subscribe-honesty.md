# ADR-0026: Honest BLE subscribe failures + spec-correct CCCD value

- **Status:** Accepted
- **Date:** 2026-10-14
- **Context:** Live verification against a lab BLE HID gadget (test peripheral) showed
  two defects in `BleGatt.subscribe`, both well-formed (no crashes) but wrong:
  (1) subscribing to an HID report characteristic failed with "BLE permission
  missing (BLUETOOTH_CONNECT)" while the grant was held — the real cause was a
  framework SecurityException from the peripheral-gated CCCD write (Samsung
  demands system-level BLUETOOTH_PRIVILEGED for unbonded HID CCCD); the message
  lied. (2) `subscribe` always wrote ENABLE_NOTIFICATION_VALUE (0x0001), so an
  indicate-only characteristic (Service Changed 0x2A05) was rejected by the
  peripheral (status 6).
- **Decision:**
  1. `subscribeFailureMessage(granted, cause)` (pure, JVM-tested): keeps the
     legacy text when the grant is absent; otherwise reports "BLE CCCD setup
     rejected by peripheral/link (BLUETOOTH_CONNECT granted: <cause>;
     peripheral may require bonding)".
  2. Subscribe failures with the grant held surface as IllegalStateException
     (domain failure → `isError:true`), not SecurityException: RadioProvider's
     outer catch rewrites every SecurityException to "permission missing",
     which masked the honest text end-to-end (found live: the first fix attempt
     changed nothing observable until the exception type was re-routed).
  3. `cccdEnableValue(properties)` (pure, JVM-tested): 0x0001 when NOTIFY is
     supported, else 0x0002, per Bluetooth Core Spec Vol 3 Part G §3.3.3.3.
     Uses local byte literals (not the framework constants) so it stays
     runnable in plain JVM unit tests. The permission probe itself is guarded
     `SDK_INT >= S` (API-guarded like REQUIRED_PERMISSIONS; lint-clean).
- **Alternatives considered:** (a) Changing RadioProvider's outer catch instead
  — rejected: the outer catch is the last line of defense for true denials
  across all BLE tools; re-typing at the source keeps each layer's contract
  intact. (b) String-matching the old text in the outer layer — rejected:
  brittle coupling, worse than the type-level fix.
- **Consequences:** + Live re-proof: HID subscribe now surfaces the PRIVILEGED
  root cause; 0x2A05 subscribe turned status-6 rejection into a clean
  `count:0` collect. + 6 new JVM tests (BleGattTest). − Bonding-gated HID
  notify remains impossible without a system pairing flow (no tool for it;
  correctly reported, not silently retried).
