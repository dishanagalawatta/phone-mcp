package com.phonemcp.server.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Durable form of one [ExternalCapability] for restart persistence.
 * Pure Kotlin (no android.*): the JSON codec is JVM-testable; only the
 * string storage lives in `data/ExternalCapabilityStore`.
 */
data class ExternalCapabilitySnapshot(
    val id: String,
    val packageName: String,
    val displayName: String,
    val version: String,
    /** Raw `tools[]` JSON array (re-validated on import). */
    val toolsJson: String,
    val signerSha256: String = "",
    val approved: Boolean = false,
    val registeredAtMs: Long = 0L,
)

private val SnapshotJson = Json { ignoreUnknownKeys = true }

/** Serializes snapshots for EncryptedSharedPreferences (single string value). */
fun List<ExternalCapabilitySnapshot>.toJsonString(): String =
    SnapshotJson.encodeToString(
        JsonArray.serializer(),
        JsonArray(
            map { snapshot ->
                buildJsonObject {
                    put("id", snapshot.id)
                    put("packageName", snapshot.packageName)
                    put("displayName", snapshot.displayName)
                    put("version", snapshot.version)
                    put("toolsJson", snapshot.toolsJson)
                    put("signerSha256", snapshot.signerSha256)
                    put("approved", snapshot.approved)
                    put("registeredAtMs", snapshot.registeredAtMs)
                }
            },
        ),
    )

/** Parses stored snapshots; corrupt input yields empty (fail open to empty, never crash boot). */
fun snapshotsFromJsonString(raw: String): List<ExternalCapabilitySnapshot> {
    if (raw.isBlank()) return emptyList()
    return try {
        SnapshotJson.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            fun str(key: String): String? =
                (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            val id = str("id") ?: return@mapNotNull null
            val packageName = str("packageName") ?: return@mapNotNull null
            val toolsJson = str("toolsJson") ?: return@mapNotNull null
            ExternalCapabilitySnapshot(
                id = id,
                packageName = packageName,
                displayName = str("displayName") ?: id,
                version = str("version") ?: "0",
                toolsJson = toolsJson,
                signerSha256 = str("signerSha256").orEmpty(),
                approved = (obj["approved"] as? JsonPrimitive)?.booleanOrNull ?: false,
                registeredAtMs = (obj["registeredAtMs"] as? JsonPrimitive)?.longOrNull ?: 0L,
            )
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Exports live records (pruned first) for persistence. */
fun ExternalCapabilityRegistry.exportSnapshots(): List<ExternalCapabilitySnapshot> =
    listCapabilities().map { capability ->
        ExternalCapabilitySnapshot(
            id = capability.id,
            packageName = capability.packageName,
            displayName = capability.displayName,
            version = capability.version,
            toolsJson = JsonArray(
                capability.tools.map { spec ->
                    buildJsonObject {
                        put("name", spec.name)
                        put("description", spec.description)
                        put("inputSchema", spec.inputSchema)
                    }
                },
            ).toString(),
            signerSha256 = capability.signerSha256,
            approved = capability.approved,
            registeredAtMs = capability.registeredAtMs,
        )
    }

/**
 * Imports stored snapshots (re-validated; same-owner approval + signer
 * preserved by [ExternalCapabilityRegistry.register]). Returns the count
 * accepted. Imported records get a fresh heartbeat so providers have the
 * normal TTL window to re-announce.
 */
fun ExternalCapabilityRegistry.importSnapshots(snapshots: List<ExternalCapabilitySnapshot>): Int {
    var accepted = 0
    for (snapshot in snapshots) {
        try {
            val tools = SnapshotJson.parseToJsonElement(snapshot.toolsJson).jsonArray
            val descriptor = buildJsonObject {
                put("capabilityId", snapshot.id)
                put("displayName", snapshot.displayName)
                put("version", snapshot.version)
                put("tools", tools)
            }
            val outcome = register(snapshot.packageName, descriptor, snapshot.signerSha256)
            if (outcome is ExternalCapabilityRegistry.RegisterOutcome.Ok) {
                if (snapshot.approved) setApproved(snapshot.id, true, forceLocal = true)
                accepted++
            }
        } catch (_: Exception) {
            continue
        }
    }
    return accepted
}
