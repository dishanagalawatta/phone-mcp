package com.phonemcp.server.domain

import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

/** One I/O domain (sensors, camera, ...). Implementations live in data/ and touch android.*. */
interface ToolProvider {
    /** Stable domain id, e.g. "sensors", "camera". */
    val id: String
    fun listTools(): List<ToolDefinition>
    suspend fun call(name: String, args: JsonObject): ToolCallResult

    /** True for tools served as SSE streams instead of single JSON (see McpRouter.handleStream). */
    fun supportsStreaming(name: String): Boolean = false

    /** SSE notification method for a stream, e.g. `notifications/sensors`. */
    fun streamNotificationMethod(name: String): String = "notifications/stream"

    /** Cold stream of per-batch payloads; cancelled on client disconnect. Default throws. */
    fun openStream(name: String, args: JsonObject): Flow<JsonObject> =
        flow { throw IllegalArgumentException("not streamable: $name") }
}
