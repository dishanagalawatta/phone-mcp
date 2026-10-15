package com.phonemcp.server.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.phonemcp.server.domain.ExternalToolInvoker
import com.phonemcp.server.mcp.CapabilityIpcContract
import com.phonemcp.server.mcp.ExternalCapability
import com.phonemcp.server.mcp.ToolCallResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Invokes provider-app tools over an explicit bound-service Messenger channel.
 *
 * Protocol (see [CapabilityIpcContract]): Phone MCP binds the provider's
 * [CapabilityIpcContract.ACTION_PROVIDE] service (explicit package — no
 * implicit-bind resolution attacks), sends [CapabilityIpcContract.MSG_DESCRIBE]
 * or [CapabilityIpcContract.MSG_INVOKE] with a `replyTo` Messenger, and awaits
 * [CapabilityIpcContract.MSG_RESULT]. Every hop is bounded; Binder death or a
 * missing service surfaces as `isError`, never a crash.
 */
@Singleton
class ServiceToolInvoker @Inject constructor(
    @ApplicationContext private val context: Context,
) : ExternalToolInvoker {
    override suspend fun invoke(capability: ExternalCapability, tool: String, args: JsonObject): ToolCallResult {
        val payload = Bundle().apply {
            putString(CapabilityIpcContract.KEY_TOOL, tool)
            putString(CapabilityIpcContract.KEY_ARGS_JSON, args.toString())
        }
        val reply = transact(
            packageName = capability.packageName,
            what = CapabilityIpcContract.MSG_INVOKE,
            payload = payload,
            timeoutMs = INVOKE_TIMEOUT_MS,
        ) ?: return ToolCallResult.error("Provider ${capability.packageName} unreachable (bind/invoke timeout)")
        if (!reply.getBoolean(CapabilityIpcContract.KEY_OK, false)) {
            return ToolCallResult.error(
                reply.getString(CapabilityIpcContract.KEY_ERROR) ?: "Provider reported failure",
            )
        }
        return ToolCallResult.text(reply.getString(CapabilityIpcContract.KEY_RESULT_TEXT).orEmpty())
    }

    /** Fetches the authoritative descriptor from the provider app itself. Null when unreachable. */
    suspend fun fetchDescriptor(packageName: String): JsonObject? {
        val reply = transact(
            packageName = packageName,
            what = CapabilityIpcContract.MSG_DESCRIBE,
            payload = Bundle(),
            timeoutMs = DESCRIBE_TIMEOUT_MS,
        ) ?: return null
        if (!reply.getBoolean(CapabilityIpcContract.KEY_OK, false)) return null
        val raw = reply.getString(CapabilityIpcContract.KEY_DESCRIPTOR_JSON) ?: return null
        return try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun transact(packageName: String, what: Int, payload: Bundle, timeoutMs: Long): Bundle? {
        val intent = Intent(CapabilityIpcContract.ACTION_PROVIDE).setPackage(packageName)
        // Fail closed when the target exposes no such service (typo, uninstalled, non-provider).
        val resolves = try {
            context.packageManager.queryIntentServices(intent, 0)
        } catch (_: Exception) {
            return null
        }
        if (resolves.isEmpty()) return null
        val replyBox = CompletableDeferred<Bundle?>()
        val client = try {
            Messenger(
                Handler(Looper.getMainLooper()) { message ->
                    if (message.what == CapabilityIpcContract.MSG_RESULT) {
                        replyBox.complete(message.data)
                    } else {
                        replyBox.complete(null)
                    }
                    true
                },
            )
        } catch (_: Exception) {
            return null
        }
        var porter: Messenger? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                porter = service?.let { Messenger(it) }
                try {
                    porter?.send(
                        Message.obtain(null, what).apply {
                            data = payload
                            replyTo = client
                        },
                    )
                } catch (_: Exception) {
                    replyBox.complete(null)
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                porter = null
            }
        }
        val bound = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) {
            false
        }
        if (!bound) return null
        return try {
            withTimeoutOrNull(timeoutMs) { replyBox.await() }
        } finally {
            try {
                context.unbindService(connection)
            } catch (_: Exception) {
                // Already unbound or connection dead; nothing to release.
            }
        }
    }

    companion object {
        /** Bound for one proxied invocation (bind + execute + reply). */
        const val INVOKE_TIMEOUT_MS = 20_000L

        /** Bound for a descriptor fetch during rescan. */
        const val DESCRIBE_TIMEOUT_MS = 8_000L

        /**
         * SHA-256 hex of the package's first signing certificate (audit trail
         * stored on the capability record). Null when the package is unknown.
         * Pure lookup; JVM-untestable (needs PackageManager) by design.
         */
        fun signingSha256(packageManager: PackageManager, packageName: String): String? {
            return try {
                val signatures = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    val info = packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_SIGNING_CERTIFICATES,
                    )
                    val signing = info.signingInfo ?: return null
                    if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)?.signatures
                } ?: return null
                val first = signatures.firstOrNull() ?: return null
                MessageDigest.getInstance("SHA-256").digest(first.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            } catch (_: Exception) {
                null
            }
        }
    }
}
