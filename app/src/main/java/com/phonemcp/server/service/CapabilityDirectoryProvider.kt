package com.phonemcp.server.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.phonemcp.server.di.CapabilityDirectoryEntryPoint
import com.phonemcp.server.mcp.CapabilityIpcContract
import com.phonemcp.server.mcp.ExternalCapabilityRegistry
import dagger.hilt.android.EntryPointAccessors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Public on-device directory of registered third-party capabilities.
 *
 * Read-only listing for any on-device app (`query()` needs no permission —
 * capability metadata is public by design). Mutations go through `call()`
 * and are keyed by Binder-verified [getCallingPackage], so a provider can
 * only ever register, heartbeat, or remove its own record:
 *
 * - `call("register", null, {descriptorJson})` → `{ok, tools[]}`
 * - `call("unregister", capabilityId, null)` → `{ok}`
 * - `call("heartbeat", capabilityId, null)` → `{ok}`
 *
 * Never carries tokens or argument values into the dashboard log.
 *
 * Note: resolved via [CapabilityDirectoryEntryPoint] (lazily per call) —
 * `@AndroidEntryPoint` does not support ContentProviders, and the provider
 * starts before `Application.onCreate`, so injection must wait until a
 * client actually calls in.
 */
class CapabilityDirectoryProvider : ContentProvider() {

    /** Singleton-component access; only valid once the app process is up (i.e. inside query()/call()). */
    private fun entryPoint(): CapabilityDirectoryEntryPoint {
        val appContext = context?.applicationContext
            ?: throw IllegalStateException("CapabilityDirectoryProvider has no context")
        return EntryPointAccessors.fromApplication(appContext, CapabilityDirectoryEntryPoint::class.java)
    }

    private fun registry(): ExternalCapabilityRegistry = entryPoint().registry()

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        return when (matcher.match(uri)) {
            MATCH_CAPABILITIES -> MatrixCursor(CAPABILITY_COLUMNS).also { cursor ->
                registry().listCapabilities().forEach { capability ->
                    cursor.addRow(
                        arrayOf<Any?>(
                            capability.id,
                            capability.packageName,
                            capability.displayName,
                            capability.version,
                            capability.tools.map {
                                CapabilityIpcContract.namespacedName(capability.id, it.name)
                            }.joinToString(","),
                            capability.signerSha256,
                        ),
                    )
                }
            }
            MATCH_TOOLS -> MatrixCursor(TOOL_COLUMNS).also { cursor ->
                registry().listTools().forEach { tool ->
                    val parsed = CapabilityIpcContract.parseNamespaced(tool.name)
                    cursor.addRow(arrayOf<Any?>(tool.name, parsed?.first.orEmpty(), tool.description))
                }
            }
            else -> null
        }
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        MATCH_CAPABILITIES -> "vnd.android.cursor.dir/vnd.phonemcp.capability"
        MATCH_TOOLS -> "vnd.android.cursor.dir/vnd.phonemcp.capability.tool"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        // Binder-verified identity (API 19+; minSdk 29). Never trust extras for this.
        val caller = try {
            callingPackage
        } catch (_: Exception) {
            null
        }.orEmpty()
        if (caller.isEmpty()) {
            return Bundle().apply {
                putBoolean(CapabilityIpcContract.KEY_OK, false)
                putString(CapabilityIpcContract.KEY_ERROR, "unknown caller")
            }
        }
        return when (method) {
            CapabilityIpcContract.CALL_REGISTER -> {
                val raw = extras?.getString(CapabilityIpcContract.KEY_DESCRIPTOR_JSON).orEmpty()
                val descriptor = try {
                    Json.parseToJsonElement(raw) as? JsonObject
                } catch (_: Exception) {
                    null
                } ?: return Bundle().apply {
                    putBoolean(CapabilityIpcContract.KEY_OK, false)
                    putString(CapabilityIpcContract.KEY_ERROR, "descriptorJson must be a JSON object")
                }
                when (val outcome = registry().register(caller, descriptor)) {
                    is ExternalCapabilityRegistry.RegisterOutcome.Ok -> {
                        entryPoint().log().append("capability registered ${descriptorDescriptorId(descriptor)} ($caller)")
                        Bundle().apply {
                            putBoolean(CapabilityIpcContract.KEY_OK, true)
                            putString(CapabilityIpcContract.KEY_RESULT_TEXT, outcome.toolNames.joinToString(","))
                        }
                    }
                    is ExternalCapabilityRegistry.RegisterOutcome.Rejected -> Bundle().apply {
                        putBoolean(CapabilityIpcContract.KEY_OK, false)
                        putString(CapabilityIpcContract.KEY_ERROR, outcome.errors.joinToString("; "))
                    }
                }
            }
            CapabilityIpcContract.CALL_UNREGISTER -> {
                val id = arg.orEmpty()
                val removed = registry().unregister(id, requesterPackage = caller)
                if (removed) entryPoint().log().append("capability unregistered $id ($caller)")
                Bundle().apply { putBoolean(CapabilityIpcContract.KEY_OK, removed) }
            }
            CapabilityIpcContract.CALL_HEARTBEAT -> {
                val alive = registry().heartbeat(arg.orEmpty(), requesterPackage = caller)
                Bundle().apply { putBoolean(CapabilityIpcContract.KEY_OK, alive) }
            }
            else -> null
        }
    }

    private fun descriptorDescriptorId(descriptor: JsonObject): String =
        CapabilityIpcContract.stringField(descriptor, "capabilityId", "?")

    companion object {
        private const val MATCH_CAPABILITIES = 1
        private const val MATCH_TOOLS = 2

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(CapabilityIpcContract.AUTHORITY, CapabilityIpcContract.PATH_CAPABILITIES, MATCH_CAPABILITIES)
            addURI(CapabilityIpcContract.AUTHORITY, CapabilityIpcContract.PATH_TOOLS, MATCH_TOOLS)
        }

        private val CAPABILITY_COLUMNS = arrayOf(
            "capabilityId", "packageName", "displayName", "version", "tools", "signerSha256",
        )
        private val TOOL_COLUMNS = arrayOf("name", "capabilityId", "description")
    }
}
