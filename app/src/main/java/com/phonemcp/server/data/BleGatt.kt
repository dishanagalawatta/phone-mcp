package com.phonemcp.server.data

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * BLE GATT sessions. One cached [BluetoothGatt] per address; all operations on a device
 * are serialized (the stack requires it) and bounded by [OP_TIMEOUT_MS]. A single shared
 * callback completes per-operation deferreds; cancellation and timeouts always clear slots.
 */
@Singleton
class BleGatt @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val mutex = Mutex()
    private val connections = mutableMapOf<String, BluetoothGatt>()
    private var connectSlot: CompletableDeferred<Unit>? = null
    private var discoverSlot: CompletableDeferred<Unit>? = null
    private var writeSlot: CompletableDeferred<Unit>? = null
    private var readSlot: CompletableDeferred<ByteArray>? = null
    private var descriptorSlot: CompletableDeferred<Unit>? = null

    @Volatile
    private var notifySink: Channel<NotifyEvent>? = null

    data class ServiceInfo(val uuid: String, val characteristics: List<String>)
    data class NotifyEvent(val service: String, val char: String, val bytes: ByteArray, val atMs: Long)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectSlot?.complete(Unit)
                try {
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    discoverSlot?.completeExceptionally(e)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val err = IllegalStateException("GATT disconnected (status $status)")
                connectSlot?.completeExceptionally(err)
                discoverSlot?.completeExceptionally(err)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val slot = discoverSlot ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) slot.complete(Unit)
            else slot.completeExceptionally(IllegalStateException("Service discovery failed: $status"))
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val slot = readSlot ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                slot.complete(characteristic.value ?: ByteArray(0))
            } else {
                slot.completeExceptionally(IllegalStateException("GATT read failed: $status"))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            val slot = writeSlot ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) slot.complete(Unit)
            else slot.completeExceptionally(IllegalStateException("GATT write failed: $status"))
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            val slot = descriptorSlot ?: return
            if (status == BluetoothGatt.GATT_SUCCESS) slot.complete(Unit)
            else slot.completeExceptionally(IllegalStateException("GATT descriptor write failed: $status"))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val bytes = characteristic.value ?: return
            notifySink?.trySend(
                NotifyEvent(
                    characteristic.service.uuid.toString(),
                    characteristic.uuid.toString(),
                    bytes,
                    System.currentTimeMillis(),
                ),
            )
        }
    }

    suspend fun connect(address: String): List<ServiceInfo> = mutex.withLock {
        val key = address.uppercase()
        val manager = context.getSystemService(BluetoothManager::class.java)
            ?: throw IllegalStateException("Bluetooth unavailable")
        val adapter = manager.adapter ?: throw IllegalStateException("Bluetooth unavailable")
        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid BLE address: $address")
        }
        closeSilently(key)
        val connected = CompletableDeferred<Unit>()
        val discovered = CompletableDeferred<Unit>()
        connectSlot = connected
        discoverSlot = discovered
        val gatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            throw SecurityException("BLE permission missing (BLUETOOTH_CONNECT)")
        } ?: throw IllegalStateException("connectGatt returned null")
        try {
            try {
                withTimeout(OP_TIMEOUT_MS) { connected.await() }
                withTimeout(OP_TIMEOUT_MS) { discovered.await() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("GATT connect/discover timed out")
            }
        } catch (e: Exception) {
            closeSilently(key)
            throw e
        } finally {
            if (connectSlot === connected) connectSlot = null
            if (discoverSlot === discovered) discoverSlot = null
        }
        connections[key] = gatt
        return gatt.services.orEmpty().map { service ->
            ServiceInfo(
                service.uuid.toString(),
                service.characteristics.orEmpty().map { it.uuid.toString() },
            )
        }
    }

    suspend fun read(address: String, serviceUuid: UUID, charUuid: UUID): ByteArray = mutex.withLock {
        val gatt = connections[address.uppercase()]
            ?: throw IllegalStateException("Not connected (call ble_connect first)")
        val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
            ?: throw IllegalArgumentException("Characteristic not found")
        val done = CompletableDeferred<ByteArray>()
        readSlot = done
        try {
            if (!gatt.readCharacteristic(characteristic)) {
                throw IllegalStateException("readCharacteristic rejected")
            }
            return try {
                withTimeout(OP_TIMEOUT_MS) { done.await() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("GATT read timed out")
            }
        } catch (e: SecurityException) {
            throw SecurityException("BLE permission missing (BLUETOOTH_CONNECT)")
        } finally {
            if (readSlot === done) readSlot = null
        }
    }

    suspend fun write(address: String, serviceUuid: UUID, charUuid: UUID, bytes: ByteArray, withResponse: Boolean) =
        mutex.withLock {
            val gatt = connections[address.uppercase()]
                ?: throw IllegalStateException("Not connected (call ble_connect first)")
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
                ?: throw IllegalArgumentException("Characteristic not found")
            @Suppress("DEPRECATION")
            characteristic.writeType = if (withResponse) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            val done = CompletableDeferred<Unit>()
            writeSlot = done
            try {
                if (!gatt.writeCharacteristic(characteristic)) {
                    throw IllegalStateException("writeCharacteristic rejected")
                }
                try {
                    withTimeout(OP_TIMEOUT_MS) { done.await() }
                } catch (e: TimeoutCancellationException) {
                    throw IllegalStateException("GATT write timed out")
                }
            } catch (e: SecurityException) {
                throw SecurityException("BLE permission missing (BLUETOOTH_CONNECT)")
            } finally {
                if (writeSlot === done) writeSlot = null
            }
        }

    fun disconnect(address: String) {
        closeSilently(address)
    }

    /**
     * Subscribes to notifications on a characteristic for [durationMs], collecting
     * change events. Requires the characteristic to support notify/indicate and an
     * active connection. Always disables the subscription afterwards.
     */
    suspend fun subscribe(address: String, serviceUuid: UUID, charUuid: UUID, durationMs: Long): List<NotifyEvent> =
        mutex.withLock {
            val key = address.uppercase()
            val gatt = connections[key]
                ?: throw IllegalStateException("Not connected (call ble_connect first)")
            val characteristic = gatt.getService(serviceUuid)?.getCharacteristic(charUuid)
                ?: throw IllegalArgumentException("Characteristic not found")
            val supportsNotify = (characteristic.properties and
                (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0
            if (!supportsNotify) throw IllegalArgumentException("Characteristic does not support notify/indicate")
            val channel = Channel<NotifyEvent>(Channel.UNLIMITED)
            notifySink = channel
            try {
                try {
                    if (!gatt.setCharacteristicNotification(characteristic, true)) {
                        throw IllegalStateException("setCharacteristicNotification rejected")
                    }
                } catch (e: SecurityException) {
                    throw subscribeFailure(e)
                }
                val descriptor = characteristic.getDescriptor(UUID.fromString(CLIENT_CONFIG))
                    ?: throw IllegalStateException("Missing CCCD descriptor (0x2902)")
                @Suppress("DEPRECATION")
                descriptor.value = cccdEnableValue(characteristic.properties)
                val written = CompletableDeferred<Unit>()
                descriptorSlot = written
                try {
                    if (!gatt.writeDescriptor(descriptor)) {
                        throw IllegalStateException("writeDescriptor rejected")
                    }
                    try {
                        withTimeout(OP_TIMEOUT_MS) { written.await() }
                    } catch (e: TimeoutCancellationException) {
                        throw IllegalStateException("CCCD write timed out")
                    }
                } catch (e: SecurityException) {
                    throw subscribeFailure(e)
                } finally {
                    if (descriptorSlot === written) descriptorSlot = null
                }
                val collected = mutableListOf<NotifyEvent>()
                try {
                    withTimeout(durationMs) {
                        for (event in channel) collected.add(event)
                    }
                } catch (e: TimeoutCancellationException) {
                    // Normal end of the collection window.
                }
                return collected
            } finally {
                notifySink = null
                channel.close()
                try {
                    gatt.setCharacteristicNotification(characteristic, false)
                } catch (_: Exception) {
                }
                // Restore CCCD to disabled so the peripheral stops notifying.
                // Best-effort: failures here must not mask the collected result.
                try {
                    val cccd = characteristic.getDescriptor(UUID.fromString(CLIENT_CONFIG))
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                        try {
                            gatt.writeDescriptor(cccd)
                        } catch (_: SecurityException) {
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

    private fun closeSilently(key: String) {
        try {
            connections.remove(key)?.close()
        } catch (_: Exception) {
        }
    }

    private fun hasConnectPermission(): Boolean {
        // BLUETOOTH_CONNECT exists only on API 31+ (matches the API-guarded
        // REQUIRED_PERMISSIONS in DashboardViewModel); below that the legacy
        // Bluetooth model applies and this path never authorizes.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.BLUETOOTH_CONNECT,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Re-maps a framework SecurityException from CCCD setup. When the grant is
     * present the failure is peripheral/link-side (e.g. unbonded HID gate), so
     * it must surface as IllegalStateException: RadioProvider's outer catch
     * rewrites every SecurityException to "permission missing". True denials
     * keep the legacy SecurityException text.
     */
    private fun subscribeFailure(cause: SecurityException): Exception {
        val granted = hasConnectPermission()
        val message = subscribeFailureMessage(granted, cause.message)
        return if (granted) IllegalStateException(message) else SecurityException(message)
    }

    companion object {
        const val OP_TIMEOUT_MS = 15_000L
        const val CLIENT_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"

        /**
         * CCCD enable value per Bluetooth Core Spec Vol 3 Part G §3.3.3.3:
         * bit 0 = notifications, bit 1 = indications. Writing the
         * notification config to an indicate-only characteristic is rejected
         * by peripherals (observed status 6 live). Callers pre-validate
         * notify|indicate, so the else branch is indicate-only. Byte literals
         * (not the framework constants) keep this JVM-testable without Robolectric.
         */
        fun cccdEnableValue(properties: Int): ByteArray =
            if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                byteArrayOf(0x01, 0x00)
            } else {
                byteArrayOf(0x02, 0x00)
            }

        /**
         * Honest subscribe-failure text: a SecurityException with the permission
         * granted means the peripheral/link rejected CCCD setup (commonly an
         * unbonded HID gate), not a missing grant. Pure for JVM tests.
         */
        fun subscribeFailureMessage(permissionGranted: Boolean, cause: String?): String =
            if (permissionGranted) {
                buildString {
                    append("BLE CCCD setup rejected by peripheral/link (BLUETOOTH_CONNECT granted")
                    if (!cause.isNullOrBlank()) append(": $cause")
                    append("; peripheral may require bonding)")
                }
            } else {
                "BLE permission missing (BLUETOOTH_CONNECT)"
            }
    }
}
