package com.phonemcp.server.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatekeeperTest {

    @Test
    fun sensitiveToolsBlockedByDefault() {
        val gatekeeper = Gatekeeper()
        assertFalse(gatekeeper.isAllowed("audio_record"))
        assertFalse(gatekeeper.isAllowed("camera_capture"))
        assertFalse(gatekeeper.isAllowed("camera_video"))
        assertFalse(gatekeeper.isAllowed("location_read"))
        assertFalse(gatekeeper.isAllowed("ble_write"))
        assertFalse(gatekeeper.isAllowed("ui_screenshot"))
        assertFalse(gatekeeper.isAllowed("ui_click"))
        assertFalse(gatekeeper.isAllowed("net_status"))
        assertFalse(gatekeeper.isAllowed("net_connections"))
        assertTrue(gatekeeper.isAllowed("vibrate"))
        assertTrue(gatekeeper.isAllowed("battery_read"))
        assertTrue(gatekeeper.isAllowed("ble_read"))
    }

    @Test
    fun toggleRoundTrip() {
        val gatekeeper = Gatekeeper()
        gatekeeper.setEnabled(Gatekeeper.TOOLS_MIC, true)
        assertTrue(gatekeeper.isAllowed("audio_record"))
        assertTrue(gatekeeper.isEnabled(Gatekeeper.TOOLS_MIC))
        gatekeeper.setEnabled(Gatekeeper.TOOLS_MIC, false)
        assertFalse(gatekeeper.isAllowed("audio_record"))
    }

    @Test
    fun bleWriteIsExactMatch() {
        val gatekeeper = Gatekeeper()
        // Disabled by default: exact tool blocked ...
        assertFalse(gatekeeper.isAllowed("ble_write"))
        // ... but hypothetical future siblings are not over-matched.
        assertTrue(gatekeeper.isAllowed("ble_write_extended"))
        assertTrue(gatekeeper.isAllowed("ble_writer"))
    }
}
