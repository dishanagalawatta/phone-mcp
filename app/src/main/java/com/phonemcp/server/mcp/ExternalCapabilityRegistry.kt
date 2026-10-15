package com.phonemcp.server.mcp

import com.phonemcp.server.domain.ExternalToolInvoker
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One tool contributed by a third-party capability. */
data class ExternalToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

/** One registered third-party capability (one provider app). */
data class ExternalCapability(
    val id: String,
    val packageName: String,
    val displayName: String,
    val version: String,
    val tools: List<ExternalToolSpec>,
    /** SHA-256 of the provider's signing cert (audit trail, may be empty). */
    val signerSha256: String = "",
    val registeredAtMs: Long = 0L,
    val lastHeartbeatMs: Long = 0L,
)

/** An `ext_<cap>__<tool>` name resolved to its owner and spec. */
data class ResolvedExternalTool(
    val capability: ExternalCapability,
    val spec: ExternalToolSpec,
    val fullName: String,
)

/**
 * Dynamic registry for third-party app capabilities.
 *
 * Pure Kotlin (no android.* imports): registrations arrive via local IPC
 * (bound service, directory ContentProvider, announce broadcast) or the
 * `capabilities_*` MCP management tools, and are surfaced to agents as
 * namespaced `ext_<capId>__<tool>` entries in `tools/list`.
 *
 * Freshness: every record carries a heartbeat timestamp; entries older than
 * [HEARTBEAT_TIMEOUT_MS] are evicted on the next read. [version] bumps on
 * every list change so transports can advertise `listChanged` deltas.
 */
