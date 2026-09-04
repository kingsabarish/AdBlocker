package com.adblocker.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(active: Boolean, connecting: Boolean, blocked: Long, onToggle: () -> Unit) {
    val isOn = active || connecting
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when {
                active -> "AdBlocker is active"
                connecting -> "Connecting..."
                else -> "AdBlocker is off"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Blocked: $blocked requests",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(48.dp))
        Surface(
            onClick = { if (!connecting) onToggle() },
            shape = CircleShape,
            modifier = Modifier
                .size(160.dp)
                .then(if (connecting) Modifier.alpha(pulseAlpha) else Modifier),
            color = when {
                active -> MaterialTheme.colorScheme.primary
                connecting -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = when {
                active -> MaterialTheme.colorScheme.onPrimary
                connecting -> MaterialTheme.colorScheme.onTertiary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            shadowElevation = 8.dp,
            tonalElevation = 2.dp,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.PowerSettingsNew,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = when {
                        active -> "ON"
                        connecting -> "..."
                        else -> "OFF"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = when {
                active -> "Tap to disable"
                connecting -> "Starting VPN..."
                else -> "Tap to enable"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
