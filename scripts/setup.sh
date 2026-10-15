#!/bin/bash
# Dev environment check for phone-mcp (Linux).
set -euo pipefail
JDK_CANDIDATE="${HOME}/tools/jdk17/bin/java"
echo "== phone-mcp setup check =="
if [ -x "$JDK_CANDIDATE" ]; then echo "OK java: $JDK_CANDIDATE"; "$JDK_CANDIDATE" -version 2>&1 | head -2; else echo "MISSING java 17 at $JDK_CANDIDATE"; fi
if [ -d "$HOME/Android/Sdk" ]; then echo "OK Android SDK: $HOME/Android/Sdk"; ls "$HOME/Android/Sdk/platforms" 2>/dev/null || true; else echo "MISSING Android SDK"; fi
command -v adb >/dev/null && echo "OK adb: $(adb version 2>&1 | head -1)" || echo "MISSING adb"
if command -v adb >/dev/null 2>&1; then
  echo "-- adb devices --"; adb devices 2>/dev/null | sed 's/^/  /' || true
  echo "-- adb forward --list (USB MCP path needs: adb forward tcp:8080 tcp:8080 — see scripts/usb-mcp.sh setup) --"
  adb forward --list 2>/dev/null | sed 's/^/  /' || true
fi
echo "Next: open this folder in Android Studio (Giraffe+), let it install Gradle wrapper + SDK 35, then Run 'app'."
echo "Verify MCP: ./scripts/verify-mcp.sh <phone-lan-ip>:8080"
