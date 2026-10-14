package com.phonemcp.server.presentation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** User-visible appearance choice, persisted in SharedPreferences (see DashboardViewModel). */
enum class ThemeMode(val stored: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Pure; unknown/blank values fall back to SYSTEM. JVM-testable. */
        fun parse(raw: String?): ThemeMode =
            entries.firstOrNull { it.stored == raw?.trim()?.lowercase() } ?: SYSTEM
    }
}

/** Pure theme resolution: explicit choice wins, SYSTEM follows the OS. JVM-testable. */
fun resolveDarkTheme(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
    ThemeMode.SYSTEM -> systemDark
}

/**
 * App theme: brand M3 palettes, or Material You dynamic color on API 31+ when
 * [dynamic] is true. Call once at the activity root.
 */
@Composable
fun PhoneMcpTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    dynamic: Boolean = true,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = resolveDarkTheme(mode, systemDark)
    val context = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
