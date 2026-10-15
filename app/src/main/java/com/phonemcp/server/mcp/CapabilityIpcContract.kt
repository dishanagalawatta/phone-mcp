package com.phonemcp.server.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared local-IPC contract for dynamic third-party capabilities.
 *
 * Pure Kotlin (no android.*): both Phone MCP and provider apps (plus JVM
 * unit tests) agree on action/authority names, tool namespacing, Messenger
 * codes, ContentProvider paths, and descriptor validation from this file.
 *
 * Transports (all localhost-only, same device):
 * - **Bound service** (`ACTION_PROVIDE`): Phone MCP binds the provider app's
 *   service and exchanges JSON over [Messenger]-style messages
 *   ([MSG_DESCRIBE]/[MSG_INVOKE]/[MSG_RESULT]). Primary invocation path.
 * - **Directory provider** (`AUTHORITY`): Phone MCP's own exported
 *   ContentProvider lists registered capabilities (`PATH_CAPABILITIES`,
 *   `PATH_TOOLS`) and accepts `call()` register/unregister/heartbeat from
 *   any on-device app. Mutations are keyed by Binder [callingPackage], so a
 *   provider can only ever touch its own record.
 * - **Announce broadcast** (`ACTION_ANNOUNCE`): untrusted rescan hint
 *   carrying only [EXTRA_PACKAGE]. The receiver re-fetches the authoritative
 *   descriptor from that package's bound service — never trusts extras.
 *
 * Manifest literals (`AndroidManifest.xml` `<queries>` block, receiver and
 * provider declarations) MUST match the string constants below.
 */
object CapabilityIpcContract {
    /** Authority of Phone MCP's capability directory provider. */
    const val AUTHORITY = "com.phonemcp.server.capabilities"

    /** Action a provider app's invocation service must declare. */
    const val ACTION_PROVIDE = "com.phonemcp.server.action.PROVIDE_CAPABILITY"

    /** Rescan-hint broadcast sent by provider apps after install/update. */
    const val ACTION_ANNOUNCE = "com.phonemcp.server.action.CAPABILITY_ANNOUNCED"

    /** Marker permission (normal level) identifying capability providers. */
    const val PERMISSION_REGISTER = "com.phonemcp.server.permission.REGISTER_CAPABILITY"

    /** Intent extra: provider package name (hint only — always re-verified). */
    const val EXTRA_PACKAGE = "com.phonemcp.server.extra.PACKAGE"

    // Bound-service message codes.
    const val MSG_DESCRIBE = 1
    const val MSG_INVOKE = 2
    const val MSG_RESULT = 3

    // Bound-service Bundle keys.
    const val KEY_TOOL = "tool"
    const val KEY_ARGS_JSON = "argsJson"
    const val KEY_DESCRIPTOR_JSON = "descriptorJson"
    const val KEY_OK = "ok"
    const val KEY_RESULT_TEXT = "resultText"
    const val KEY_ERROR = "error"

    // Directory provider paths and call() methods.
    const val PATH_CAPABILITIES = "capabilities"
    const val PATH_TOOLS = "tools"
    const val CALL_REGISTER = "register"
    const val CALL_UNREGISTER = "unregister"
    const val CALL_HEARTBEAT = "heartbeat"

    // Registry limits (DoS bounds for third-party metadata).
    const val MAX_CAPABILITIES = 32
    const val MAX_TOOLS_PER_CAPABILITY = 16
    const val MAX_DISPLAY_NAME_CHARS = 64
    const val MAX_VERSION_CHARS = 32
    const val MAX_DESCRIPTION_CHARS = 2000
    const val MAX_SCHEMA_BYTES = 8192

    /** Prefix for every externally provided tool: `ext_<capId>__<tool>`. */
    const val PREFIX = "ext_"
    const val SEPARATOR = "__"

    /** Provider capability id: lowercase alnum, 1..20 chars. */
    val CAPABILITY_ID: Regex = Regex("[a-z0-9]{1,20}")

