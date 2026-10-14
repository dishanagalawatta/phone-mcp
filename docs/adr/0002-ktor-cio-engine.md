# ADR-0002: Ktor CIO embedded engine

## Status
Accepted

## Context
Need embedded HTTP server inside APK. Options: Ktor CIO, Ktor Netty, NanoHTTPD.

## Decision
`ktor-server-cio`. Pure-Kotlin coroutine I/O, ~1-2 MB, no native deps.

## Rationale
Netty adds ~4-8 MB + native/epoll assumptions with historic Android startup issues. Jetty/Tomcat need containers. CIO serves HTTP/1.1 chunked, sufficient for MCP JSON + sidecar media.

## Consequences
No HTTP/2 (irrelevant). Configure `connectionIdleTimeoutSeconds`, single CIO engine for in-app server.
