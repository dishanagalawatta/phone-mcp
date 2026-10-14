package com.phonemcp.server.data

import android.content.Context
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** GPS fix via FusedLocationProvider. Needs location permission + location services on. */
@Singleton
class LocationProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {
    override val id = "location"
    override fun listTools() = listOf(
        ToolDefinition(
            "location_read",
            "Current GPS fix (fresh when possible, last-known fallback). " +
                "Args: highAccuracy (default true), timeoutMs (default 8000, clamped 1000..30000).",
            objectSchema(
                buildJsonObject {
                    put("highAccuracy", prop("boolean", "Use GPS-grade fix instead of balanced power"))
                    put("timeoutMs", prop("integer", "Fresh-fix wait cap in ms"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        require(name == "location_read") { "unknown tool $name" }
        val request = parseArgs(args)
        val client = LocationServices.getFusedLocationProviderClient(context)
        return try {
            val priority = if (request.highAccuracy) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val source = CancellationTokenSource()
            val current = try {
                withTimeoutOrNull(request.timeoutMs) {
                    client.getCurrentLocation(priority, source.token).await()
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                source.cancel()
            }
            var fix = current
            var origin = "current"
            if (fix == null) {
                fix = try {
                    client.lastLocation.await()
                } catch (e: Exception) {
                    null
                }
                origin = "last"
            }
            if (fix == null) {
                ToolCallResult.error("No location fix (enable location services and grant permission)")
            } else {
                ToolCallResult.text(
                    buildJsonObject {
                        put("latitude", fix.latitude)
                        put("longitude", fix.longitude)
                        put("accuracyM", fix.accuracy)
                        put("timeMs", fix.time)
                        put("source", origin)
                    }.toString(),
                )
            }
        } catch (e: SecurityException) {
            ToolCallResult.error("Location permission missing")
        }
    }

    companion object {
        data class LocationRequest(val highAccuracy: Boolean, val timeoutMs: Long)

        /** Pure arg parsing; JVM-test safe. */
        fun parseArgs(args: JsonObject): LocationRequest {
            val timeoutMs = ArgParser.long(args, "timeoutMs", 8000L, 1000L..30_000L)
            val highAccuracy = ArgParser.boolean(args, "highAccuracy", true)
            return LocationRequest(highAccuracy, timeoutMs)
        }
    }
}
