package com.phonemcp.server.service

import java.security.SecureRandom
import java.util.Base64

/**
 * Pure bearer-token helpers (no Android imports, JVM-testable).
 * Storage lives in [TokenStore]; comparison is constant-time at the call site.
 */
object AuthToken {
    /** 256-bit random, URL-safe base64, 43 chars, no padding. */
    fun generate(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Extracts the token from an `Authorization` header, or null when absent/malformed. */
    fun extract(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split(Regex("\\s+"), limit = 2)
        if (parts.size != 2 || !parts[0].equals("Bearer", ignoreCase = true)) return null
        return parts[1].takeIf { it.isNotEmpty() }
    }
}
