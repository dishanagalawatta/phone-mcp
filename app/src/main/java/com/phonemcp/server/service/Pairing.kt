package com.phonemcp.server.service

import java.security.MessageDigest

/**
 * Pure pairing/TLS helpers (no Android imports, JVM-testable).
 * The fingerprint is public information — safe to display, log, and embed in QR payloads.
 */
object Pairing {
    /** `{"url","token","sha256"}` envelope encoded in the dashboard QR. JSON-escaped. */
    fun pairingPayload(url: String, token: String, sha256: String): String =
        kotlinx.serialization.json.buildJsonObject {
            put("url", kotlinx.serialization.json.JsonPrimitive(url))
            put("token", kotlinx.serialization.json.JsonPrimitive(token))
            put("sha256", kotlinx.serialization.json.JsonPrimitive(sha256))
        }.toString()

    /** Lowercase hex SHA-256 of DER certificate bytes. */
    fun sha256Hex(der: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(der)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** `aabbcc…` → `AA:BB:CC:…` grouping shown in the dashboard and startup log. */
    fun groupedFingerprint(hex: String): String =
        hex.chunked(2).joinToString(":").uppercase()
}
