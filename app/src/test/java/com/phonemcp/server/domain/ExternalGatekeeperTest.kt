package com.phonemcp.server.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalGatekeeperTest {

    @Test
    fun externalToolsBlockedByDefault() {
        val gatekeeper = Gatekeeper()
        assertFalse(gatekeeper.isAllowed("ext_notes__search"))
        assertFalse(gatekeeper.isAllowed("ext_anything"))
    }

    @Test
    fun managementToolsStayUngated() {
        val gatekeeper = Gatekeeper()
        assertTrue(gatekeeper.isAllowed("capabilities_list"))
        assertTrue(gatekeeper.isAllowed("capabilities_register"))
        assertTrue(gatekeeper.isAllowed("capabilities_unregister"))
        assertTrue(gatekeeper.isAllowed("capabilities_rescan"))
    }

    @Test
    fun externalToggleRoundTrip() {
        val gatekeeper = Gatekeeper()
        gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, true)
        assertTrue(gatekeeper.isAllowed("ext_notes__search"))
        assertTrue(gatekeeper.isEnabled(Gatekeeper.TOOLS_EXT))
        gatekeeper.setEnabled(Gatekeeper.TOOLS_EXT, false)
        assertFalse(gatekeeper.isAllowed("ext_notes__search"))
        // Management plane unaffected by the toggle.
        assertTrue(gatekeeper.isAllowed("capabilities_list"))
    }
}
