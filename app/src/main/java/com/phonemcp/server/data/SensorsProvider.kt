package com.phonemcp.server.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Snapshot poll of IMU/environmental sensors. No permission needed for this set. */
@Singleton
class SensorsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : ToolProvider {
    override val id = "sensors"

    override fun listTools(): List<ToolDefinition> = listOf(
        ToolDefinition(
            "sensors_read",
            "Single-shot snapshot of IMU/environmental sensors (values + accuracy). " +
                "Args: types[] subset of [accelerometer, gyroscope, magnetic_field, pressure, light, proximity, " +
                "gravity, linear_acceleration, rotation_vector, humidity, ambient_temperature] (default all), " +
                "timeoutMs (default 2000, clamped 100..10000).",
            objectSchema(
                buildJsonObject {
                    put("types", prop("array", "Sensor subset to read; omit for all available"))
                    put("timeoutMs", prop("integer", "Per-call wait cap in ms"))
                },
            ),
        ),
        ToolDefinition(
            "sensors_stream",
            "Live SSE stream of sensor batches + location. Requires Accept: text/event-stream. " +
                "Args: types[] (default IMU subset), intervalMs batch period (default 200, clamped 100..2000), " +
                "durationMs total cap (default 10000, clamped 1000..60000), " +
                "includeLocation (default true; needs Location toggle + permission). " +
                "One stream at a time; client disconnect cancels.",
            objectSchema(
                buildJsonObject {
                    put("types", prop("array", "Sensor subset to stream; omit for default IMU set"))
                    put("intervalMs", prop("integer", "Batch period in ms"))
                    put("durationMs", prop("integer", "Total stream cap in ms"))
                    put("includeLocation", prop("boolean", "Attach 1 Hz GPS fix when available"))
                },
            ),
        ),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        require(name == "sensors_read") {
            if (name == "sensors_stream") {
                "sensors_stream requires SSE (POST /mcp with Accept: text/event-stream)"
            } else {
                "unknown tool $name"
            }
        }
        val request = parseArgs(args) // throws IllegalArgumentException on unknown type
        val manager = context.getSystemService(SensorManager::class.java)
            ?: return ToolCallResult.error("SensorManager unavailable")
        val present = mutableListOf<Pair<Int, Sensor>>()
        val missing = mutableListOf<String>()
        for (type in request.types) {
            val sensor = manager.getDefaultSensor(type)
            if (sensor == null) missing.add(NAME_BY_TYPE[type] ?: type.toString()) else present.add(type to sensor)
        }
        val readings = readOnce(manager, present.map { it.second }, request.timeoutMs)
        val payload = buildJsonObject {
            put(
                "readings",
                JsonArray(
                    readings.map { reading ->
                        buildJsonObject {
                            put("sensor", reading.name)
                            put("values", JsonArray(reading.values.map { JsonPrimitive(it) }))
                            put("accuracy", reading.accuracy)
                        }
                    },
                ),
            )
            val unavailable = missing + readings
                .filter { it.timedOut }
                .map { it.name }
            put("unavailable", JsonArray(unavailable.map { JsonPrimitive(it) }))
        }
        return ToolCallResult.text(payload.toString())
    }

    override fun supportsStreaming(name: String): Boolean = name == "sensors_stream"

    override fun streamNotificationMethod(name: String): String = "notifications/sensors"

