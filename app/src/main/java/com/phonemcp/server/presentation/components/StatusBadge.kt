package com.phonemcp.server.presentation.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonemcp.server.presentation.theme.DarkOnStatusGreenContainer
import com.phonemcp.server.presentation.theme.DarkOnStatusSlateContainer
import com.phonemcp.server.presentation.theme.DarkStatusGreenContainer
import com.phonemcp.server.presentation.theme.DarkStatusSlateContainer
import com.phonemcp.server.presentation.theme.OnStatusGreenContainer
import com.phonemcp.server.presentation.theme.OnStatusSlateContainer
import com.phonemcp.server.presentation.theme.StatusGreen
import com.phonemcp.server.presentation.theme.StatusGreenContainer
import com.phonemcp.server.presentation.theme.StatusSlate
import com.phonemcp.server.presentation.theme.StatusSlateContainer

/**
 * Modern pill badge indicating real-time system state (Online / Standby)
 * with an animated glowing pulse beacon when active.
 */
@Composable
fun StatusBadge(
    active: Boolean,
    activeText: String = "ONLINE",
    inactiveText: String = "STANDBY",
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (active) {
        if (isDark) DarkStatusGreenContainer else StatusGreenContainer
    } else {
        if (isDark) DarkStatusSlateContainer else StatusSlateContainer
    }

    val contentColor = if (active) {
        if (isDark) DarkOnStatusGreenContainer else OnStatusGreenContainer
    } else {
        if (isDark) DarkOnStatusSlateContainer else OnStatusSlateContainer
    }

    val dotColor = if (active) StatusGreen else StatusSlate

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale",
    )

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .border(
                width = 1.dp,
                color = dotColor.copy(alpha = 0.35f),
                shape = RoundedCornerShape(50),
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(dotColor.copy(alpha = 0.35f)),
                )
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (active) activeText else inactiveText,
            color = contentColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp,
        )
    }
}