    /** Bare tool name inside a capability: 1..30 chars, must start lowercase. */
    val TOOL_NAME: Regex = Regex("[a-z][a-z0-9_]{0,29}")

    /** Full MCP-visible name (<= 64 chars, MCP tool-name charset). */
    val FULL_NAME: Regex = Regex("ext_[a-z0-9]{1,20}__[a-z][a-z0-9_]{0,29}")

    fun namespacedName(capabilityId: String, tool: String): String =
        "$PREFIX$capabilityId$SEPARATOR$tool"

    /** Splits `ext_<capId>__<tool>` into (capId, tool), or null when malformed. */
    fun parseNamespaced(fullName: String): Pair<String, String>? {
        if (!FULL_NAME.matches(fullName)) return null
        val rest = fullName.removePrefix(PREFIX)
        val sep = rest.indexOf(SEPARATOR)
        if (sep < 0) return null
        return rest.substring(0, sep) to rest.substring(sep + SEPARATOR.length)
    }

    /**
     * Validates a capability descriptor object:
     * `{capabilityId, displayName, version, tools:[{name, description, inputSchema}]}`.
     * Returns human-readable errors; empty means valid. Pure; JVM-testable.
     */
    fun validateDescriptor(descriptor: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        val id = (descriptor["capabilityId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (id == null || !CAPABILITY_ID.matches(id)) {
            errors += "capabilityId must match [a-z0-9]{1,20}"
        }
        val display = (descriptor["displayName"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (display.isNullOrBlank() || display.length > MAX_DISPLAY_NAME_CHARS) {
            errors += "displayName must be 1..$MAX_DISPLAY_NAME_CHARS chars"
        }
        val version = (descriptor["version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version.isNullOrBlank() || version.length > MAX_VERSION_CHARS) {
            errors += "version must be 1..$MAX_VERSION_CHARS chars"
        }
        val tools = descriptor["tools"] as? JsonArray
        if (tools == null || tools.isEmpty() || tools.size > MAX_TOOLS_PER_CAPABILITY) {
            errors += "tools must list 1..$MAX_TOOLS_PER_CAPABILITY entries"
            return errors
        }
        val seen = mutableSetOf<String>()
        tools.forEachIndexed { index, element ->
            val tool = element as? JsonObject
            if (tool == null) {
                errors += "tools[$index] must be an object"
                return@forEachIndexed
            }
            val name = (tool["name"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (name == null || !TOOL_NAME.matches(name)) {
                errors += "tools[$index].name must match [a-z][a-z0-9_]{0,29}"
            } else if (!seen.add(name)) {
                errors += "tools[$index].name duplicates '$name'"
            } else if (id != null && !FULL_NAME.matches(namespacedName(id, name))) {
                errors += "tools[$index].name overflows the 64-char namespaced limit with '$id'"
            }
            val description = (tool["description"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (description.isNullOrBlank() || description.length > MAX_DESCRIPTION_CHARS) {
                errors += "tools[$index].description must be 1..$MAX_DESCRIPTION_CHARS chars"
            }
            val schema = tool["inputSchema"] as? JsonObject
            val schemaType = (schema?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (schema == null || schemaType != "object") {
                errors += "tools[$index].inputSchema must be an object with \"type\":\"object\""
            } else if (schema.toString().length > MAX_SCHEMA_BYTES) {
                errors += "tools[$index].inputSchema exceeds $MAX_SCHEMA_BYTES bytes"
            }
        }
        return errors
    }

    /** Parses a validated descriptor into spec triples (name, description, schema). */
    fun parseTools(descriptor: JsonObject): List<Triple<String, String, JsonObject>> =
        (descriptor["tools"] as JsonArray).map { element ->
            val tool = element as JsonObject
            val name = (tool["name"] as JsonPrimitive).content
            val description = (tool["description"] as JsonPrimitive).content
            Triple(name, description, tool["inputSchema"] as JsonObject)
        }

    fun stringField(descriptor: JsonObject, key: String, fallback: String = ""): String =
        (descriptor[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: fallback
}
