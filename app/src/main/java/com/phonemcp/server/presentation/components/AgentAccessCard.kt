package com.phonemcp.server.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.DashboardUiState
import com.phonemcp.server.presentation.theme.StatusAmber
import com.phonemcp.server.presentation.theme.StatusGreen
import com.phonemcp.server.presentation.theme.StatusSlate

/**
 * One-Button Full Permission Command Card for Phone MCP.
 * Provides a single-tap gateway to enable all 7 hardware kill-switches,
 * request missing Android runtime permissions, approve external capabilities,
 * and review UI automation readiness for AI agents.
 */
@Composable
fun AgentAccessCard(
    state: DashboardUiState,
    onGrantFullAccess: () -> Unit,
    onRevokeFullAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val allGates = state.allGatesEnabled
    val fullAccess = state.hasFullAgentAccess
    val permissionsGranted = state.allPermissionsGranted

    ElevatedCard(
        modifier = modifier.fillMaxWidth().testTag("agent_access_card"),
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
            // Header: Category label + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Autonomous Agent Setup",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Agent Permissions & Access",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when {
                        fullAccess -> MaterialTheme.colorScheme.secondaryContainer
                        allGates -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                ) {
                    Text(
                        text = when {
                            fullAccess -> "FULL ACCESS"
                            allGates -> "GATES ON • PERMS PENDING"
                            else -> "RESTRICTED (${state.activeGateCount}/7)"
                        },
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("agent_access_status"),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            fullAccess -> MaterialTheme.colorScheme.onSecondaryContainer
                            allGates -> MaterialTheme.colorScheme.onTertiaryContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "One-tap control to grant or revoke full agent autonomy. Enables all 7 hardware gates, approves external tools, and verifies system permissions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(14.dp))

            // Sub-status Indicators Row (Hardware Gates, System Perms, Accessibility)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AccessSubStatusPill(
                    title = "Hardware Gates",
                    value = "${state.activeGateCount}/7",
                    isComplete = allGates,
                    modifier = Modifier.weight(1f),
                )
                AccessSubStatusPill(
                    title = "System Perms",
                    value = if (state.permissions.isEmpty()) "Pending" else "${state.grantedPermissionCount}/${state.permissions.size}",
                    isComplete = permissionsGranted,
                    modifier = Modifier.weight(1f),
                )
                AccessSubStatusPill(
                    title = "UI Access",
                    value = if (state.accessibilityOn) "Active" else "Off",
                    isComplete = state.accessibilityOn,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            // One Button Controls
            if (!allGates || !permissionsGranted) {
                Button(
                    onClick = onGrantFullAccess,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_grant_full_access"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Text(
                        text = "⚡ Grant Full Agent Access",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                }
            } else {
                // All gates are enabled
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = onRevokeFullAccess,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("btn_revoke_full_access"),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            text = "Revoke All (Safe Mode)",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Button(
                        onClick = onGrantFullAccess,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("btn_grant_full_access"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary,
                        ),
                    ) {
                        Text(
                            text = "Re-check All",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // Accessibility prompt banner if accessibility is off but gates are on
            if (allGates && !state.accessibilityOn) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Next Step: UI Automation",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Enable Phone MCP in Accessibility settings for agent screen & tap control.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier.testTag("btn_open_a11y_settings"),
                        ) {
                            Text("Enable")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccessSubStatusPill(
    title: String,
    value: String,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isComplete) StatusGreen else StatusAmber),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
