package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Single MCP tool descriptor. inputSchema is a JSON-Schema object. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

fun emptySchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject { })
}

/** Object schema with typed properties, e.g. `objectSchema(buildJsonObject { put("x", type("integer")) })`. */
fun objectSchema(properties: JsonObject, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "object")
        put("properties", properties)
        if (required.isNotEmpty()) {
            put("required", JsonArray(required.map { JsonPrimitive(it) }))
        }
    }

/** Shorthand for a `{"type": ..., "description": ...}` property node. */
fun prop(type: String, description: String): JsonObject = buildJsonObject {
    put("type", type)
    put("description", description)
}
