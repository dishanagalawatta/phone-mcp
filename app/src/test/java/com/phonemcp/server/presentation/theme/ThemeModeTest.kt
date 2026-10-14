package com.phonemcp.server.presentation.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {

    @Test
    fun parseKnownValues() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("system"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.parse("light"))
        assertEquals(ThemeMode.DARK, ThemeMode.parse("dark"))
    }

    @Test
    fun parseFallsBackToSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(null))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse(""))
        assertEquals(ThemeMode.SYSTEM, ThemeMode.parse("midnight"))
        assertEquals(ThemeMode.DARK, ThemeMode.parse(" DARK "))
    }

    @Test
    fun resolveExplicitWins() {
        assertFalse(resolveDarkTheme(ThemeMode.LIGHT, true))
        assertTrue(resolveDarkTheme(ThemeMode.DARK, false))
    }

    @Test
    fun resolveSystemFollowsOs() {
        assertTrue(resolveDarkTheme(ThemeMode.SYSTEM, true))
        assertFalse(resolveDarkTheme(ThemeMode.SYSTEM, false))
    }
}
