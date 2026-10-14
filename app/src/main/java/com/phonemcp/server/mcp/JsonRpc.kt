package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Minimal JSON-RPC 2.0 envelope for MCP Streamable HTTP (stateless, JSON only in v1). */
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val method: String,
    val params: JsonObject? = null,
)

data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

object JsonRpcCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    /** Server-defined: missing or invalid bearer token (always paired with HTTP 401). */
    const val UNAUTHORIZED = -32001
}

/** Shared JSON-RPC error envelope builder (used by router and HTTP transport). */
fun jsonRpcError(id: JsonElement?, code: Int, message: String): JsonObject =
    buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            },
        )
    }
