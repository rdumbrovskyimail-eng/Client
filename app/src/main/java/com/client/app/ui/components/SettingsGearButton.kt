package com.client.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Цветовые константы белого минимализма
private val ColorPillBackground = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFE2E8F0)
private val ColorIconBlack = Color(0xFF09090B)
private val ColorErrorBadge = Color(0xFFEF4444)

/**
 * Тактильный микрощелчок часового механизма для шестеренки настроек.
 */
private fun performEscapementHapticTick(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.70f, 0)
                        .compose()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(10L)
                }
            }
        }
    }
}

/**
 * Верхний правый контроллер настроек с анимированной черной шестеренкой и кнопкой системных логов.
 */
@Composable
fun SettingsGearButton(
    onOpenSettings: () -> Unit,
    onOpenLogs: () -> Unit,
    errorCount: Int = 0,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Накапливаемый угол вращения для исключения сброса фазы при повторных тапах
    var targetRotationAngle by remember { mutableFloatStateOf(0f) }

    // Пружинная физическая анимация вращения шестеренки на 360 градусов
    val animatedRotation by animateFloatAsState(
        targetValue = targetRotationAngle,
        animationSpec = spring(
            dampingRatio = 0.65f, // Средняя упругость с органичным овершутом
            stiffness = 160f      // Мягкая посадка механизма
        ),
        label = "gear_rotation_anim"
    )

    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Упругое сжатие капсулы при нажатии пальца
    val animatedScale by animateFloatAsState(
        targetValue = if (isPressed) 0.90f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "gear_press_scale"
    )

    Row(
        modifier = modifier
            .wrapContentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {

        // =====================================================================
        // 1. КОМПАНЬОН: МИНИМАЛИСТИЧНЫЙ ТЕРМИНАЛ ЛОГОВ (Белая капсула)
        // =====================================================================
        Box(
            modifier = Modifier
                .size(40.dp)
                .shadow(
                    elevation = 3.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x0A000000),
                    spotColor = Color(0x10000000)
                )
                .clip(CircleShape)
                .background(ColorPillBackground)
                .border(1.dp, ColorHairline, CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    performEscapementHapticTick(context)
                    onOpenLogs()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Terminal,
                contentDescription = "Системный лог",
                tint = ColorIconBlack,
                modifier = Modifier.size(17.dp)
            )

            // Светодиодный бейдж при наличии ошибок
            if (errorCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 4.dp, end = 4.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(ColorErrorBadge)
                )
            }
        }

        // =====================================================================
        // 2. ОСНОВНОЙ ЭЛЕМЕНТ: ЧЕРНАЯ ВРАЩАЮЩАЯСЯ ШЕСТЕРЕНКА НАСТРОЕК
        // =====================================================================
        Box(
            modifier = Modifier
                .size(46.dp)
                .scale(animatedScale)
                .shadow(
                    elevation = 4.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x0F000000),
                    spotColor = Color(0x16000000)
                )
                .clip(CircleShape)
                .background(ColorPillBackground)
                .border(1.dp, ColorHairline, CircleShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    performEscapementHapticTick(context)
                    // Добавляем ровно 360 градусов к текущему углу
                    targetRotationAngle += 360f

                    // Переход к окну настроек в фазе максимальной скорости вращения
                    coroutineScope.launch {
                        delay(210L)
                        onOpenSettings()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Настройки",
                tint = ColorIconBlack,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(animatedRotation)
            )
        }
    }
}