package com.client.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.client.app.session.LinkState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Белый минимализм: чернила + акценты Gemini (жёлтый — подключение, зелёный — эфир)
private val ColorPillBackground = Color(0xFFFFFFFF)
private val ColorHairlineDefault = Color(0xFFEBEBEB)
private val ColorHairlineLive = Color(0xFF34A853).copy(alpha = 0.45f)

private val ColorTextIdle = Color(0xFF0A0A0A)
private val ColorTextConnecting = Color(0xFF8A8A8F)
private val ColorTextLive = Color(0xFF0A0A0A)
private val ColorLedConnecting = Color(0xFFFBBC04)
private val ColorLedLive = Color(0xFF34A853)

/** Пульс светодиода только на время подключения: меняется лишь слой, без перекомпозиций. */
private fun Modifier.connectingPulse(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "connecting_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    graphicsLayer { alpha = pulse }
}

/**
 * Тактильный импульс щелчка X-Axis LRA для физического отклика клавиши сессии.
 */
private fun performTactileClick(context: Context, intensity: Float = 1.0f) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity, 0)
                        .compose()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    vibrator.vibrate(20L)
                }
            }
        }
    }
}

/**
 * Правая вертикальная полукруглая плашка «Start a session» с анимационной сменой цвета с черного на изумрудный.
 */
@Composable
fun SessionControlPill(
    linkState: LinkState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Анимация упругого механического толчка плашки влево при нажатии
    val horizontalBump = remember { Animatable(0f) }

    val isConnected = linkState == LinkState.LIVE
    val isConnecting = linkState == LinkState.CONNECTING || linkState == LinkState.RECONNECTING

    // Цвет надписи и светодиода по состоянию сессии (пульсирует только светодиод при подключении)
    val targetTextColor = when {
        isConnected -> ColorTextLive
        isConnecting -> ColorTextConnecting
        else -> ColorTextIdle
    }
    val animatedLedColor by animateColorAsState(
        targetValue = when {
            isConnected -> ColorLedLive
            isConnecting -> ColorLedConnecting
            else -> ColorTextIdle
        },
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "session_pill_led_color"
    )

    // Бесшовная интерполяция цвета надписи
    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "session_pill_text_color"
    )

    // Тонкая окантовка плашки
    val animatedBorderColor by animateColorAsState(
        targetValue = if (isConnected) ColorHairlineLive else ColorHairlineDefault,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "session_pill_border_color"
    )

    // Масштабирование индикаторного ореола при активном соединении
    val auraScale by animateFloatAsState(
        targetValue = if (isConnected) 1.25f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "aura_scale"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 54.dp)
                .offset { IntOffset(horizontalBump.value.roundToInt(), 0) }
                .width(46.dp)
                .height(236.dp)
                .shadow(
                    elevation = if (isConnected) 8.dp else 6.dp,
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp),
                    ambientColor = if (isConnected) Color(0x1434A853) else Color(0x0F000000),
                    spotColor = if (isConnected) Color(0x2434A853) else Color(0x1A000000)
                )
                .clip(RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp))
                .background(ColorPillBackground)
                .border(
                    width = 1.dp,
                    color = animatedBorderColor,
                    shape = RoundedCornerShape(topStart = 24.dp, bottomStart = 24.dp, topEnd = 0.dp, bottomEnd = 0.dp)
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    coroutineScope.launch {
                        performTactileClick(context, intensity = if (isConnected) 0.75f else 1.0f)

                        // Механический толчок плашки влево с упругим возвратом
                        launch {
                            horizontalBump.animateTo(
                                targetValue = (-5).dp.value,
                                animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                            )
                            horizontalBump.animateTo(
                                targetValue = 0f,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                )
                            )
                        }

                        onClick()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // =============================================================
                // ВЕРХНЯЯ СВЕТОДИОДНАЯ ИНДИКАТОРНАЯ ТОЧКА С ОРЕОЛОМ
                // =============================================================
                Box(
                    modifier = Modifier.size(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Внешний полупрозрачный ореол активности
                    if (isConnected || isConnecting) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .scale(auraScale)
                                .then(if (isConnecting) Modifier.connectingPulse() else Modifier)
                                .clip(CircleShape)
                                .background(animatedLedColor.copy(alpha = 0.24f))
                        )
                    }

                    // Центральный светодиодный кристалл
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .then(if (isConnecting) Modifier.connectingPulse() else Modifier)
                            .clip(CircleShape)
                            .background(animatedLedColor)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // =============================================================
                // ВЕРТИКАЛЬНЫЙ ТЕКСТ
                // =============================================================
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Надпись отражает действие: начать / идёт подключение / завершить
                    val characters = when {
                        isConnected -> listOf("E", "n", "d", "·", "s", "e", "s", "s", "i", "o", "n")
                        isConnecting -> listOf("C", "o", "n", "n", "e", "c", "t", "i", "n", "g")
                        else -> listOf("S", "t", "a", "r", "t", "·", "a", "·", "s", "e", "s", "s", "i", "o", "n")
                    }

                    characters.forEach { char ->
                        val isDot = char == "·"
                        Text(
                            text = char,
                            color = if (isDot) animatedTextColor.copy(alpha = 0.5f) else animatedTextColor,
                            fontSize = if (isDot) 10.sp else 11.5.sp,
                            fontWeight = if (char[0].isUpperCase()) FontWeight.Black else FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center,
                            lineHeight = 13.sp
                        )
                    }
                }
            }
        }
    }
}