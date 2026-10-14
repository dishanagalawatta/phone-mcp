package com.phonemcp.server.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.theme.ThemeMode

/**
 * Modern Appearance & Application Details Card.
 * Configures theme mode (System, Light, Dark) and displays server metadata.
 */
@Composable
fun AppearanceCard(
    themeMode: ThemeMode,
    onThemeChange: (ThemeMode) -> Unit,
    secureScreen: Boolean = true,
    onSecureChange: (Boolean) -> Unit = {},
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
                text = "Preferences",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Appearance",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ThemeOptionChip(
                    label = "System",
                    tag = "theme_system",
                    selected = themeMode == ThemeMode.SYSTEM,
                    modifier = Modifier.weight(1f),
                ) {
                    onThemeChange(ThemeMode.SYSTEM)
                }

                ThemeOptionChip(
                    label = "Light",
                    tag = "theme_light",
                    selected = themeMode == ThemeMode.LIGHT,
                    modifier = Modifier.weight(1f),
                ) {
                    onThemeChange(ThemeMode.LIGHT)
                }

                ThemeOptionChip(
                    label = "Dark",
                    tag = "theme_dark",
                    selected = themeMode == ThemeMode.DARK,
                    modifier = Modifier.weight(1f),
                ) {
                    onThemeChange(ThemeMode.DARK)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            // Secure-screen toggle: ON hides this window from mirrors/recents
            // (scrcpy shows black). Turn OFF to drive the dashboard over a mirror —
            // anyone viewing it can then read the pairing token/QR.
            // Guarded: accidental flips black out mirrors or expose the token.
            var pendingSecure by remember { mutableStateOf<Boolean?>(null) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Secure screen",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (secureScreen) "Hidden in mirrors/recents" else "Visible in mirrors (token exposed)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = secureScreen,
                    onCheckedChange = { pendingSecure = it },
                )
            }
            pendingSecure?.let { want ->
                val (title, message, confirmLabel) = secureConfirmText(want)
                ConfirmDialog(
                    title = title,
                    message = message,
                    confirmLabel = confirmLabel,
                    onConfirm = {
                        onSecureChange(want)
                        pendingSecure = null
                    },
                    onDismiss = { pendingSecure = null },
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Server Metadata & Security Guarantee
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Phone MCP Server v${com.phonemcp.server.BuildConfig.VERSION_NAME}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "Ktor Netty • TLS TOFU",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "100% on-device local execution. Zero network exfiltration. Physical kill-switches strictly enforced.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeOptionChip(
    label: String,
    tag: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        modifier = modifier.testTag(tag),
        shape = RoundedCornerShape(10.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}
