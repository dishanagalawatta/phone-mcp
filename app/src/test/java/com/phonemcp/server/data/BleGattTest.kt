package com.phonemcp.server.data

import android.bluetooth.BluetoothGattCharacteristic
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BleGattTest {

    @Test
    fun cccdNotifyForNotifyCapable() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            BleGatt.cccdEnableValue(BluetoothGattCharacteristic.PROPERTY_NOTIFY),
        )
    }

    @Test
    fun cccdNotifyPreferredWhenBoth() {
        assertArrayEquals(
            byteArrayOf(0x01, 0x00),
            BleGatt.cccdEnableValue(
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                    BluetoothGattCharacteristic.PROPERTY_INDICATE,
            ),
        )
    }

    @Test
    fun cccdIndicationForIndicateOnly() {
        // Writing the notify config (0x0001) to an indicate-only characteristic
        // is rejected by peripherals (observed status 6 live); bit 1 selects
        // indications per Core Spec Vol 3 Part G §3.3.3.3.
        assertArrayEquals(
            byteArrayOf(0x02, 0x00),
            BleGatt.cccdEnableValue(BluetoothGattCharacteristic.PROPERTY_INDICATE),
        )
    }

    @Test
    fun failureMessageKeepsLegacyTextWhenDenied() {
        assertEquals(
            "BLE permission missing (BLUETOOTH_CONNECT)",
            BleGatt.subscribeFailureMessage(false, "whatever"),
        )
    }

    @Test
    fun failureMessageNamesBondingWhenGranted() {
        val msg = BleGatt.subscribeFailureMessage(true, "status 133")
        assertTrue("granted" in msg)
        assertTrue("status 133" in msg)
        assertTrue("bonding" in msg)
    }

    @Test
    fun failureMessageOmitsBlankCause() {
        val msg = BleGatt.subscribeFailureMessage(true, null)
        assertTrue("granted" in msg)
        assertTrue("null" !in msg)
    }
}
