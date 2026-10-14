# ADR-0011: Netty engine for TLS termination (supersedes ADR-0002 for the server)

## Status
Accepted (supersedes ADR-0002's CIO choice for the embedded server)

## Context
ADR-0002 chose Ktor CIO (small, coroutine-native). TLS testing on-device proved CIO
silently ignores `sslConnector`: the service reported Running with zero sockets bound.
(Bytecode inspection: `ktor-server-cio` contains no SSL classes at all.)

## Decision
Use `ktor-server-netty` for the embedded HTTPS listener (documented `sslConnector` path,
honored by Netty's SslContext pipeline). Cost accepted: ~4–5 MB APK growth, larger thread
pools. CIO remains a fine choice for any future plain-HTTP or client use.

Related fix in the same pass: Ktor's cert builder defaults to SHA-1 — now pinned to
SHA-256/RSA explicitly after an on-device probe showed `SHA1withRSA` (rejected by
modern TLS stacks).
