package com.client.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.client.app.audio.NativeAudioEngine
import com.client.app.session.LinkState
import com.client.app.session.SessionState
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin

private const val TOTAL_TICKS = 65

// Белый минимализм: чернила, бумага, волосяные линии
private val ColorSurface = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFEBEBEB)
private val ColorInk = Color(0xFF0A0A0A)
private val ColorInkSoft = Color(0xFF262626)
private val ColorInkMinor = Color(0xFFB8B8BD)
private val ColorLabel = Color(0xFFA3A3A8)

// Четыре цвета Gemini — единственные акценты интерфейса
private val GeminiBlue = Color(0xFF4285F4)
private val GeminiRed = Color(0xFFEA4335)
private val GeminiYellow = Color(0xFFFBBC04)
private val GeminiGreen = Color(0xFF34A853)
private val GeminiPalette = arrayOf(GeminiBlue, GeminiRed, GeminiYellow, GeminiGreen)
private val GeminiSweep = listOf(GeminiBlue, GeminiRed, GeminiYellow, GeminiGreen, GeminiBlue)

/**
 * Состояние отрисовки без аллокаций в кадре: сглаженные полосы спектра, высоты делений,
 * «присутствие» голоса и время кадра для баллистики, не зависящей от частоты экрана (60/120 Гц).
 */
private class StripRenderState {
    val bands = FloatArray(5)
    val heights = FloatArray(TOTAL_TICKS)
    var outLevel = 0f
    var micLevel = 0f
    var aiPresence = 0f
    var userPresence = 0f
    var lastFrameNanos = 0L
    var timeSec = 0f
}

/**
 * FM-шкала голоса: 65 чёрных делений на белом листе.
 * Во время речи внутри делений загораются микролинии цветов Gemini, бегущие по шкале,
 * с мягким цветным ореолом. Речь модели и речь пользователя различаются направлением бега.
 * Анимация работает только в сессии (и полторы секунды затухания после неё) — в покое батарея не тратится.
 */
@Composable
fun FmStripAudioVisualizer(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    modifier: Modifier = Modifier
) {
    val render = remember { StripRenderState() }
    val frameNanos = remember { mutableLongStateOf(0L) }

    val sessionActive = state.link != LinkState.IDLE
    val isLive = state.link == LinkState.LIVE
    val isSpeaking = state.isAiSpeaking
    val isListening = state.isMicActive
    val hasError = state.error != null

    // Кадровый цикл только пока идёт сессия (+ затухание); чтение времени — в фазе отрисовки, без перекомпозиции
    LaunchedEffect(sessionActive) {
        val stopAt = if (sessionActive) Long.MAX_VALUE else System.nanoTime() + 1_500_000_000L
        while (isActive) {
            val t = withFrameNanos { it }
            frameNanos.longValue = t
            if (t >= stopAt) break
        }
    }

    val shape = RoundedCornerShape(24.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(84.dp)
            .shadow(
                elevation = 5.dp,
                shape = shape,
                ambientColor = Color(0x0A000000),
                spotColor = Color(0x12000000)
            )
            .clip(shape)
            .background(ColorSurface)
            .border(1.dp, ColorHairline, shape),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp)
        ) {
            val now = frameNanos.longValue
            val dt = if (render.lastFrameNanos == 0L || now <= render.lastFrameNanos) {
                1f / 120f
            } else {
                ((now - render.lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            render.lastFrameNanos = now
            render.timeSec += dt

            updateBallistics(
                render = render,
                dt = dt,
                spectrum = nativeEngine.spectrumUniforms.get(),
                out = nativeEngine.outLevel.value,
                mic = nativeEngine.micLevel.value,
                isSpeaking = isSpeaking,
                isListening = isListening && isLive
            )

            drawVoiceStrip(render = render, dt = dt, isLive = isLive)
            drawNeedle(render = render, isLive = isLive)
        }

        // Верхний ряд: шкала частот в духе аналоговых тюнеров Braun
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 5.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf("88", "92", "96", "100", "104", "108").forEachIndexed { index, label ->
                val edge = index == 0 || index == 5
                Text(
                    text = label,
                    color = if (edge) ColorLabel else ColorLabel.copy(alpha = 0.65f),
                    fontSize = 7.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (edge) FontWeight.SemiBold else FontWeight.Medium,
                    letterSpacing = 0.4.sp
                )
            }
        }

        // Нижний ряд: состояние тракта
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 5.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FM",
                color = ColorLabel,
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
            Text(
                text = when {
                    hasError -> "AUDIO FAULT"
                    isSpeaking -> "GEMINI · SPEAKING"
                    isLive && isListening -> "LISTENING"
                    isLive -> "LIVE"
                    sessionActive -> "CONNECTING"
                    else -> "READY"
                },
                color = when {
                    hasError -> GeminiRed
                    isSpeaking -> ColorInk
                    isLive -> ColorInk
                    else -> ColorLabel
                },
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSpeaking || isLive) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 1.2.sp
            )
            Text(
                text = "S23",
                color = ColorLabel,
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp
            )
        }
    }
}

