package com.phonemcp.server.service

import com.phonemcp.server.mcp.McpProtocol
import java.net.URI

/**
 * Pure transport helpers for the MCP HTTP layer (no Ktor/Android imports,
 * JVM unit-testable). Server lifecycle stays in [McpServerService].
 */
object McpHttp {
    /** Single-sourced from [McpProtocol]; kept here for transport-local callers/tests. */
    val SUPPORTED_VERSIONS: List<String> get() = McpProtocol.SUPPORTED_VERSIONS

    /**
     * DNS-rebinding guard: missing Origin is allowed (curl, native clients);
     * a present Origin must match the server host or be loopback.
     */
    fun isOriginAllowed(origin: String?, requestHost: String): Boolean {
        if (origin.isNullOrBlank()) return true
        val originHost = try {
            URI(origin).host
        } catch (e: Exception) {
            return false
        } ?: return false
        val o = originHost.lowercase()
        val rawHost = requestHost.lowercase().trim()
        val r = if (rawHost.startsWith("[")) {
            rawHost.substringAfter("[").substringBefore("]")
        } else {
            rawHost.substringBefore(":")
        }
        if (o == r) return true
        return o == "localhost" || o == "127.0.0.1" || o == "::1"
    }

    /**
     * Host allowlist for DNS-rebinding defense: the Host header must be the
     * server's LAN identity or loopback — never an attacker domain pointing
     * at the phone. Callers fail closed on blank/unparseable hosts.
     */
    fun isHostAllowed(hostHeader: String?, allowHosts: Set<String> = emptySet()): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val raw = hostHeader.lowercase().trim()
        val h = if (raw.startsWith("[")) {
            raw.substringAfter("[").substringBefore("]")
        } else {
            raw.substringBefore(":")
        }.trim()
        if (h.isEmpty()) return false
        if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
        if (allowHosts.isEmpty()) return true // no pin configured: Origin check remains the guard
        return allowHosts.any { it.lowercase() == h }
    }

    /** Since protocol 2025-06-18 every non-initialize request must carry this header. */
    fun isVersionHeaderValid(header: String?): Boolean =
        header != null && SUPPORTED_VERSIONS.contains(header)

    fun mimeTypeFor(fileName: String): String =
        when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "mp4" -> "video/mp4"
            "m4a" -> "audio/mp4"
            "wav" -> "audio/wav"
            else -> "application/octet-stream"
        }

    data class RangeSpec(val start: Long, val endInclusive: Long?)

    /** Upper bound for a POST /mcp JSON body. Largest legit tool args are single-digit
     * KB (clipboard 10k chars); 1 MiB leaves wide headroom while bounding parse RAM. */
    const val MAX_MCP_BODY_BYTES = 1024L * 1024L

    /** True when a declared Content-Length exceeds [MAX_MCP_BODY_BYTES] (or is negative).
     * Missing length (chunked) passes here — callers note the residual. Pure; JVM-testable. */
    fun isBodyTooLarge(contentLength: Long?, limit: Long = MAX_MCP_BODY_BYTES): Boolean =
        contentLength != null && (contentLength < 0 || contentLength > limit)

    /** Minimum RSA key size (bits) for the server TLS identity. Below this, modern
     * TLS stacks (OpenSSL seclevel ≥ 2, e.g. Python ssl) abort the handshake. */
    const val MIN_TLS_RSA_BITS = 2048

    /** True when [publicKey] is missing, non-RSA, or RSA below [MIN_TLS_RSA_BITS].
     * Pure; JVM unit-testable. Callers fail closed (rotate, never serve weak). */
    fun isTlsKeyTooWeak(publicKey: java.security.PublicKey?): Boolean {
        val rsa = publicKey as? java.security.interfaces.RSAPublicKey ?: return true
        return rsa.modulus.bitLength() < MIN_TLS_RSA_BITS
    }

    /** Parses a single "bytes=start-end" range. Returns null when malformed. */
    fun parseRange(header: String?): RangeSpec? {
        if (header == null || !header.startsWith("bytes=")) return null
        val range = header.removePrefix("bytes=").substringBefore(",").trim()
        val start = range.substringBefore("-").toLongOrNull() ?: return null
        if (start < 0) return null
        val endPart = range.substringAfter("-", "")
        val end = endPart.takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (end != null && end < start) return null
        return RangeSpec(start, end)
    }
}
