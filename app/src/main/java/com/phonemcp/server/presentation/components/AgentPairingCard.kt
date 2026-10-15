package com.phonemcp.server.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.UsbTransport
import com.phonemcp.server.presentation.pairingQrBitmap
import com.phonemcp.server.service.Pairing.groupedFingerprint
import com.phonemcp.server.service.Pairing.pairingPayload

/**
 * Agent pairing card: connection targets, credentials, and QR pairing in one
 * compact surface. Copy actions are inline icons (no button-row sprawl) and
 * the QR builds only on demand behind the pairing button.
 */
@Composable
fun AgentPairingCard(
    endpointUrl: String?,
    token: String?,
    fingerprint: String,
    onCopy: (label: String, text: String) -> Unit,
    onRotateToken: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    var qrRevealed by rememberSaveable { mutableStateOf(false) }

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
            // Header
            Text(
                text = "Authentication & Security",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Agent pairing (bearer token + TLS)",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(14.dp))

            if (endpointUrl == null || token == null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Start the server to generate the pairing token.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                return@Column
            }

            // ---- Connection targets (LAN + USB in one surface) ----
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    CopyableRow(
                        label = "Endpoint URL",
                        value = endpointUrl,
                        copyDescription = "Copy endpoint URL",
                        onCopy = { onCopy("endpoint", endpointUrl) },
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    CopyableRow(
                        label = "USB cable (no Wi-Fi needed)",
                        value = remember(endpointUrl) { UsbTransport.forwardCommand(endpointUrl) },
                        copyDescription = "Copy USB forward command",
                        onCopy = {
                            onCopy(
                                "usb-command",
                                UsbTransport.forwardCommand(endpointUrl),
                            )
                        },
                    )
                    CopyableRow(
                        label = null,
                        value = remember(endpointUrl) { UsbTransport.loopbackUrl(endpointUrl) },
                        copyDescription = "Copy USB loopback URL",
                        onCopy = {
                            onCopy(
                                "usb-url",
                                UsbTransport.loopbackUrl(endpointUrl),
                            )
                        },
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "USB uses the same token + SHA-256 pin. Never adb reverse.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ---- Credentials (token + fingerprint in one surface) ----
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Bearer Authorization Token",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (revealed) token else "••••••••••••••••",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                            ),
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { revealed = !revealed }) {
                            Text(if (revealed) "Hide" else "Show")
                        }
                        IconButton(onClick = { onCopy("token", token) }) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy bearer token",
                            )
                        }
                    }
                    if (fingerprint.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        CopyableRow(
                            label = "TLS Certificate Fingerprint (SHA-256)",
                            value = groupedFingerprint(fingerprint),
                            copyDescription = "Copy TLS fingerprint",
                            onCopy = { onCopy("fingerprint", groupedFingerprint(fingerprint)) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(14.dp))

            // ---- On-demand QR pairing (builds the bitmap only when asked) ----
            OutlinedButton(
                onClick = { qrRevealed = !qrRevealed },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = if (qrRevealed) "Hide pairing QR" else "Show pairing QR",
                    fontSize = 12.sp,
                )
            }

            AnimatedVisibility(
                visible = qrRevealed,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                val qrContent = remember(endpointUrl, token, fingerprint) {
                    pairingPayload(endpointUrl, token, fingerprint)
                }
                var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
                LaunchedEffect(qrContent) {
                    qrBitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        pairingQrBitmap(qrContent, 384)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scan to Pair Agent",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val bitmap = qrBitmap
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Pairing QR (endpoint + token + fingerprint)",
                                modifier = Modifier.size(176.dp),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = {
                    onRotateToken()
                    revealed = false
                    qrRevealed = false
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    text = "Rotate token (old agents stop working)",
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** Label + monospace value + trailing copy icon. Label omitted for continuations. */
@Composable
private fun CopyableRow(
    label: String?,
    value: String,
    copyDescription: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (label != null) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(4.dp))
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Default.ContentCopy,
                contentDescription = copyDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
