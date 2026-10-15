package com.phonemcp.server.mcp

import com.phonemcp.server.domain.Gatekeeper
import com.phonemcp.server.domain.ToolProvider
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pure-Kotlin MCP dispatcher (no android.* imports).
 * Handles: initialize, tools/list, tools/call, ping, plus notifications (no reply).
 * Returns null for notifications (caller must answer HTTP 202 with empty body).
 */
@Singleton
class McpRouter @Inject constructor(
    private val providers: Set<@JvmSuppressWildcards ToolProvider>,
    private val gatekeeper: Gatekeeper,
    private val externalRegistry: ExternalCapabilityRegistry = ExternalCapabilityRegistry(),
) {
    private val streamBusy = AtomicBoolean(false)

    private val toolIndex: Map<String, ToolProvider> by lazy {
        buildMap {
            for (p in providers) {
                for (t in p.listTools()) {
                    putIfAbsent(t.name, p)
                }
            }
        }
    }

    private val streamIndex: Map<String, ToolProvider> by lazy {
        buildMap {
            for (p in providers) {
                for (t in p.listTools()) {
                    if (p.supportsStreaming(t.name)) putIfAbsent(t.name, p)
                }
            }
        }
    }

    fun listTools(): List<ToolDefinition> =
        providers.flatMap { it.listTools() } + externalRegistry.listTools()

    /** Monotonic counter: bumps on every external capability list change. */
    fun externalVersion(): Long = externalRegistry.version.value

    /**
     * Server-initiated `notifications/tools/list_changed` envelope, exactly
     * per MCP 2025-06-18 (method only — clients re-call `tools/list`).
     * Pure; JVM-testable.
     */
    fun externalChangeNotification(): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "notifications/tools/list_changed")
    }

    /** Hot flow of change notifications for SSE subscribers (skips current version). */
    fun externalChanges(): Flow<JsonObject> =
        externalRegistry.version.drop(1).map { externalChangeNotification() }

    fun isStreamable(name: String): Boolean =
        streamIndex.containsKey(name)

    suspend fun callTool(name: String, args: JsonObject): ToolCallResult {
        if (!gatekeeper.isAllowed(name)) {
            return ToolCallResult.error("Tool disabled by kill-switch: $name")
        }
        val provider = toolIndex[name]
        if (provider != null) {
            return try {
                provider.call(name, args)
            } catch (e: IllegalArgumentException) {
                ToolCallResult.error("Invalid params for $name: ${e.message}", rpcCode = JsonRpcCodes.INVALID_PARAMS)
            } catch (e: Exception) {
                ToolCallResult.error("Internal error in $name: ${e.message}", rpcCode = JsonRpcCodes.INTERNAL_ERROR)
            }
        }
        // Dynamic third-party tools (ext_<cap>__<tool>) resolve outside the
        // static provider index, which is snapshotted at startup.
        if (CapabilityIpcContract.parseNamespaced(name) != null) {
            return externalRegistry.callTool(name, args)
        }
        // MCP spec: unknown tool in tools/call is a -32602 Invalid params error.
        return ToolCallResult.error("Unknown tool: $name", rpcCode = JsonRpcCodes.INVALID_PARAMS)
    }

    fun serverInfo(): JsonObject = buildJsonObject {
        put("name", McpProtocol.SERVER_NAME)
        put("version", McpProtocol.SERVER_VERSION)
        put("protocolVersion", McpProtocol.LATEST_VERSION)
        val extra = externalRegistry.serverInfoExtra()
        put("externalTools", extra["externalTools"] ?: JsonPrimitive(0))
        put("externalCapabilities", extra["externalCapabilities"] ?: JsonPrimitive(0))
        put("capabilitiesVersion", extra["capabilitiesVersion"] ?: JsonPrimitive(0L))
    }

    /**
     * Serves one tools/call as an SSE stream: each provider batch is emitted as a
     * `notifications/sensors` envelope; the returned object is the final result envelope.
     * One stream at a time (second caller gets -32603); client disconnect cancels collection.
     */
    suspend fun handleStream(raw: JsonObject, emit: suspend (JsonObject) -> Unit): JsonObject {
        val id = raw["id"] ?: JsonNull
        val params = raw["params"] as? JsonObject
            ?: return jsonRpcError(id, JsonRpcCodes.INVALID_REQUEST, "Missing params")
        val name = str(params["name"])
            ?: return jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, "Missing 'name'")
        val provider = streamIndex[name]
            ?: return jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, "Tool is not streamable: $name")
        val args = (params["arguments"] as? JsonObject) ?: buildJsonObject { }
        if (name == "sensors_stream") {
            val includeLocation = (args["includeLocation"] as? JsonPrimitive)?.booleanOrNull ?: true
            if (includeLocation && !gatekeeper.isAllowed("location_read")) {
                return jsonRpcError(
                    id,
                    JsonRpcCodes.INVALID_PARAMS,
                    "Location disabled by kill-switch (toggle Location or set includeLocation false)",
                )
            }
        }
        if (!gatekeeper.isAllowed(name)) {
            return jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, "Tool disabled by kill-switch: $name")
        }
        if (!streamBusy.compareAndSet(false, true)) {
            return jsonRpcError(id, JsonRpcCodes.INTERNAL_ERROR, "Another stream is active")
        }
        return try {
            var batches = 0
            try {
                withTimeoutOrNull(STREAM_TIMEOUT_MS) {
                    provider.openStream(name, args).collect { batch ->
                        batches++
                        emit(
                            buildJsonObject {
                                put("jsonrpc", "2.0")
                                put("method", provider.streamNotificationMethod(name))
                                put("params", batch)
                            },
                        )
                    }
                } ?: return jsonRpcError(id, JsonRpcCodes.INTERNAL_ERROR, "Stream timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                return jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, e.message ?: "Invalid stream args")
            } catch (e: Exception) {
                return jsonRpcError(id, JsonRpcCodes.INTERNAL_ERROR, "Stream failed: ${e.message}")
            }
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put(
                    "result",
                    buildJsonObject {
                        put("tool", name)
                        put("batches", batches)
                    },
                )
            }
        } finally {
            streamBusy.set(false)
        }
    }

    suspend fun handle(raw: JsonObject): JsonObject? {
        val id = raw["id"] ?: JsonNull
        val jsonrpc = str(raw["jsonrpc"])
        if (jsonrpc != null && jsonrpc != "2.0") {
            return jsonRpcError(id, JsonRpcCodes.INVALID_REQUEST, "Unsupported jsonrpc version (want \"2.0\")")
        }
        val method = str(raw["method"])
            ?: return jsonRpcError(id, JsonRpcCodes.INVALID_REQUEST, "Missing 'method'")
        if (method.startsWith("notifications/")) return null
        if (!raw.containsKey("id")) {
            return jsonRpcError(JsonNull, JsonRpcCodes.INVALID_REQUEST, "Missing 'id' for '$method'")
        }
        val params = raw["params"] as? JsonObject
        return when (method) {
            "initialize" -> handleInitialize(id, params)
            "tools/list" -> handleList(id)
            "tools/call" -> handleCall(id, params)
            "ping" -> okResponse(id, buildJsonObject { })
            else -> jsonRpcError(id, JsonRpcCodes.METHOD_NOT_FOUND, "Unknown method: $method")
        }
    }

    private fun handleInitialize(id: JsonElement, params: JsonObject?): JsonObject {
        val requested = str(params?.get("protocolVersion"))
        val negotiated = if (requested != null && McpProtocol.SUPPORTED_VERSIONS.contains(requested)) {
            requested
        } else {
            McpProtocol.LATEST_VERSION
        }
        return okResponse(
            id,
            buildJsonObject {
                put("protocolVersion", negotiated)
                put(
                    "capabilities",
                    buildJsonObject {
                        // True: third-party apps can register capabilities at
                        // runtime, so clients SHOULD expect
                        // `notifications/tools/list_changed` and re-call
                        // tools/list (plus `capabilitiesVersion` deltas in
                        // serverInfo) rather than caching the list.
                        put("tools", buildJsonObject { put("listChanged", true) })
                    },
                )
                put(
                    "serverInfo",
                    buildJsonObject {
                        put("name", McpProtocol.SERVER_NAME)
                        put("version", McpProtocol.SERVER_VERSION)
                    },
                )
            },
        )
    }

    private fun handleList(id: JsonElement): JsonObject {
        val tools = JsonArray(
            listTools().map { tool ->
                buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                }
            },
        )
        return okResponse(id, buildJsonObject { put("tools", tools) })
    }

    private suspend fun handleCall(id: JsonElement, params: JsonObject?): JsonObject {
        val name = str(params?.get("name"))
            ?: return jsonRpcError(id, JsonRpcCodes.INVALID_PARAMS, "Missing 'name'")
        val args = (params?.get("arguments") as? JsonObject) ?: buildJsonObject { }
        val result = callTool(name, args)
        if (result.rpcCode != null) {
            val message = result.content.firstOrNull()?.let { str(it["text"]) } ?: "Tool error"
            return jsonRpcError(id, result.rpcCode, message)
        }
        return okResponse(
            id,
            buildJsonObject {
                put("content", JsonArray(result.content))
                put("isError", result.isError)
            },
        )
    }

    private fun okResponse(id: JsonElement, result: JsonElement): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

    companion object {
        /** @deprecated Use [McpProtocol.SUPPORTED_VERSIONS]; kept for tests. */
        val SUPPORTED_VERSIONS: List<String> get() = McpProtocol.SUPPORTED_VERSIONS

        /** @deprecated Use [McpProtocol.LATEST_VERSION]; kept for tests. */
        const val LATEST_VERSION = "2025-06-18"

        /** Upper bound for any stream (provider clamps duration to 60 s; this is the backstop). */
        const val STREAM_TIMEOUT_MS = 75_000L

        internal fun str(element: JsonElement?): String? {
            val prim = element as? JsonPrimitive ?: return null
            return if (prim.isString) prim.content else null
        }
    }
}