/** Экспоненциальная баллистика с постоянными времени: одинаково на 60 и 120 Гц. */
private fun follow(current: Float, target: Float, dt: Float, attackSec: Float, releaseSec: Float): Float {
    val tau = if (target > current) attackSec else releaseSec
    val k = 1f - exp(-dt / tau)
    return (current + (target - current) * k).coerceIn(0f, 1f)
}

private fun updateBallistics(
    render: StripRenderState,
    dt: Float,
    spectrum: FloatArray,
    out: Float,
    mic: Float,
    isSpeaking: Boolean,
    isListening: Boolean
) {
    for (b in 0 until 5) {
        render.bands[b] = follow(render.bands[b], spectrum.getOrElse(b) { 0f }, dt, 0.030f, 0.160f)
    }
    render.outLevel = follow(render.outLevel, out, dt, 0.025f, 0.180f)
    render.micLevel = follow(render.micLevel, mic, dt, 0.025f, 0.180f)

    // «Присутствие» голоса управляет яркостью цветных микролиний (плавное включение и гашение)
    val aiTarget = if (isSpeaking) (0.35f + render.outLevel * 3.0f).coerceAtMost(1f) else 0f
    val userTarget = if (isListening) (render.micLevel * 4.0f).coerceAtMost(1f) else 0f
    render.aiPresence = follow(render.aiPresence, aiTarget, dt, 0.060f, 0.420f)
    render.userPresence = follow(render.userPresence, userTarget, dt, 0.050f, 0.320f)
}

/** Цвет Gemini в точке p∈[0,1): плавный переход синий → красный → жёлтый → зелёный → синий. */
private fun geminiColorAt(p: Float): Color {
    val x = (p - floor(p)) * 4f
    val i = x.toInt().coerceIn(0, 3)
    val f = x - i
    val s = f * f * (3f - 2f * f)
    return lerp(GeminiPalette[i], GeminiPalette[(i + 1) % 4], s)
}

