package com.phonemcp.server.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Modern Security Guardrails Card providing hardware kill-switches.
 * Disabling immediately prevents AI agents from accessing sensitive phone I/O.
 */
@Composable
fun SecurityGuardrailsCard(
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    locationEnabled: Boolean,
    bleWriteEnabled: Boolean,
    uiEnabled: Boolean,
    netEnabled: Boolean,
    onMicToggle: (Boolean) -> Unit,
    onCameraToggle: (Boolean) -> Unit,
    onLocationToggle: (Boolean) -> Unit,
    onBleWriteToggle: (Boolean) -> Unit,
    onUiToggle: (Boolean) -> Unit,
    onNetToggle: (Boolean) -> Unit,
    externalEnabled: Boolean = false,
    onExternalToggle: (Boolean) -> Unit = {},
    externalCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
        ) {
            Text(
                text = "Privacy & Hardware Gates",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Agent access (kill-switches)",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Turn off any switch to immediately sever agent access to that hardware capability.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))

            GuardrailToggleRow(
                title = "Microphone",
                codeLabel = "Microphone (audio_record)",
                description = "Live audio streaming and voice recording",
                tag = "toggle_mic",
                checked = micEnabled,
                onChange = onMicToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "Camera & Video",
                codeLabel = "Camera (photo/video)",
                description = "High-res photo captures and video streaming",
                tag = "toggle_camera",
                checked = cameraEnabled,
                onChange = onCameraToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "Geolocation",
                codeLabel = "Location",
                description = "High-accuracy GPS and network coordinates",
                tag = "toggle_location",
                checked = locationEnabled,
                onChange = onLocationToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "Bluetooth LE Write",
                codeLabel = "BLE write (actuates hardware)",
                description = "Modifying peripheral characteristics and actuating hardware",
                tag = "toggle_blewrite",
                checked = bleWriteEnabled,
                onChange = onBleWriteToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "Screen & UI Control",
                codeLabel = "UI control (screen + actions)",
                description = "Visual hierarchy inspection, taps, swipes, and text input",
                tag = "toggle_ui",
                checked = uiEnabled,
                onChange = onUiToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "Network Diagnostics",
                codeLabel = "Network inspection (net_*)",
                description = "Traffic inspection, per-app flows, and firewall control",
                tag = "toggle_net",
                checked = netEnabled,
                onChange = onNetToggle,
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                modifier = Modifier.padding(vertical = 4.dp),
            )

            GuardrailToggleRow(
                title = "External App Capabilities",
                codeLabel = "Third-party tools (ext_*)",
                description = "Proxied tools from other apps ($externalCount registered) — untrusted until enabled",
                tag = "toggle_external",
                checked = externalEnabled,
                onChange = onExternalToggle,
            )
        }
    }
}

@Composable
private fun GuardrailToggleRow(
    title: String,
    codeLabel: String,
    description: String,
    tag: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = codeLabel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            modifier = Modifier.testTag(tag),
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}
