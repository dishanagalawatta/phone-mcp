package com.phonemcp.server.data

import android.content.Context
import android.os.SystemClock
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.McpRouter
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.service.TlsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Device + server identity: model, API level, app version, protocol, uptime, TLS pin. */
@Singleton
class SystemProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tls: TlsStore,
) : ToolProvider {
    private val startedMs = SystemClock.elapsedRealtime()

    override val id = "system"
    override fun listTools() = listOf(
        ToolDefinition("device_info", "Device model/API, app version, MCP protocol, uptime, TLS fingerprint", emptySchema()),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        require(name == "device_info") { "unknown tool $name" }
        val pm = context.packageManager
        val pkg = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, 0)
        } catch (e: Exception) {
            null
        }
        return ToolCallResult.text(
            buildJsonObject {
                put("model", android.os.Build.MODEL ?: "unknown")
                put("api", android.os.Build.VERSION.SDK_INT)
                put("appVersion", pkg?.versionName ?: "unknown")
                put("protocolVersion", McpRouter.LATEST_VERSION)
                put("uptimeMs", SystemClock.elapsedRealtime() - startedMs)
                val fingerprint = tls.fingerprint()
                if (fingerprint.isNotEmpty()) put("tlsSha256", fingerprint)
            }.toString(),
        )
    }
}
