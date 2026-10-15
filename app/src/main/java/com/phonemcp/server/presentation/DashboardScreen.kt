package com.phonemcp.server.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.components.ActivityConsoleCard
import com.phonemcp.server.presentation.components.AgentPairingCard
import com.phonemcp.server.presentation.components.AppearanceCard
import com.phonemcp.server.presentation.components.ExternalCapabilitiesCard
import com.phonemcp.server.presentation.components.NetworkTunnelCard
import com.phonemcp.server.presentation.components.PhoneMcpTopBar
import com.phonemcp.server.presentation.components.SecurityGuardrailsCard
import com.phonemcp.server.presentation.components.ServerHeroCard
import com.phonemcp.server.presentation.components.SystemHealthCard
import com.phonemcp.server.presentation.theme.ThemeMode

/**
 * High-level category filters for the dashboard.
 * Defaults to ALL to ensure complete hierarchy visibility for both users and automated tests.
 */
enum class DashboardCategory(val title: String) {
    ALL("Overview"),
    SERVER("Server & Auth"),
    GUARDRAILS("Guardrails"),
    NETWORK("Network"),
    CONSOLE("Console"),
    SETTINGS("Settings"),
}

/**
 * Modern, clean, professional, and sleek Dashboard interface for Phone MCP.
 * Implements full Material 3 design, visual hierarchy, responsive status beacons,
 * and strict layer discipline.
 */
@Composable
fun DashboardScreen(
    state: DashboardUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onAddTile: () -> Unit,
    onAddWidget: () -> Unit,
    onGrantPermissions: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onCameraToggle: (Boolean) -> Unit,
    onLocationToggle: (Boolean) -> Unit,
    onBleWriteToggle: (Boolean) -> Unit,
    onUiToggle: (Boolean) -> Unit,
    onNetToggle: (Boolean) -> Unit,
    onExternalToggle: (Boolean) -> Unit = {},
    onUnregisterExternal: (String) -> Unit = {},
    onExternalApproval: (String, Boolean) -> Unit = { _, _ -> },
    onRescanExternal: () -> Unit = {},
    onStartVpn: () -> Unit,
    onStopVpn: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onCopy: (label: String, text: String) -> Unit,
    onRotateToken: () -> Unit,
    onSecureChange: (Boolean) -> Unit = {},
) {
    var selectedCategory by rememberSaveable { mutableStateOf(DashboardCategory.ALL) }
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            PhoneMcpTopBar(
                running = state.running,
                themeMode = state.themeMode,
                onThemeToggle = {
                    val next = when (state.themeMode) {
                        ThemeMode.SYSTEM -> ThemeMode.DARK
                        ThemeMode.DARK -> ThemeMode.LIGHT
                        ThemeMode.LIGHT -> ThemeMode.SYSTEM
                    }
                    onThemeChange(next)
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            // Category navigation filter strip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DashboardCategory.entries.forEach { category ->
                    val isSelected = selectedCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = category },
                        label = {
                            Text(
                                text = category.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Server Hero Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.SERVER) {
                    ServerHeroCard(
                        running = state.running,
                        urls = state.urls,
                        onStart = onStart,
                        onStop = onStop,
                        onAddTile = onAddTile,
                        onAddWidget = onAddWidget,
                        onCopyEndpoint = { url -> onCopy("endpoint", url) },
                        tileAdded = state.tileAdded,
                    )
                }

                // Agent Pairing & Authentication Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.SERVER) {
                    AgentPairingCard(
                        endpointUrl = state.urls.firstOrNull(),
                        token = state.token,
                        fingerprint = state.fingerprint,
                        onCopy = onCopy,
                        onRotateToken = onRotateToken,
                    )
                }

                // Security Guardrails Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.GUARDRAILS) {
                    SecurityGuardrailsCard(
                        micEnabled = state.micEnabled,
                        cameraEnabled = state.cameraEnabled,
                        locationEnabled = state.locationEnabled,
                        bleWriteEnabled = state.bleWriteEnabled,
                        uiEnabled = state.uiEnabled,
                        netEnabled = state.netEnabled,
                        onMicToggle = onMicToggle,
                        onCameraToggle = onCameraToggle,
                        onLocationToggle = onLocationToggle,
                        onBleWriteToggle = onBleWriteToggle,
                        onUiToggle = onUiToggle,
                        onNetToggle = onNetToggle,
                        externalEnabled = state.externalEnabled,
                        onExternalToggle = onExternalToggle,
                        externalCount = state.externalCapabilities.size,
                    )
                }

                // Third-party capabilities directory
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.GUARDRAILS) {
                    ExternalCapabilitiesCard(
                        capabilities = state.externalCapabilities,
                        onApprove = { onExternalApproval(it, true) },
                        onRevoke = { onExternalApproval(it, false) },
                        onUnregister = onUnregisterExternal,
                        onRescan = onRescanExternal,
                    )
                }

                // Network Tunnel Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.NETWORK) {
                    NetworkTunnelCard(
                        vpnRunning = state.vpnRunning,
                        netEnabled = state.netEnabled,
                        onStartVpn = onStartVpn,
                        onStopVpn = onStopVpn,
                        onNetToggle = onNetToggle,
                    )
                }

                // System Health & Permissions Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.SETTINGS) {
                    SystemHealthCard(
                        permissions = state.permissions,
                        accessibilityOn = state.accessibilityOn,
                        onGrantPermissions = onGrantPermissions,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    )
                }

                // Activity Console Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.CONSOLE) {
                    ActivityConsoleCard(
                        log = state.log,
                        onCopyLogs = {
                            val fullLog = state.log.joinToString("\n")
                            onCopy("logs", fullLog)
                        },
                    )
                }

                // Appearance & Preferences Card
                if (selectedCategory == DashboardCategory.ALL || selectedCategory == DashboardCategory.SETTINGS) {
                    AppearanceCard(
                        themeMode = state.themeMode,
                        onThemeChange = onThemeChange,
                        secureScreen = state.secureScreen,
                        onSecureChange = onSecureChange,
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
