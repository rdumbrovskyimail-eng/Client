// >>> FILE: app/src/main/java/com/client/app/ui/components/NeoVoiceVisualizer.kt
package com.client.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
    size: Dp = 170.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb_infinite")

    // Плавная цветовая интерполяция состояний
    val coreColor by animateColorAsState(
        targetValue = when {
            hasError -> Color(0xFFEF4444)      // Красный: ошибка
            isConnecting -> Color(0xFF818CF8)  // Индиго: установка связи
            !isConnected -> Color(0xFF52525B)  // Серый: сессия отключена
            isAiSpeaking -> Color(0xFF10B981)  // Изумрудный: говорит ассистент
            isMicActive -> Color(0xFF3B82F6)   // Синий: микрофон слушает
            else -> Color(0xFFF59E0B)          // Янтарный: режим ожидания речи
        },
        animationSpec = tween(350, easing = FastOutSlowInEasing),
        label = "color"
    )

    // Фоновое органическое «дыхание» сферы
    val breathing by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(if (isConnecting) 750 else 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breath"
    )

    // Непрерывное плавное вращение градиента плазмы (120 Гц)
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(14000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "plasma_rotation"
    )

    // Пружинное сглаживание аудио-амплитуды для исключения микро-дёрганий
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (isConnected) amplitude.coerceIn(0f, 1f) else 0f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = 850f
        ),
        label = "amplitude_spring"
    )

    val currentScale = if (isConnected) {
        (1f + animatedAmplitude * 0.85f) * breathing
    } else {
        breathing
    }

    val stateDescription = when {
        hasError -> "Ошибка соединения"
        isConnecting -> "Установка соединения"
        !isConnected -> "Сессия остановлена. Нажмите для подключения"
        isAiSpeaking -> "Ассистент говорит"
        isMicActive -> "Микрофон активен. Говорите"
        else -> "Ожидание"
    }

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = stateDescription
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val c = this.center
            val baseRadius = this.size.minDimension / 3.4f
            val r = (baseRadius * currentScale).coerceAtMost(this.size.minDimension / 2.05f)

            // 1. Внешняя интерференционная аура (Bloom)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        coreColor.copy(alpha = if (isConnected) 0.35f else 0.15f),
                        coreColor.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = (r * 1.35f).coerceAtLeast(1f)
                ),
                radius = r * 1.35f,
                center = c
            )

            // 2. Реактивное внешнее акустическое кольцо
            if (isConnected) {
                drawCircle(
                    color = coreColor.copy(alpha = (0.3f + animatedAmplitude * 0.5f).coerceIn(0f, 0.8f)),
                    radius = r * 1.08f,
                    center = c,
                    style = Stroke(width = (1.5f + animatedAmplitude * 2f).dp.toPx())
                )
            }

            // 3. Центральное плазменное тело с плавным вращением
            rotate(degrees = rotation, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            coreColor,
                            coreColor.copy(alpha = 0.55f),
                            coreColor.copy(alpha = 0.85f),
                            coreColor
                        ),
                        center = c
                    ),
                    radius = r * 0.88f,
                    center = c
                )
            }

            // 4. Спекулярный световой блик для 3D глубины стекла
            val specularOffset = Offset(c.x - r * 0.28f, c.y - r * 0.28f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (isConnected) 0.45f else 0.20f),
                        Color.White.copy(alpha = 0.05f),
                        Color.Transparent
                    ),
                    center = specularOffset,
                    radius = (r * 0.45f).coerceAtLeast(1f)
                ),
                radius = r * 0.45f,
                center = specularOffset
            )
        }
    }
}