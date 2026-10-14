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
    }
}
