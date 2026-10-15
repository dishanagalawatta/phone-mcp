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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * On-device directory of third-party capabilities registered via local IPC
 * (bound service, directory provider, announce broadcast) or the
 * `capabilities_*` MCP tools. Owners can approve/revoke invocation consent,
 * remove entries, and trigger a rescan of installed provider apps.
 */
data class ExternalCapabilityEntry(
    val id: String,
    val approved: Boolean,
)

@Composable
fun ExternalCapabilitiesCard(
    capabilities: List<ExternalCapabilityEntry>,
    onApprove: (String) -> Unit = {},
    onRevoke: (String) -> Unit = {},
    onUnregister: (String) -> Unit = {},
    onRescan: () -> Unit = {},
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
                text = "Third-party capabilities",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "External app tools",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Apps on this phone can register tools dynamically (ext_<app>__<tool>). " +
                    "Agents see them in tools/list once the External switch above is ON.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (capabilities.isEmpty()) {
                Text(
                    text = "No external capabilities registered.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("external_empty"),
                )
            } else {
                capabilities.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.id,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (entry.approved) "approved for invocation" else "needs approval",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (entry.approved) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (entry.approved) {
                            TextButton(
                                onClick = { onRevoke(entry.id) },
                                modifier = Modifier.testTag("external_revoke_${entry.id}"),
                            ) {
                                Text(text = "Revoke")
                            }
                        } else {
                            TextButton(
                                onClick = { onApprove(entry.id) },
                                modifier = Modifier.testTag("external_approve_${entry.id}"),
                            ) {
                                Text(text = "Approve")
                            }
                        }
                        TextButton(
                            onClick = { onUnregister(entry.id) },
                            modifier = Modifier.testTag("external_remove_${entry.id}"),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(text = "Remove")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onRescan,
                modifier = Modifier.testTag("external_rescan"),
            ) {
                Text(text = "Rescan installed apps")
            }
        }
    }
}
