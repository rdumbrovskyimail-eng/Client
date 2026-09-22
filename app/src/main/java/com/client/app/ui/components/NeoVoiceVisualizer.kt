// >>> FILE: app/src/main/java/com/client/app/ui/components/NeoVoiceVisualizer.kt
package com.client.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Устаревший 2D Canvas визуализатор.
 * Заменен на аппаратный однопроходный 120 FPS AGSL-шейдер AgslVoiceVisualizer.
 */
@Deprecated("Заменен на AgslVoiceVisualizer", ReplaceWith("AgslVoiceVisualizer"))
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
            .coerceAtLeast(if (isConnected || isConnecting) 0.08f else 0.05f)

    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(90, easing = LinearOutSlowInEasing),
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
        Canvas(Modifier.size(size)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = minOf(size.width, size.height) * 0.30f
            val radius = baseRadius * (1f + animatedAmplitude * 0.30f)

            drawCircle(
                color = Color(0xFF202024),
                radius = baseRadius,
                center = center
            )
            drawCircle(
                color = Color(0xFF4F46E5),
                radius = radius,
                center = center,
                style = Stroke(width = minOf(size.width, size.height) * 0.035f)
            )
        }
    }
}