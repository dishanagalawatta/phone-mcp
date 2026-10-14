# ADR-0003: Hand-rolled MCP protocol layer

## Status
Accepted

## Context
No official Kotlin MCP SDK. Options: reference Java SDK (`io.modelcontextprotocol.sdk:mcp`) vs hand-rolled Ktor + kotlinx.serialization.

## Decision
Hand-roll `mcp/` module (JsonRpc, McpRouter, ToolDefinition) on kotlinx.serialization. Zero `android.*` imports.

## Rationale
Java SDK pulls Reactor + Jackson + Servlet transport — heavy for APK, no Android transport, Java 17 weight. Hand-roll gives coroutine-native control over session, version headers, timeouts.

## Consequences
Own conformance burden. Mitigate with Inspector + `scripts/verify-mcp.sh` + JVM unit tests on `mcp/`.
