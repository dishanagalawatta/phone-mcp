package com.phonemcp.server.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAccessibilityTest {

    @Test
    fun matchesFullClassNameAsStoredBySystem() {
        assertTrue(
            DashboardViewModel.isServiceListed(
                "com.phonemcp.server/com.phonemcp.server.service.PhoneMcpAccessibilityService",
                "com.phonemcp.server",
            ),
        )
    }

    @Test
    fun matchesShortClassName() {
        assertTrue(
            DashboardViewModel.isServiceListed(
                "com.phonemcp.server/.service.PhoneMcpAccessibilityService",
                "com.phonemcp.server",
            ),
        )
    }

    @Test
    fun matchesAmongOtherServices() {
        assertTrue(
            DashboardViewModel.isServiceListed(
                "com.example.app/.service.TalkBack:com.phonemcp.server/com.phonemcp.server.service.PhoneMcpAccessibilityService",
                "com.phonemcp.server",
            ),
        )
    }

    @Test
    fun rejectsOtherPackagesAndBlanks() {
        assertFalse(DashboardViewModel.isServiceListed(null, "com.phonemcp.server"))
        assertFalse(DashboardViewModel.isServiceListed("", "com.phonemcp.server"))
        assertFalse(
            DashboardViewModel.isServiceListed(
                "com.evil.app/com.evil.app.service.PhoneMcpAccessibilityService",
                "com.phonemcp.server",
            ),
        )
        assertFalse(
            DashboardViewModel.isServiceListed(
                "com.phonemcp.server/com.phonemcp.server.service.OtherService",
                "com.phonemcp.server",
            ),
        )
    }
}