private fun DrawScope.drawVoiceStrip(render: StripRenderState, dt: Float, isLive: Boolean) {
    val w = size.width
    val h = size.height
    val cy = h / 2f
    val spacing = w / (TOTAL_TICKS + 1)
    val t = render.timeSec

    val majorW = 2.25.dp.toPx()
    val midW = 1.75.dp.toPx()
    val minorW = 1.5.dp.toPx()
    val coreMinW = 0.8.dp.toPx()

    val ai = render.aiPresence
    val user = render.userPresence
    val presence = maxOf(ai, user)
    // Речь модели бежит вправо, речь пользователя — влево
    val flow = if (ai >= user) t * 0.22f else -t * 0.22f

    val activity = maxOf(
        render.outLevel * 1.9f,
        render.micLevel * 2.2f,
        if (isLive) 0.05f else 0.02f
    ).coerceAtMost(1f)

    for (i in 0 until TOTAL_TICKS) {
        val tick = i + 1
        val x = tick * spacing
        val isCenter = tick == TOTAL_TICKS / 2 + 1
        if (isCenter) continue // визирная нить рисуется отдельно
        val isMajor = tick % 5 == 1
        val isMid = tick % 5 == 3

        val bandEnergy = when {
            tick <= 14 -> render.bands[0] * 0.85f + render.bands[1] * 0.65f
            tick <= 34 -> render.bands[2] * 1.15f
            tick <= 50 -> render.bands[3] * 1.20f
            else -> render.bands[4] * 1.30f
        }

        // Органическое «дыхание» шкалы: два медленных поля, без шума и дёрганья
        val organic = 0.5f + 0.5f * sin(i * 0.61f + t * 6.2f) * cos(i * 0.23f - t * 2.7f)
        val level = (activity * (0.55f + 0.45f * organic) + bandEnergy * 0.8f).coerceIn(0f, 1f)

        val base = when {
            isMajor -> 0.30f
            isMid -> 0.22f
            else -> 0.15f
        }
        val targetH = base + (0.94f - base) * level
        val smoothed = follow(render.heights[i], targetH, dt, 0.022f, 0.120f)
        render.heights[i] = smoothed

        val halfH = (smoothed * h / 2f).coerceAtLeast(2.dp.toPx())
        val barW = when {
            isMajor -> majorW
            isMid -> midW
            else -> minorW
        }
        val barColor = when {
            isMajor -> ColorInk
            isMid -> ColorInkSoft
            else -> ColorInkMinor
        }

        val top = Offset(x, cy - halfH)
        val bottom = Offset(x, cy + halfH)

        if (presence > 0.01f) {
            val c = geminiColorAt(i / TOTAL_TICKS.toFloat() * 1.5f + flow)
            val coreAlpha = (presence * (0.55f + 0.9f * level)).coerceIn(0f, 1f)

            // Мягкий цветной ореол вокруг деления (яркость на белом без потери контраста)
            drawLine(
                color = c.copy(alpha = 0.16f * coreAlpha),
                start = top,
                end = bottom,
                strokeWidth = barW * 3.4f,
                cap = StrokeCap.Round
            )

            // Чёрное (или серое) деление — каркас шкалы
            drawLine(color = barColor, start = top, end = bottom, strokeWidth = barW, cap = StrokeCap.Round)

            // Цветная микролиния внутри деления: чёрные «капы» сверху и снизу, светящаяся сердцевина
            val inset = (barW * 1.1f).coerceAtMost(halfH * 0.5f)
            if (halfH - inset > 1f) {
                drawLine(
                    color = c.copy(alpha = coreAlpha),
                    start = Offset(x, cy - halfH + inset),
                    end = Offset(x, cy + halfH - inset),
                    strokeWidth = maxOf(barW * 0.46f, coreMinW),
                    cap = StrokeCap.Round
                )
            }
        } else {
            drawLine(color = barColor, start = top, end = bottom, strokeWidth = barW, cap = StrokeCap.Round)
        }
    }
}

private fun DrawScope.drawNeedle(render: StripRenderState, isLive: Boolean) {
    val cx = size.width / 2f
    val h = size.height
    val needleW = 1.5.dp.toPx()

    drawLine(
        color = ColorInk,
        start = Offset(cx, 1.dp.toPx()),
        end = Offset(cx, h - 1.dp.toPx()),
        strokeWidth = needleW,
        cap = StrokeCap.Round
    )

    val beadRadius = 3.2.dp.toPx()
    val beadCenter = Offset(cx, beadRadius + 0.5.dp.toPx())
    if (isLive) {
        // Бусина-индикатор: вращающийся спектр Gemini
        rotate(degrees = (render.timeSec * 90f) % 360f, pivot = beadCenter) {
            drawCircle(
                brush = Brush.sweepGradient(GeminiSweep, center = beadCenter),
                radius = beadRadius,
                center = beadCenter
            )
        }
        drawCircle(color = ColorSurface, radius = beadRadius * 0.38f, center = beadCenter)
    } else {
        drawCircle(color = ColorInk, radius = beadRadius, center = beadCenter)
    }

    drawCircle(
        color = ColorInk,
        radius = 1.8.dp.toPx(),
        center = Offset(cx, h - 1.8.dp.toPx() - 0.5.dp.toPx())
    )
}