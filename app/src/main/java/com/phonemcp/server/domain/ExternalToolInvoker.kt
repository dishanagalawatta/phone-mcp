package com.phonemcp.server.domain

import com.phonemcp.server.mcp.ExternalCapability
import com.phonemcp.server.mcp.ToolCallResult
import kotlinx.serialization.json.JsonObject

/**
 * Executes one external tool invocation against its owning app.
 * Implementations live in data/ (bound-service Messenger transport).
 */
interface ExternalToolInvoker {
    suspend fun invoke(capability: ExternalCapability, tool: String, args: JsonObject): ToolCallResult

    companion object {
        /** Fallback used in JVM unit tests: every proxied call reports unreachable. */
        val UNREACHABLE: ExternalToolInvoker = object : ExternalToolInvoker {
            override suspend fun invoke(
                capability: ExternalCapability,
                tool: String,
                args: JsonObject,
            ): ToolCallResult = ToolCallResult.error(
                "Provider ${capability.packageName} unreachable (no IPC transport bound)",
            )
        }
    }
}
