package com.phonemcp.server.data

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.phonemcp.server.domain.ToolProvider
import com.phonemcp.server.mcp.ToolCallResult
import com.phonemcp.server.mcp.ArgParser
import com.phonemcp.server.mcp.ToolDefinition
import com.phonemcp.server.mcp.emptySchema
import com.phonemcp.server.mcp.objectSchema
import com.phonemcp.server.mcp.prop
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** BLE scan/connect/GATT (connectedDevice FGS), NFC tap intake (FG-only), battery (no perm). */
@Singleton
class RadioProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nfc: NfcReader,
    private val gatt: BleGatt,
) : ToolProvider {
    override val id = "radio"
    override fun listTools() = listOf(
        ToolDefinition(
            "ble_scan",
            "Low-latency BLE scan, returns unique devices seen. Args: durationMs (default 10000, clamped 2000..30000).",
            objectSchema(
                buildJsonObject {
                    put("durationMs", prop("integer", "Scan window in ms"))
                },
            ),
        ),
        ToolDefinition(
            "ble_connect",
            "GATT connect + service discovery. Returns service/characteristic UUID table. Args: address (required).",
            objectSchema(buildJsonObject { put("address", prop("string", "BLE MAC address")) }),
        ),
        ToolDefinition(
            "ble_read",
            "Read a GATT characteristic. Args: address, serviceUuid, charUuid (all required).",
            objectSchema(
                buildJsonObject {
                    put("address", prop("string", "BLE MAC address"))
                    put("serviceUuid", prop("string", "Service UUID"))
                    put("charUuid", prop("string", "Characteristic UUID"))
                },
            ),
        ),
        ToolDefinition(
            "ble_write",
            "Write a GATT characteristic (actuates real hardware — gated by the BLE-write kill-switch). " +
                "Args: address, serviceUuid, charUuid (required), hex (required even-length hex), withResponse (default true).",
            objectSchema(
                buildJsonObject {
                    put("address", prop("string", "BLE MAC address"))
                    put("serviceUuid", prop("string", "Service UUID"))
                    put("charUuid", prop("string", "Characteristic UUID"))
                    put("hex", prop("string", "Bytes as even-length hex"))
                    put("withResponse", prop("boolean", "Write with response"))
                },
            ),
        ),
        ToolDefinition(
            "ble_disconnect",
            "Drop a GATT connection. Args: address (required).",
            objectSchema(buildJsonObject { put("address", prop("string", "BLE MAC address")) }),
        ),
        ToolDefinition(
            "ble_subscribe",
            "Collect notification events from a characteristic for a window. Passive read-only. " +
                "Args: address, serviceUuid, charUuid (required), durationMs (default 10000, clamped 2000..60000).",
            objectSchema(
                buildJsonObject {
                    put("address", prop("string", "BLE MAC address"))
                    put("serviceUuid", prop("string", "Service UUID"))
                    put("charUuid", prop("string", "Characteristic UUID"))
                    put("durationMs", prop("integer", "Collection window in ms"))
                },
            ),
        ),
        ToolDefinition(
            "nfc_status",
            "NFC adapter presence/enabled plus tap-intake state (waiting readers, last tap age).",
            emptySchema(),
        ),
        ToolDefinition(
            "nfc_read",
            "Wait for an NFC tap (agent asks the user to tap, then calls). Returns tag id/techs/NDEF, " +
                "or waiting_for_tap when nothing arrives in time. Args: timeoutMs (default 10000, clamped 1000..60000).",
            objectSchema(
                buildJsonObject {
                    put("timeoutMs", prop("integer", "How long to wait for a tap in ms"))
                },
            ),
        ),
        ToolDefinition("battery_read", "Battery level, charging state, temperature (no permission)", emptySchema()),
    )

    override suspend fun call(name: String, args: JsonObject): ToolCallResult {
        when (name) {
            "battery_read" -> {
                val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    ?: return ToolCallResult.error("Battery status unavailable")
                return ToolCallResult.text(
                    batteryPayload(
                        level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                        scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100),
                        status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                        plugged = sticky.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
                        temperatureTenths = sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE),
                    ).toString(),
                )
            }
            "ble_scan" -> return bleScan(parseScanArgs(args))
            "ble_connect" -> {
                val address = parseAddress(args)
                return try {
                    val services = gatt.connect(address)
                    ToolCallResult.text(
                        buildJsonObject {
                            put("connected", true)
                            put("address", address)
                            put(
                                "services",
                                JsonArray(
                                    services.map { service ->
                                        buildJsonObject {
                                            put("uuid", service.uuid)
                                            put(
                                                "characteristics",
                                                JsonArray(service.characteristics.map { JsonPrimitive(it) }),
                                            )
                                        }
                                    },
                                ),
                            )
                        }.toString(),
                    )
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: SecurityException) {
                    ToolCallResult.error("BLE permission missing (BLUETOOTH_CONNECT)")
                } catch (e: Exception) {
                    ToolCallResult.error("BLE connect failed: ${e.message}")
                }
            }
            "ble_read" -> {
                val target = parseGattTarget(args)
                return try {
                    val bytes = gatt.read(target.address, target.service, target.char)
                    ToolCallResult.text(bytePayload(bytes).toString())
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: SecurityException) {
                    ToolCallResult.error("BLE permission missing (BLUETOOTH_CONNECT)")
                } catch (e: Exception) {
                    ToolCallResult.error("BLE read failed: ${e.message}")
                }
            }
            "ble_write" -> {
                val target = parseGattTarget(args)
                val bytes = parseHexArg(args)
                val withResponse = ArgParser.boolean(args, "withResponse", true)
                return try {
                    gatt.write(target.address, target.service, target.char, bytes, withResponse)
                    ToolCallResult.text("wrote ${bytes.size} bytes")
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: SecurityException) {
                    ToolCallResult.error("BLE permission missing (BLUETOOTH_CONNECT)")
                } catch (e: Exception) {
                    ToolCallResult.error("BLE write failed: ${e.message}")
                }
            }
            "ble_disconnect" -> {
                gatt.disconnect(parseAddress(args))
                return ToolCallResult.text("disconnected")
            }
            "ble_subscribe" -> {
                val target = parseGattTarget(args)
                val durationMs = ArgParser.long(args, "durationMs", 10_000L, 2000L..60_000L)
                return try {
                    val events = gatt.subscribe(target.address, target.service, target.char, durationMs)
                    ToolCallResult.text(
                        buildJsonObject {
                            put("count", events.size)
                            put(
                                "events",
                                JsonArray(
                                    events.map { event ->
                                        buildJsonObject {
                                            put("service", event.service)
                                            put("char", event.char)
                                            put("atMs", event.atMs)
                                            put("data", bytePayload(event.bytes))
                                        }
                                    },
                                ),
                            )
                        }.toString(),
                    )
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: SecurityException) {
                    ToolCallResult.error("BLE permission missing (BLUETOOTH_CONNECT)")
                } catch (e: Exception) {
                    ToolCallResult.error("BLE subscribe failed: ${e.message}")
                }
            }
            "nfc_status" -> {
                if (!nfc.isPresent()) return ToolCallResult.error("NFC unavailable on this device")
                return ToolCallResult.text(
                    buildJsonObject {
                        put("present", true)
                        put("enabled", nfc.isEnabled())
                        put("waitingReaders", nfc.waiterCount())
                        val last = nfc.lastTag(Long.MAX_VALUE)
                        if (last != null) put("lastTapAgeMs", System.currentTimeMillis() - last.atMs)
                    }.toString(),
                )
            }
            "nfc_read" -> {
                if (!nfc.isPresent()) return ToolCallResult.error("NFC unavailable on this device")
                if (!nfc.isEnabled()) {
                    return ToolCallResult.error("NFC is disabled — enable it in Settings, then tap a tag")
                }
                val timeoutMs = ((args["timeoutMs"] as? JsonPrimitive)?.longOrNull ?: 10_000L)
                    .coerceIn(1000, 60_000)
                // A recent tap answers immediately; otherwise park until the next tap or timeout.
                val fresh = nfc.lastTag(timeoutMs)
                val tag = fresh ?: nfc.awaitTag(timeoutMs)
                if (tag == null) {
                    return ToolCallResult.error("waiting_for_tap: no NFC tag tapped within ${timeoutMs}ms")
                }
                return ToolCallResult.text(tagPayload(tag).toString())
            }
        }
        throw IllegalArgumentException("unknown tool $name")
    }

    private suspend fun bleScan(durationMs: Long): ToolCallResult {
        // Needs BLUETOOTH_SCAN (+ CONNECT for names) and the service's connectedDevice FGS for background.
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return ToolCallResult.error("Bluetooth unavailable")
        if (!adapter.isEnabled) return ToolCallResult.error("Bluetooth off")
        val scanner = try {
            adapter.bluetoothLeScanner
        } catch (e: SecurityException) {
            return ToolCallResult.error("BLE permission missing (BLUETOOTH_SCAN)")
        } ?: return ToolCallResult.error("BLE scanner unavailable")
        val seen = ConcurrentHashMap<String, String>()
        val failure = AtomicInteger(-1)
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = try {
                    result.device.address
                } catch (e: SecurityException) {
                    return
                }
                val name = try {
                    result.scanRecord?.deviceName ?: result.device.name
                } catch (e: SecurityException) {
                    null
                }
                seen[address] = "${name ?: "?"} rssi=${result.rssi}"
            }

            override fun onScanFailed(errorCode: Int) {
                failure.set(errorCode)
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, callback)
        } catch (e: SecurityException) {
            return ToolCallResult.error("BLE permission missing (BLUETOOTH_SCAN)")
        }
        try {
            delay(durationMs)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        val failed = failure.get()
        if (failed != -1) return ToolCallResult.error("BLE scan failed (code $failed)")
        val devices = seen.entries.sortedBy { it.key }.take(MAX_DEVICES).map { (address, info) ->
            buildJsonObject {
                put("address", address)
                put("info", info)
            }
        }
        return ToolCallResult.text(
            buildJsonObject {
                put("devices", JsonArray(devices))
                put("count", seen.size)
            }.toString(),
        )
    }

    companion object {
        const val MAX_DEVICES = 100
        /** ATT max payload (512 bytes) as hex chars. Bounds ble_write allocations. */
        const val MAX_HEX_CHARS = 1024
        private val MAC = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        private val HEX = Regex("^[0-9A-Fa-f]*$")

        /** Pure arg parsing; JVM-test safe. */
        fun parseScanArgs(args: JsonObject): Long =
            ArgParser.long(args, "durationMs", 10_000L, 2000L..30_000L)

        /** Pure MAC validation; throws IllegalArgumentException. JVM-test safe. */
        fun parseAddress(args: JsonObject): String {
            val address = (args["address"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(address.matches(MAC)) { "Invalid BLE address (expected AA:BB:CC:DD:EE:FF)" }
            return address.uppercase()
        }

        data class GattTarget(val address: String, val service: UUID, val char: UUID)

        /** Pure target parsing; throws IllegalArgumentException. JVM-test safe. */
        fun parseGattTarget(args: JsonObject): GattTarget {
            val service = try {
                UUID.fromString((args["serviceUuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid serviceUuid")
            }
            val char = try {
                UUID.fromString((args["charUuid"] as? JsonPrimitive)?.takeIf { it.isString }?.content)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid charUuid")
            }
            return GattTarget(parseAddress(args), service, char)
        }

        /** Pure hex parsing (even length, ATT-size capped); throws IllegalArgumentException. JVM-test safe. */
        fun parseHexArg(args: JsonObject): ByteArray {
            val hex = (args["hex"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            require(hex.isNotEmpty() && hex.length % 2 == 0 && hex.matches(HEX)) {
                "Invalid hex (need even-length hex string)"
            }
            // ATT payloads max out at 512 bytes; anything larger is always a mistake or abuse.
            require(hex.length <= MAX_HEX_CHARS) {
                "Hex too long (max $MAX_HEX_CHARS chars = ${MAX_HEX_CHARS / 2} bytes)"
            }
            return ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        }

        /** Read payload: hex always, text when printable UTF-8. Pure; JVM-test safe. */
        fun bytePayload(bytes: ByteArray): JsonObject = buildJsonObject {
            put("hex", bytes.joinToString("") { "%02x".format(it) })
            put("size", bytes.size)
            val text = try {
                String(bytes, Charsets.UTF_8).takeIf { s -> s.all { it == '\n' || it == '\r' || it == '\t' || !it.isISOControl() } }
            } catch (_: Exception) {
                null
            }
            if (text != null) put("text", text.take(1000))
        }

        /** Tag dump payload. Pure; JVM-test safe. */
        fun tagPayload(tag: TagDump): JsonObject = buildJsonObject {
            put("id", tag.idHex)
            put("techs", JsonArray(tag.techs.map { JsonPrimitive(it) }))
            put("records", JsonArray(tag.records))
            put("atMs", tag.atMs)
        }

        /** Pure payload builder from sticky-broadcast extras; JVM-test safe. */
        fun batteryPayload(level: Int, scale: Int, status: Int, plugged: Int, temperatureTenths: Int): JsonObject {
            val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            return buildJsonObject {
                put("percent", percent)
                put("charging", charging)
                put("full", status == BatteryManager.BATTERY_STATUS_FULL)
                put("pluggedAc", (plugged and BatteryManager.BATTERY_PLUGGED_AC) != 0)
                put("pluggedUsb", (plugged and BatteryManager.BATTERY_PLUGGED_USB) != 0)
                put("pluggedWireless", (plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0)
                if (temperatureTenths != Int.MIN_VALUE) put("temperatureC", temperatureTenths / 10.0)
            }
        }
    }
}
