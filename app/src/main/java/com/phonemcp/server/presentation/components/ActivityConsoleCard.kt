package com.phonemcp.server.presentation.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.theme.TerminalBackground
import com.phonemcp.server.presentation.theme.TerminalBorder
import com.phonemcp.server.presentation.theme.TerminalGray
import com.phonemcp.server.presentation.theme.TerminalGreen
import com.phonemcp.server.presentation.theme.TerminalText

/**
 * Modern Live Activity Console displaying the circular tool-call log buffer
 * in an authentic dark terminal theme with automatic bottom scroll.
 */
@Composable
fun ActivityConsoleCard(
    log: List<String>,
    onCopyLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tail = log.takeLast(80)
    val listState = rememberLazyListState()

    LaunchedEffect(tail.size) {
        if (tail.isNotEmpty()) {
            listState.scrollToItem(tail.lastIndex)
        }
    }

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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Real-time Telemetry",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Tool-call log",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(
                            text = "${tail.size} ENTRIES",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (tail.isNotEmpty()) {
                        TextButton(
                            onClick = onCopyLogs,
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("Copy", fontSize = 12.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Dark Terminal Console Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(TerminalBackground)
                    .border(1.dp, TerminalBorder, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                if (tail.isEmpty()) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "Waiting for agent requests...",
                            color = TerminalGray,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Connect an MCP client to see live tool calls",
                            color = TerminalGray.copy(alpha = 0.7f),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(tail) { line ->
                            val color = when {
                                line.contains("ERR", ignoreCase = true) || line.contains("401", ignoreCase = true) -> Color(0xFFF87171)
                                line.contains("CALL", ignoreCase = true) -> TerminalText
                                line.contains("OK", ignoreCase = true) || line.contains("RES", ignoreCase = true) -> TerminalGreen
                                else -> TerminalGray
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = color,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
