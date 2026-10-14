package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** MCP tools/call result. Tool-logic failures use isError=true (not JSON-RPC errors). */
data class ToolCallResult(
    val content: List<JsonObject>,
    val isError: Boolean = false,
    val rpcCode: Int? = null,
) {
    companion object {
        fun text(text: String): ToolCallResult =
            ToolCallResult(
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", text)
                    },
                ),
            )

        fun error(message: String, rpcCode: Int? = null): ToolCallResult =
            ToolCallResult(
                content = listOf(
                    buildJsonObject {
                        put("type", "text")
                        put("text", message)
                    },
                ),
                isError = true,
                rpcCode = rpcCode,
            )
    }
}
