# ADR-0005: Sidecar /media URLs over inline base64

## Status
Accepted

## Context
Camera/mic/video blobs must reach agents. Options: inline base64 in tool result vs sidecar HTTP URLs.

## Decision
Providers write to `cacheDir/mcp/{uuid}` and return `{mediaUrl, mimeType, expiresAt}`. Agent fetches `GET /media/{id}` with Range support. TTL 15 min, 100 MB cap.

## Consequences
Avoids JSON bloat/OOM, enables streaming. Cost: two fetches; document in tool descriptions.
