// >>> FILE: app/src/main/java/com/client/app/ui/components/NeoVoiceVisualizer.kt
package com.client.app.ui.components

import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Устаревший 2D Canvas визуализатор.
 * Заменен на аппаратный однопроходный 120 FPS AGSL-шейдер AgslVoiceVisualizer.
 */
@Deprecated(
    "Заменен на AgslVoiceVisualizer",
    ReplaceWith("AgslVoiceVisualizer")
)
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
    size: Dp = 170.dp
) {
    val targetAmplitude =
        amplitude
            .coerceIn(0f, 1f)
            .coerceAtLeast(
                if (isConnected || isConnecting) {
                    0.08f
                } else {
                    0.05f
                }
            )

    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(
            durationMillis = 90,
            easing = LinearOutSlowInEasing
        ),
        label = "neo_amplitude"
    )

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    hasError -> "Ошибка голосового режима"
                    isAiSpeaking -> "Ассистент говорит"
                    isMicActive -> "Микрофон слушает"
                    isConnecting -> "Сессия подключается"
                    else -> "Голосовой режим ожидания"
                }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.size(size)
        ) {
            val canvasW = this.size.width
            val canvasH = this.size.height

            val center = Offset(
                canvasW / 2f,
                canvasH / 2f
            )

            val baseRadius = minOf(
                canvasW,
                canvasH
            ) * 0.30f

            val radius = baseRadius * (
                1f + animatedAmplitude * 0.30f
            )

            drawCircle(
                color = Color(0xFF202024),
                radius = baseRadius,
                center = center
            )

            drawCircle(
                color = Color(0xFF4F46E5),
                radius = radius,
                center = center,
                style = Stroke(
                    width = minOf(
                        canvasW,
                        canvasH
                    ) * 0.035f
                )
            )
        }
    }
}