    override fun openStream(name: String, args: JsonObject): Flow<JsonObject> = flow {
        require(name == "sensors_stream") { "unknown stream $name" }
        val request = parseStreamArgs(args) // throws IllegalArgumentException on unknown type
        val manager = context.getSystemService(SensorManager::class.java)
            ?: throw IllegalStateException("SensorManager unavailable")
        val missing = mutableListOf<String>()
        val sensors = request.types.mapNotNull { type ->
            manager.getDefaultSensor(type) ?: run {
                missing.add(NAME_BY_TYPE[type] ?: type.toString())
                null
            }
        }
        if (sensors.isEmpty() && !request.includeLocation) {
            throw IllegalStateException("No requested sensors on this device")
        }
        val warnings = mutableListOf<String>()
        if (missing.isNotEmpty()) warnings.add("missing sensors: ${missing.joinToString()}")
        val thread = HandlerThread("mcp-stream").apply { start() }
        val latest = ConcurrentHashMap<Int, Triple<List<Float>, Int, Long>>()
        val latestFix = AtomicReference<Location?>(null)
        var locationCallback: LocationCallback? = null
        try {
            val handler = Handler(thread.looper)
            val listeners = sensors.map { sensor ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        latest[sensor.type] = Triple(event.values.toList(), event.accuracy, event.timestamp)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI, handler)
                listener
            }
            if (request.includeLocation) {
                try {
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    locationCallback = object : LocationCallback() {
                        override fun onLocationResult(result: LocationResult) {
                            latestFix.set(result.lastLocation)
                        }
                    }
                    val locationRequest = LocationRequest.Builder(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                        max(request.intervalMs, 1000L),
                    ).build()
                    client.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
                } catch (e: SecurityException) {
                    warnings.add("location permission missing")
                } catch (e: Exception) {
                    warnings.add("location unavailable: ${e.message}")
                }
            }
            try {
                val deadline = SystemClock.elapsedRealtime() + request.durationMs
                while (SystemClock.elapsedRealtime() < deadline) {
                    delay(request.intervalMs)
                    emit(
                        buildJsonObject {
                            put("t", System.currentTimeMillis())
                            put(
                                "sensors",
                                buildJsonObject {
                                    latest.forEach { (type, reading) ->
                                        put(
                                            NAME_BY_TYPE[type] ?: "type_$type",
                                            JsonArray(reading.first.map { JsonPrimitive(it) }),
                                        )
                                    }
                                },
                            )
                            latestFix.get()?.let { fix ->
                                put(
                                    "location",
                                    buildJsonObject {
                                        put("lat", fix.latitude)
                                        put("lon", fix.longitude)
                                        put("acc", fix.accuracy)
                                    },
                                )
                            }
                            if (warnings.isNotEmpty()) {
                                put("warnings", JsonArray(warnings.map { JsonPrimitive(it) }))
                            }
                        },
                    )
                }
            } finally {
                listeners.forEach(manager::unregisterListener)
                try {
                    locationCallback?.let {
                        LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(it)
                    }
                } catch (_: Exception) {
                }
            }
        } finally {
            thread.quitSafely()
        }
    }

    private data class Reading(val name: String, val values: List<Float>, val accuracy: Int, val timedOut: Boolean)

    private suspend fun readOnce(
        manager: SensorManager,
        sensors: List<Sensor>,
        timeoutMs: Long,
    ): List<Reading> {
        if (sensors.isEmpty()) return emptyList()
        val thread = HandlerThread("mcp-sensors").apply { start() }
        val listeners = mutableListOf<SensorEventListener>()
        try {
            val handler = Handler(thread.looper)
            val latest = ConcurrentHashMap<Sensor, Triple<List<Float>, Int, Boolean>>()
            for (sensor in sensors) {
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        latest[sensor] = Triple(event.values.toList(), event.accuracy, false)
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                listeners.add(listener)
            }
            withTimeoutOrNull(timeoutMs) {
                while (latest.size < sensors.size) delay(POLL_MS)
            }
            return sensors.map { sensor ->
                val name = NAME_BY_TYPE[sensor.type] ?: "type_${sensor.type}"
                val got = latest[sensor]
                if (got != null) {
                    Reading(name, got.first, got.second, timedOut = false)
                } else {
                    Reading(name, emptyList(), 0, timedOut = true)
                }
            }
        } finally {
            listeners.forEach(manager::unregisterListener)
            thread.quitSafely()
        }
    }

    companion object {
        const val POLL_MS = 50L
        /** Name -> Sensor.TYPE_*. Pure map; TYPE_* are compile-time constants (JVM-test safe). */
        val SENSOR_TYPES: Map<String, Int> = mapOf(
            "accelerometer" to Sensor.TYPE_ACCELEROMETER,
            "gyroscope" to Sensor.TYPE_GYROSCOPE,
            "magnetic_field" to Sensor.TYPE_MAGNETIC_FIELD,
            "pressure" to Sensor.TYPE_PRESSURE,
            "light" to Sensor.TYPE_LIGHT,
            "proximity" to Sensor.TYPE_PROXIMITY,
            "gravity" to Sensor.TYPE_GRAVITY,
            "linear_acceleration" to Sensor.TYPE_LINEAR_ACCELERATION,
            "rotation_vector" to Sensor.TYPE_ROTATION_VECTOR,
            "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY,
            "ambient_temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
        )

        val NAME_BY_TYPE: Map<Int, String> = SENSOR_TYPES.entries.associate { (name, type) -> type to name }

        val DEFAULT_TYPES: List<Int> = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_LIGHT,
            Sensor.TYPE_PROXIMITY,
        )

        val DEFAULT_STREAM_TYPES: List<Int> = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE,
            Sensor.TYPE_LIGHT,
        )

        data class SensorReadRequest(val types: List<Int>, val timeoutMs: Long)

        /** Pure arg parsing; throws IllegalArgumentException on unknown sensor names. */
        fun parseArgs(args: JsonObject): SensorReadRequest {
            val timeoutMs = ArgParser.long(args, "timeoutMs", 2000L, 100L..10_000L)
            return SensorReadRequest(resolveTypes(args), timeoutMs)
        }

        data class StreamRequest(
            val types: List<Int>,
            val intervalMs: Long,
            val durationMs: Long,
            val includeLocation: Boolean,
        )

        /** Pure arg parsing; throws IllegalArgumentException on unknown sensor names. */
        fun parseStreamArgs(args: JsonObject): StreamRequest {
            val intervalMs = ArgParser.long(args, "intervalMs", 200L, 100L..2000L)
            val durationMs = ArgParser.long(args, "durationMs", 10_000L, 1000L..60_000L)
            val includeLocation = ArgParser.boolean(args, "includeLocation", true)
            val names = (args["types"] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            val types = if (names.isNullOrEmpty()) {
                DEFAULT_STREAM_TYPES
            } else {
                resolveNames(names)
            }
            return StreamRequest(types, intervalMs, durationMs, includeLocation)
        }

        private fun resolveTypes(args: JsonObject): List<Int> {
            val names = (args["types"] as? JsonArray)?.mapNotNull { element ->
                (element as? JsonPrimitive)?.takeIf { it.isString }?.content
            }
            return if (names.isNullOrEmpty()) DEFAULT_TYPES else resolveNames(names)
        }

        private fun resolveNames(names: List<String>): List<Int> =
            names.map { name ->
                SENSOR_TYPES[name.lowercase()] ?: throw IllegalArgumentException("Unknown sensor: $name")
            }
    }
}
