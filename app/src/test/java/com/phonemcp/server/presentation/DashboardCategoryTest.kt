package com.phonemcp.server.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardCategoryTest {

    @Test
    fun categoryEntriesContainAllRequiredSections() {
        val titles = DashboardCategory.entries.map { it.title }
        assertTrue(titles.contains("Overview"))
        assertTrue(titles.contains("Server & Auth"))
        assertTrue(titles.contains("Guardrails"))
        assertTrue(titles.contains("Network"))
        assertTrue(titles.contains("Console"))
        assertTrue(titles.contains("Settings"))
    }

    @Test
    fun defaultCategoryIsAll() {
        assertEquals(DashboardCategory.ALL, DashboardCategory.entries.first())
    }

    @Test
    fun dashboardUiStateDefaultsAreSafe() {
        val state = DashboardUiState()
        assertEquals(false, state.running)
        assertEquals(false, state.micEnabled)
        assertEquals(false, state.cameraEnabled)
        assertEquals(false, state.locationEnabled)
        assertEquals(false, state.bleWriteEnabled)
        assertEquals(false, state.uiEnabled)
        assertEquals(false, state.netEnabled)
        assertEquals(false, state.vpnRunning)
        assertTrue(state.urls.isEmpty())
        assertTrue(state.log.isEmpty())
        assertEquals(false, state.allGatesEnabled)
        assertEquals(false, state.allPermissionsGranted)
        assertEquals(false, state.hasFullAgentAccess)
        assertEquals(0, state.activeGateCount)
        assertEquals(0, state.grantedPermissionCount)
    }

    @Test
    fun dashboardUiStateFullAccessCalculation() {
        val allGatesOn = DashboardUiState(
            micEnabled = true,
            cameraEnabled = true,
            locationEnabled = true,
            bleWriteEnabled = true,
            uiEnabled = true,
            netEnabled = true,
            externalEnabled = true,
            permissions = mapOf("android.permission.CAMERA" to true, "android.permission.RECORD_AUDIO" to true),
            accessibilityOn = true,
        )
        assertTrue(allGatesOn.allGatesEnabled)
        assertTrue(allGatesOn.allPermissionsGranted)
        assertTrue(allGatesOn.hasFullAgentAccess)
        assertEquals(7, allGatesOn.activeGateCount)
        assertEquals(2, allGatesOn.grantedPermissionCount)

        val missingPerm = allGatesOn.copy(
            permissions = mapOf("android.permission.CAMERA" to true, "android.permission.RECORD_AUDIO" to false),
        )
        assertTrue(missingPerm.allGatesEnabled)
        assertEquals(false, missingPerm.allPermissionsGranted)
        assertEquals(false, missingPerm.hasFullAgentAccess)
        assertEquals(1, missingPerm.grantedPermissionCount)

        val a11yOff = allGatesOn.copy(accessibilityOn = false)
        assertTrue(a11yOff.allGatesEnabled)
        assertTrue(a11yOff.allPermissionsGranted)
        assertEquals(false, a11yOff.hasFullAgentAccess)
    }
}
