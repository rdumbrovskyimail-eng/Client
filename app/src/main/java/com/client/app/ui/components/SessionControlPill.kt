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

// Чёрная тема: в покое — светлая капсула-призыв, в эфире — графит (жёлтый — подключение, зелёный — эфир)
private val ColorPillIdle = Color(0xFFF4F4F5)
private val ColorPillActive = Color(0xFF141416)
private val ColorHairlineDefault = Color(0xFF2A2A2E)
private val ColorHairlineLive = Color(0xFF34A853).copy(alpha = 0.55f)

private val ColorTextIdle = Color(0xFF0A0A0A)
private val ColorTextConnecting = Color(0xFFA1A1AA)
private val ColorTextLive = Color(0xFFF4F4F5)
private val ColorLedConnecting = Color(0xFFFBBC04)
private val ColorLedLive = Color(0xFF34A853)

/** Пульс светодиода только на время подключения: меняется лишь слой, без лишних перекомпозиций. */
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
 * Горизонтальная полукруглая капсула управления голосовой сессией (Start Session / End Session).
 * Располагается под FM-лентой звука, снабжена светодиодом состояния и упругой механической отдачей.
 */
@Composable
fun SessionControlPill(
    linkState: LinkState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Пружинная механическая отдача при нажатии (легкий толчок вниз)
    val verticalBump = remember { Animatable(0f) }

    val isConnected = linkState == LinkState.LIVE
    val isConnecting = linkState == LinkState.CONNECTING || linkState == LinkState.RECONNECTING

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

    val animatedTextColor by animateColorAsState(
        targetValue = targetTextColor,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "session_pill_text_color"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isConnected) ColorHairlineLive else ColorHairlineDefault,
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "session_pill_border_color"
    )

    val animatedPillColor by animateColorAsState(
        targetValue = if (isConnected || isConnecting) ColorPillActive else ColorPillIdle,
        animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing),
        label = "session_pill_background"
    )

    val auraScale by animateFloatAsState(
        targetValue = if (isConnected) 1.25f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "aura_scale"
    )

    val pillShape = RoundedCornerShape(26.dp)

    Box(
        modifier = modifier
            .height(52.dp)
            .offset { IntOffset(0, verticalBump.value.roundToInt()) }
            .shadow(
                elevation = if (isConnected) 8.dp else 5.dp,
                shape = pillShape,
                ambientColor = if (isConnected) Color(0x1434A853) else Color(0x0F000000),
                spotColor = if (isConnected) Color(0x2434A853) else Color(0x1A000000)
            )
            .clip(pillShape)
            .background(animatedPillColor)
            .border(
                width = 1.dp,
                color = animatedBorderColor,
                shape = pillShape
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                coroutineScope.launch {
                    performTactileClick(context, intensity = if (isConnected) 0.75f else 1.0f)

                    launch {
                        verticalBump.animateTo(
                            targetValue = 4.dp.value,
                            animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                        )
                        verticalBump.animateTo(
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
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // Светодиодный индикатор слева с мягким ореолом
            Box(
                modifier = Modifier.size(16.dp),
                contentAlignment = Alignment.Center
            ) {
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

                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .then(if (isConnecting) Modifier.connectingPulse() else Modifier)
                        .clip(CircleShape)
                        .background(animatedLedColor)
                )
            }

            Spacer(Modifier.width(12.dp))

            // Горизонтальный текст действия
            val label = when {
                isConnected -> "END SESSION"
                isConnecting -> "CONNECTING..."
                else -> "START SESSION"
            }

            Text(
                text = label,
                color = animatedTextColor,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.2.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}