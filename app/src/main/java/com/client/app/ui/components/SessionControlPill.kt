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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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

// Палитра белого минимализма для кнопки сессии
private val ColorPillBackground = Color(0xFFFFFFFF)
private val ColorHairlineDefault = Color(0xFFE2E8F0)
private val ColorHairlineLive = Color(0xFF10B981).copy(alpha = 0.40f)

private val ColorTextIdle = Color(0xFF09090B)
private val ColorTextConnecting = Color(0xFFF59E0B)
private val ColorTextLive = Color(0xFF10B981)

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

    // Анимационное дыхание для фазы подключения
    val infiniteTransition = rememberInfiniteTransition(label = "connecting_pulse")
    val connectingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    // Целевой цвет в зависимости от состояния жизненного цикла сессии
    val targetTextColor = when {
        isConnected -> ColorTextLive
        isConnecting -> ColorTextConnecting.copy(alpha = connectingAlpha)
        else -> ColorTextIdle
    }

    // Бесшовная интерполяция цвета надписи (из черного в изумрудно-зеленый)
    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "session_pill_text_color"
    )

    // Тонкая окантовка плашки (становится изумрудной при активном дуплексе)
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
                    ambientColor = if (isConnected) Color(0x1410B981) else Color(0x0F000000),
                    spotColor = if (isConnected) Color(0x2410B981) else Color(0x1A000000)
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
                                .clip(CircleShape)
                                .background(animatedTextColor.copy(alpha = 0.22f))
                        )
                    }

                    // Центральный светодиодный кристалл
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(animatedTextColor)
                    )
                }

                Spacer(Modifier.height(4.dp))

                // =============================================================
                // ВЕРТИКАЛЬНЫЙ ТЕКСТ "S-t-a-r-t · a · s-e-s-s-i-o-n"
                // =============================================================
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    // Структура символов с разделителями слов
                    val characters = listOf(
                        "S", "t", "a", "r", "t",
                        "·",
                        "a",
                        "·",
                        "s", "e", "s", "s", "i", "o", "n"
                    )

                    characters.forEach { char ->
                        val isDot = char == "·"
                        Text(
                            text = char,
                            color = if (isDot) animatedTextColor.copy(alpha = 0.5f) else animatedTextColor,
                            fontSize = if (isDot) 10.sp else 11.5.sp,
                            fontWeight = if (char == "S" || char == "s") FontWeight.Black else FontWeight.Bold,
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