@Singleton
class ExternalCapabilityRegistry @Inject constructor(
    private val invoker: ExternalToolInvoker,
) {
    /** Zero-dep constructor for JVM unit tests (invocations report unreachable). */
    constructor() : this(ExternalToolInvoker.UNREACHABLE)

    /** Overridable clock (tests). Never android.*. */
    var clockMs: () -> Long = System::currentTimeMillis

    private val lock = Any()
    private val capabilities = LinkedHashMap<String, ExternalCapability>()

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    private val _snapshot = MutableStateFlow<List<ExternalCapability>>(emptyList())
    val snapshot: StateFlow<List<ExternalCapability>> = _snapshot.asStateFlow()

    sealed interface RegisterOutcome {
        data class Ok(val toolNames: List<String>) : RegisterOutcome
        data class Rejected(val errors: List<String>) : RegisterOutcome
    }

    /**
     * Registers (or replaces, same-package only) a capability descriptor.
     * Returns the MCP-visible namespaced tool names on success.
     */
    fun register(
        packageName: String,
        descriptor: JsonObject,
        signerSha256: String = "",
    ): RegisterOutcome {
        if (packageName.isBlank()) return RegisterOutcome.Rejected(listOf("packageName required"))
        val errors = CapabilityIpcContract.validateDescriptor(descriptor)
        if (errors.isNotEmpty()) return RegisterOutcome.Rejected(errors)
        val now = clockMs()
        synchronized(lock) {
            evictExpiredLocked(now)
            val id = CapabilityIpcContract.stringField(descriptor, "capabilityId")
            val existing = capabilities[id]
            if (existing != null && existing.packageName != packageName) {
                return RegisterOutcome.Rejected(
                    listOf("capability '$id' is owned by ${existing.packageName}"),
                )
            }
            if (existing == null && capabilities.size >= CapabilityIpcContract.MAX_CAPABILITIES) {
                return RegisterOutcome.Rejected(
                    listOf("capability limit reached (${CapabilityIpcContract.MAX_CAPABILITIES})"),
                )
            }
            val tools = CapabilityIpcContract.parseTools(descriptor).map { (name, description, schema) ->
                ExternalToolSpec(name, description, schema)
            }
            capabilities[id] = ExternalCapability(
                id = id,
                packageName = packageName,
                displayName = CapabilityIpcContract.stringField(descriptor, "displayName", id),
                version = CapabilityIpcContract.stringField(descriptor, "version", "0"),
                tools = tools,
                signerSha256 = signerSha256.ifBlank { existing?.signerSha256.orEmpty() },
                registeredAtMs = existing?.registeredAtMs ?: now,
                lastHeartbeatMs = now,
            )
            publishLocked()
            return RegisterOutcome.Ok(tools.map { CapabilityIpcContract.namespacedName(id, it.name) })
        }
    }

    /**
     * Removes a capability. IPC callers pass their Binder-verified package;
     * local management (dashboard, `capabilities_unregister`) passes
     * `forceLocal = true` (already bearer-authenticated).
     */
    fun unregister(id: String, requesterPackage: String? = null, forceLocal: Boolean = false): Boolean {
        synchronized(lock) {
            val existing = capabilities[id] ?: return false
            if (!forceLocal && requesterPackage != null && existing.packageName != requesterPackage) {
                return false
            }
            capabilities.remove(id)
            publishLocked()
            return true
        }
    }

    /** Refreshes liveness without changing the tool list (no version bump). */
    fun heartbeat(id: String, requesterPackage: String? = null): Boolean {
        synchronized(lock) {
            val existing = capabilities[id] ?: return false
            if (requesterPackage != null && existing.packageName != requesterPackage) return false
            if (clockMs() - existing.lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS) {
                capabilities.remove(id)
                publishLocked()
                return false
            }
            capabilities[id] = existing.copy(lastHeartbeatMs = clockMs())
            _snapshot.value = capabilities.values.toList()
            return true
        }
    }

    /** Evicts stale records; returns the removed ids. */
    fun pruneExpired(nowMs: Long = clockMs()): List<String> {
        synchronized(lock) {
            return evictExpiredLocked(nowMs)
        }
    }

    fun listCapabilities(): List<ExternalCapability> {
        synchronized(lock) {
            evictExpiredLocked(clockMs())
            return capabilities.values.toList()
        }
    }

    /** MCP-visible tool definitions (`ext_<cap>__<tool>`, origin-annotated). */
    fun listTools(): List<ToolDefinition> =
        listCapabilities().flatMap { capability ->
            capability.tools.map { spec ->
                ToolDefinition(
                    name = CapabilityIpcContract.namespacedName(capability.id, spec.name),
                    description = "${spec.description} [via ${capability.displayName}]",
                    inputSchema = spec.inputSchema,
                )
            }
        }

    fun resolve(fullName: String): ResolvedExternalTool? {
        val (capId, tool) = CapabilityIpcContract.parseNamespaced(fullName) ?: return null
        synchronized(lock) {
            evictExpiredLocked(clockMs())
            val capability = capabilities[capId] ?: return null
            val spec = capability.tools.firstOrNull { it.name == tool } ?: return null
            return ResolvedExternalTool(capability, spec, fullName)
        }
    }

    /**
     * Proxies one `tools/call` to the owning app (bounded by
     * [EXTERNAL_CALL_TIMEOUT_MS]; untrusted output is wrapped, never thrown).
     */
    suspend fun callTool(name: String, args: JsonObject): ToolCallResult {
        val resolved = resolve(name)
            ?: return ToolCallResult.error(
                "Unknown tool: $name",
                rpcCode = JsonRpcCodes.INVALID_PARAMS,
            )
        return try {
            withTimeoutOrNull(EXTERNAL_CALL_TIMEOUT_MS) {
                invoker.invoke(resolved.capability, resolved.spec.name, args)
            } ?: ToolCallResult.error("External tool timed out: $name")
        } catch (e: IllegalArgumentException) {
            ToolCallResult.error(
                "Invalid params for $name: ${e.message}",
                rpcCode = JsonRpcCodes.INVALID_PARAMS,
            )
        } catch (e: Exception) {
            ToolCallResult.error("External tool failed ($name): ${e.message}")
        }
    }

    fun serverInfoExtra(): JsonObject = buildJsonObject {
        synchronized(lock) {
            put("externalTools", capabilities.values.sumOf { it.tools.size })
            put("externalCapabilities", capabilities.size)
        }
        put("capabilitiesVersion", _version.value)
    }

    private fun evictExpiredLocked(nowMs: Long): List<String> {
        val stale = capabilities.values
            .filter { nowMs - it.lastHeartbeatMs > HEARTBEAT_TIMEOUT_MS }
            .map { it.id }
        if (stale.isNotEmpty()) {
            stale.forEach { capabilities.remove(it) }
            publishLocked()
        }
        return stale
    }

    private fun publishLocked() {
        _snapshot.value = capabilities.values.toList()
        _version.update { it + 1 }
    }

    companion object {
        /** Provider liveness window (heartbeat via ContentProvider `call()`). */
        const val HEARTBEAT_TIMEOUT_MS = 120_000L

        /** Upper bound for one proxied invocation (under the 30 s capture budget). */
        const val EXTERNAL_CALL_TIMEOUT_MS = 25_000L
    }
}
