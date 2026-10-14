package com.phonemcp.server.presentation.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand-derived Material3 palettes (see docs/branding.md).
 *
 * Primary roles use darkened brand blue/violet so white-on-primary text keeps
 * AA contrast in light mode; containers carry the vivid brand tones. Dark mode
 * inverts to light-on-dark per M3 guidance. Surfaces stay M3 baseline neutrals.
 */
val BrandBlue = Color(0xFF3B82F6)
val BrandTeal = Color(0xFF3AD7CC)
val BrandViolet = Color(0xFF8D4FF3)

// Semantic status colors for server beacons, badges, and health checks
val StatusGreen = Color(0xFF10B981)
val StatusGreenContainer = Color(0xFFD1FAE5)
val OnStatusGreenContainer = Color(0xFF065F46)
val DarkStatusGreenContainer = Color(0xFF064E3B)
val DarkOnStatusGreenContainer = Color(0xFFA7F3D0)

val StatusAmber = Color(0xFFF59E0B)
val StatusAmberContainer = Color(0xFFFEF3C7)
val OnStatusAmberContainer = Color(0xFF92400E)
val DarkStatusAmberContainer = Color(0xFF78350F)
val DarkOnStatusAmberContainer = Color(0xFFFDE68A)

val StatusSlate = Color(0xFF64748B)
val StatusSlateContainer = Color(0xFFE2E8F0)
val OnStatusSlateContainer = Color(0xFF334155)
val DarkStatusSlateContainer = Color(0xFF1E293B)
val DarkOnStatusSlateContainer = Color(0xFFCBD5E1)

// Terminal console styling
val TerminalBackground = Color(0xFF0F172A)
val TerminalText = Color(0xFF38BDF8)
val TerminalGreen = Color(0xFF4ADE80)
val TerminalGray = Color(0xFF94A3B8)
val TerminalBorder = Color(0xFF1E293B)

val LightColors = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF0F766E),
    onSecondaryContainer = Color(0xFF042F2E),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFF7C3AED),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFEDE9FE),
    onTertiaryContainer = Color(0xFF4C1D95),
    error = Color(0xFFBA1A1A),
    surface = Color(0xFFF8FAFC),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFF93C5FD),
    onPrimary = Color(0xFF1E3A8A),
    primaryContainer = Color(0xFF1E40AF),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF4C1D95),
    tertiaryContainer = Color(0xFF6D28D9),
    onTertiaryContainer = Color(0xFFEDE9FE),
    error = Color(0xFFFFB4AB),
    surface = Color(0xFF0B1120),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
)

