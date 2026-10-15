package com.phonemcp.server.data

import com.phonemcp.server.domain.LogBuffer
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.CapabilityIpcContract
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Management plane for dynamic third-party capabilities.
 *
 * These `capabilities_*` tools are always ungated (they only describe the
 * registry — invocation of `ext_*` tools stays behind the `ext_` kill-switch
 * in [com.phonemcp.server.mcp.McpRouter]). On-device apps prefer the local
 * IPC transports (bound service, directory provider, announce broadcast);
 * remote agents use these tools to inspect, provision, and rescan.
 */
@Singleton
class ExternalCapabilityProvider @Inject constructor(
    private val registry: ExternalCapabilityRegistry,
    private val discovery: ExternalCapabilityDiscovery,
    private val log: LogBuffer,
) : ToolProvider {
    override val id = "external"

    override fun listTools() = listOf(
        ToolDefinition(
            "capabilities_list",
            "List third-party app capabilities registered on this phone (id, package, tools, version).",
            emptySchema(),
        ),
        ToolDefinition(
            "capabilities_register",
            "Register a capability for an installed provider app (must expose the PROVIDE_CAPABILITY service). " +
                "Args: capabilityId (required), packageName (required), displayName, version, tools[] (required).",
            objectSchema(
                buildJsonObject {
                    put("capabilityId", prop("string", "Capability id [a-z0-9]{1,20}"))
                    put("packageName", prop("string", "Owning app package (must be installed)"))
                    put("displayName", prop("string", "Human-readable name"))
                    put("version", prop("string", "Capability version"))
                    put("tools", prop("array", "Tool descriptors [{name, description, inputSchema}]"))
                },
            ),
        ),
        ToolDefinition(
            "capabilities_unregister",
            "Remove a capability by id. Args: capabilityId (required).",
            objectSchema(
                buildJsonObject {
                    put("capabilityId", prop("string", "Capability id to remove"))
                },
            ),
        ),
        ToolDefinition(
            "capabilities_rescan",
            "Re-query installed provider apps for their authoritative descriptors. " +
                "Args: packageName (optional — rescan one app, default all).",
            objectSchema(
                buildJsonObject {
                    put("packageName", prop("string", "Single package to rescan"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        when (name) {
            "capabilities_list" -> {
                val capabilities = registry.listCapabilities()
                return ToolCallResult.text(
                    buildJsonObject {
                        put("capabilitiesVersion", registry.version.value)
                        put(
                            "capabilities",
                            JsonArray(
                                capabilities.map { capability ->
                                    buildJsonObject {
                                        put("capabilityId", capability.id)
                                        put("packageName", capability.packageName)
                                        put("displayName", capability.displayName)
                                        put("version", capability.version)
                                        put(
                                            "tools",
                                            JsonArray(
                                                capability.tools.map {
                                                    JsonPrimitive(
                                                        CapabilityIpcContract.namespacedName(capability.id, it.name),
                                                    )
                                                },
                                            ),
                                        )
                                        if (capability.signerSha256.isNotEmpty()) {
                                            put("signerSha256", capability.signerSha256.take(16))
                                        }
                                    }
                                },
                            ),
                        )
                    }.toString(),
                )
            }
            "capabilities_register" -> {
                val capabilityId = ArgParser.string(args, "capabilityId", "")
                require(capabilityId.isNotBlank()) { "capabilityId is required" }
                val packageName = ArgParser.string(args, "packageName", "")
                require(packageName.isNotBlank()) { "packageName is required" }
                val tools = args["tools"] as? JsonArray
                    ?: throw IllegalArgumentException("tools[] is required")
                val descriptor = buildJsonObject {
                    put("capabilityId", capabilityId)
                    put("displayName", ArgParser.string(args, "displayName", capabilityId))
                    put("version", ArgParser.string(args, "version", "0"))
                    put("tools", tools)
                }
                return when (val outcome = registry.register(packageName, descriptor)) {
                    is ExternalCapabilityRegistry.RegisterOutcome.Ok -> {
                        log.append("capability registered $capabilityId ($packageName, ${outcome.toolNames.size} tools)")
                        ToolCallResult.text(
                            buildJsonObject {
                                put("capabilityId", capabilityId)
                                put("tools", JsonArray(outcome.toolNames.map { JsonPrimitive(it) }))
                            }.toString(),
                        )
                    }
                    is ExternalCapabilityRegistry.RegisterOutcome.Rejected -> {
                        ToolCallResult.error("Registration rejected: ${outcome.errors.joinToString("; ")}")
                    }
                }
            }
            "capabilities_unregister" -> {
                val capabilityId = ArgParser.string(args, "capabilityId", "")
                require(capabilityId.isNotBlank()) { "capabilityId is required" }
                val removed = registry.unregister(capabilityId, forceLocal = true)
                if (!removed) return ToolCallResult.error("Unknown capability: $capabilityId")
                log.append("capability unregistered $capabilityId")
                return ToolCallResult.text("unregistered $capabilityId")
            }
            "capabilities_rescan" -> {
                val packageName = ArgParser.string(args, "packageName", "").ifBlank { null }
                val found = discovery.rescan(packageName)
                log.append("capability rescan ${packageName ?: "all"} → $found registered")
                return ToolCallResult.text(
                    buildJsonObject {
                        put("registered", found)
                        put("capabilitiesVersion", registry.version.value)
                    }.toString(),
                )
            }
        }
        throw IllegalArgumentException("unknown tool $name")
    }
}
