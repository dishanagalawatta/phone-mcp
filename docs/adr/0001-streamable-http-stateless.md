# ADR-0001: Streamable HTTP stateless JSON

## Status
Accepted

## Context
MCP 2025-03-26+ defines Streamable HTTP (single POST /mcp). Phone must serve heterogeneous agents over LAN. Need to choose stateful/SSE vs stateless JSON.

## Considered Options
- **Stateless JSON responses**: single JSON object per request. Simple, all clients support it.
- **SSE streaming**: request-scoped event streams, server push. Real-time but complex buffering/battery.
- **Legacy HTTP+SSE (2024-11-05)**: deprecated, do not use.
- **Custom WebSocket**: non-standard, breaks Inspector compat.

## Decision
Stateless `Content-Type: application/json` responses in v1. No GET SSE stream (return 405). Target `2025-03-26` minimum, negotiate `2025-06-18` via `MCP-Protocol-Version` header.

## Consequences
Positive: minimal complexity, lowest battery, broadest client compat.
Negative: no live push; continuous sensors require client polling.
