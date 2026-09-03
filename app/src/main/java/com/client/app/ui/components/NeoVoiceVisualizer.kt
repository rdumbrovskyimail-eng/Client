package com.client.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NeoVoiceVisualizer(
    amplitude: Float,
    isConnected: Boolean,
    isConnecting: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")

    val coreColor by animateColorAsState(
        targetValue = when {
            hasError -> Color(0xFFEF4444)
            !isConnected -> Color(0xFF52525B)
            isAiSpeaking -> Color(0xFF10B981)
            isMicActive -> Color(0xFF3B82F6)
            else -> Color(0xFFF59E0B)
        },
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "color"
    )

    val breathing by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 800 else 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    val currentScale = if (isConnected) (1f + amplitude * 0.9f).coerceIn(1f, 1.9f) * breathing else breathing

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val c = this.center
            val r = (this.size.minDimension / 2.7f) * currentScale

            // Внешняя интерференционная аура
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coreColor.copy(alpha = 0.35f), Color.Transparent),
                    center = c,
                    radius = r * 1.6f
                ),
                radius = r * 1.6f,
                center = c
            )

            // Реактивное внешнее кольцо
            if (isConnected) {
                drawCircle(
                    color = coreColor.copy(alpha = 0.5f),
                    radius = r * 1.15f,
                    center = c,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            // Центральное плазменное тело
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(coreColor, coreColor.copy(alpha = 0.6f), coreColor),
                    center = c
                ),
                radius = r * 0.88f,
                center = c
            )

            // Световой блик
            drawCircle(
                color = Color.White.copy(alpha = if (isConnected) 0.35f else 0.15f),
                radius = r * 0.35f,
                center = c
            )
        }
    }
}