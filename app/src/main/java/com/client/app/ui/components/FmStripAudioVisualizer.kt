package com.client.app.ui.components

import androidx.compose.animation.core.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.client.app.audio.NativeAudioEngine
import com.client.app.session.LinkState
import com.client.app.session.SessionState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Внутренний контейнер предвыделенных путей и буферов для исключения
 * выделений памяти (Zero-Allocation) в фазе DrawScope на 120 FPS.
 */
private class FmStripRenderCache {
    val wavePath1 = Path()
    val wavePath2 = Path()
    val wavePath3 = Path()
    val smoothedBands = FloatArray(5) { 0f }
    val smoothedHeights = FloatArray(TOTAL_TICKS) { 0f }
    var previousOutLevel = 0f
    var previousMicLevel = 0f
}

private const val TOTAL_TICKS = 65
private const val STEPS_PER_WAVE = 100

// Цветовая палитра белого минимализма
private val ColorSurfaceBase = Color(0xFFFFFFFF)
private val ColorHairlineBorder = Color(0xFFE2E8F0)
private val ColorTickMajor = Color(0xFF09090B)
private val ColorTickIntermediate = Color(0xFF27272A)
private val ColorTickMinor = Color(0xFF94A3B8).copy(alpha = 0.55f)
private val ColorNeedleIdle = Color(0xFF09090B)
private val ColorNeedleActive = Color(0xFF10B981)

private val ColorWaveDeep = Color(0xFFE2E8F0)
private val ColorWaveMid = Color(0xFFCBD5E1)
private val ColorWaveAir = Color(0xFF94A3B8).copy(alpha = 0.50f)
private val ColorTypography = Color(0xFFA1A1AA)
private val ColorStatusSpeaking = Color(0xFF09090B)
private val ColorStatusLive = Color(0xFF10B981)
private val ColorStatusError = Color(0xFFEF4444)

/**
 * Аналоговая лента звука (FM Ribbon Strip Visualizer) уровня Dieter Rams / Teenage Engineering.
 * Размещается в верхней трети экрана на отметке ~1/5 высоты.
 */
@Composable
fun FmStripAudioVisualizer(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    modifier: Modifier = Modifier
) {
    val micLevel by nativeEngine.micLevel.collectAsState()
    val outLevel by nativeEngine.outLevel.collectAsState()

    // Плавный генератор фазы монотонного времени для непрерывного движения волн
    val infiniteTransition = rememberInfiniteTransition(label = "fm_ribbon_oscillator")
    val phaseTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2.0 * PI * 100.0).toFloat(), // 100 полных оборотов фазы
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 120000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase_motion"
    )

    // Кэш для отрисовки графики без сборщика мусора
    val renderCache = remember { FmStripRenderCache() }

    val isLive = state.link == LinkState.LIVE
    val isSpeaking = state.isAiSpeaking
    val isListening = state.isMicActive
    val hasError = state.error != null

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(78.dp)
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(22.dp),
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x14000000)
            )
            .clip(RoundedCornerShape(22.dp))
            .background(ColorSurfaceBase)
            .border(1.dp, ColorHairlineBorder, RoundedCornerShape(22.dp)),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            val canvasW = size.width
            val canvasH = size.height
            val centerY = canvasH / 2f

            // Извлечение текущего БПФ-снимка из ядра C++
            val spectrum = nativeEngine.spectrumUniforms.get()
            val rawSubBass = spectrum.getOrElse(0) { 0f }
            val rawBass = spectrum.getOrElse(1) { 0f }
            val rawMid = spectrum.getOrElse(2) { 0f }
            val rawPresence = spectrum.getOrElse(3) { 0f }
            val rawAir = spectrum.getOrElse(4) { 0f }

            // IEC 60268-10 баллистика сглаживания спектральных полос
            updateSpectralBallistics(
                cache = renderCache,
                rawSubBass = rawSubBass,
                rawBass = rawBass,
                rawMid = rawMid,
                rawPresence = rawPresence,
                rawAir = rawAir,
                currentOut = outLevel,
                currentMic = micLevel
            )

            // Расчет активности голосового тракта
            val effectiveVoiceActivity = when {
                isSpeaking -> maxOf(renderCache.previousOutLevel * 1.55f, 0.22f)
                isListening -> maxOf(renderCache.previousMicLevel * 1.65f, 0.16f)
                isLive -> 0.08f
                else -> 0.03f
            }

            // 1. Отрисовка трех плавающих серо-белых аналоговых волн
            renderFluidWaveforms(
                cache = renderCache,
                canvasW = canvasW,
                canvasH = canvasH,
                centerY = centerY,
                phase = phaseTime,
                voiceActivity = effectiveVoiceActivity
            )

            // 2. Отрисовка 65 черных делений шкалы FM-тюнера со спектральной модуляцией
            renderTunerGraticule(
                cache = renderCache,
                canvasW = canvasW,
                canvasH = canvasH,
                centerY = centerY,
                voiceActivity = effectiveVoiceActivity,
                isLive = isLive
            )

            // 3. Центральная визирная игла со стеклянной бусиной-указателем
            renderNeedleCursor(
                canvasW = canvasW,
                canvasH = canvasH,
                centerY = centerY,
                isLive = isLive
            )
        }

        // Верхний ряд: метки радиочастот в стиле аналоговой шкалы Braun
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "88.0",
                color = ColorTypography,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "92.0",
                color = ColorTypography.copy(alpha = 0.7f),
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "96.0",
                color = ColorTypography.copy(alpha = 0.7f),
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "100.0",
                color = ColorTypography.copy(alpha = 0.7f),
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "104.0",
                color = ColorTypography.copy(alpha = 0.7f),
                fontSize = 7.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "108.0",
                color = ColorTypography,
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp
            )
        }

        // Нижний ряд: служебные субтитры аналогового тракта
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "FM SCALE",
                color = ColorTypography,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = when {
                    hasError -> "AUDIO FAULT DETECTED"
                    isSpeaking -> "GEMINI LIVE • 24.0 kHz SPEECH"
                    isListening -> "MICROPHONE • 16.0 kHz INPUT"
                    isLive -> "DUPLEX CONNECTED • WCD9385"
                    else -> "FM TUNER READY"
                },
                color = when {
                    hasError -> ColorStatusError
                    isSpeaking -> ColorStatusSpeaking
                    isLive -> ColorStatusLive
                    else -> ColorTypography
                },
                fontSize = 8.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isSpeaking || isLive) FontWeight.Bold else FontWeight.Medium,
                letterSpacing = 0.8.sp
            )

            Text(
                text = "S23 ULTRA",
                color = ColorTypography,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

/**
 * Обновляет коэффициенты сглаживания по стандарту IEC 60268-10 PPM:
 * Быстрая атака (0.75) при резком нарастании энергии звука и медленный
 * спад (0.12) для устранения микрорыскания делений при естественных паузах речи.
 */
private fun updateSpectralBallistics(
    cache: FmStripRenderCache,
    rawSubBass: Float,
    rawBass: Float,
    rawMid: Float,
    rawPresence: Float,
    rawAir: Float,
    currentOut: Float,
    currentMic: Float
) {
    val attack = 0.72f
    val decay = 0.14f

    fun filter(target: Float, current: Float): Float {
        return if (target > current) {
            current + attack * (target - current)
        } else {
            current - decay * (current - target)
        }.coerceIn(0f, 1f)
    }

    cache.smoothedBands[0] = filter(rawSubBass, cache.smoothedBands[0])
    cache.smoothedBands[1] = filter(rawBass, cache.smoothedBands[1])
    cache.smoothedBands[2] = filter(rawMid, cache.smoothedBands[2])
    cache.smoothedBands[3] = filter(rawPresence, cache.smoothedBands[3])
    cache.smoothedBands[4] = filter(rawAir, cache.smoothedBands[4])

    cache.previousOutLevel = filter(currentOut, cache.previousOutLevel)
    cache.previousMicLevel = filter(currentMic, cache.previousMicLevel)
}

/**
 * Отрисовывает три серо-белые математические аналоговые волны.
 * Волны непрерывно плавают с фазовым сдвигом и модулируются речью.
 */
private fun DrawScope.renderFluidWaveforms(
    cache: FmStripRenderCache,
    canvasW: Float,
    canvasH: Float,
    centerY: Float,
    phase: Float,
    voiceActivity: Float
) {
    val path1 = cache.wavePath1
    val path2 = cache.wavePath2
    val path3 = cache.wavePath3

    path1.reset()
    path2.reset()
    path3.reset()

    val subBass = cache.smoothedBands[0]
    val bass = cache.smoothedBands[1]
    val mid = cache.smoothedBands[2]
    val air = cache.smoothedBands[4]

    val ampBase1 = (canvasH * 0.26f) * voiceActivity * (1.0f + bass * 0.85f + subBass * 0.40f)
    val ampBase2 = (canvasH * 0.19f) * voiceActivity * (1.0f + mid * 1.10f)
    val ampBase3 = (canvasH * 0.13f) * voiceActivity * (1.0f + air * 1.25f)

    for (step in 0..STEPS_PER_WAVE) {
        val frac = step.toFloat() / STEPS_PER_WAVE.toFloat()
        val x = frac * canvasW

        // Оконная функция Хэннинга для плавного сведения амплитуды в 0 на границах контейнера
        val envelope = sin(frac * PI).toFloat()

        val normPhase = frac * 4.0 * PI

        val y1 = centerY + (sin(normPhase + phase * 1.15) * ampBase1 * envelope).toFloat()
        val y2 = centerY + (sin(normPhase * 1.55 - phase * 0.85) * ampBase2 * envelope).toFloat()
        val y3 = centerY + (sin(normPhase * 2.25 + phase * 1.45) * ampBase3 * envelope).toFloat()

        if (step == 0) {
            path1.moveTo(x, y1)
            path2.moveTo(x, y2)
            path3.moveTo(x, y3)
        } else {
            path1.lineTo(x, y1)
            path2.lineTo(x, y2)
            path3.lineTo(x, y3)
        }
    }

    drawPath(path = path1, color = ColorWaveDeep, style = Stroke(width = 2.4f, cap = StrokeCap.Round))
    drawPath(path = path2, color = ColorWaveMid, style = Stroke(width = 1.8f, cap = StrokeCap.Round))
    drawPath(path = path3, color = ColorWaveAir, style = Stroke(width = 1.2f, cap = StrokeCap.Round))
}

/**
 * Отрисовывает 65 вертикальных делений FM-шкалы с динамическим откликом высоты
 * на спектральную плотность и речь.
 */
private fun DrawScope.renderTunerGraticule(
    cache: FmStripRenderCache,
    canvasW: Float,
    canvasH: Float,
    centerY: Float,
    voiceActivity: Float,
    isLive: Boolean
) {
    val subBass = cache.smoothedBands[0]
    val bass = cache.smoothedBands[1]
    val mid = cache.smoothedBands[2]
    val presence = cache.smoothedBands[3]
    val air = cache.smoothedBands[4]

    val tickSpacing = canvasW / (TOTAL_TICKS + 1)
    val maxAllowedHalfH = canvasH * 0.44f

    for (i in 0 until TOTAL_TICKS) {
        val tickIndex = i + 1
        val tickX = tickIndex * tickSpacing

        val isCenter = (tickIndex == (TOTAL_TICKS / 2) + 1)
        val isMajor = (tickIndex % 5 == 1)
        val isIntermediate = (tickIndex % 5 == 3)

        // Привязка деления к соответствующей полосе частот БПФ
        val spectralEnergy = when {
            tickIndex <= 14 -> subBass * 0.85f + bass * 0.65f
            tickIndex in 15..34 -> mid * 1.15f
            tickIndex in 35..50 -> presence * 1.20f
            else -> air * 1.30f
        }

        val baseHeight = when {
            isMajor -> 18.0f
            isIntermediate -> 13.5f
            else -> 9.5f
        }

        // Динамический расчет высоты деления
        val targetHeight = baseHeight * (1.0f + (voiceActivity * 1.65f + spectralEnergy * 1.75f))
        val currentHeight = cache.smoothedHeights[i]

        // Локальное экспоненциальное сглаживание деления
        val smoothedH = currentHeight + 0.35f * (targetHeight - currentHeight)
        cache.smoothedHeights[i] = smoothedH

        val halfH = (smoothedH / 2f).coerceIn(4.0f, maxAllowedHalfH)

        val tickColor = when {
            isCenter -> if (isLive) ColorNeedleActive else ColorTickMajor
            isMajor -> ColorTickMajor
            isIntermediate -> ColorTickIntermediate
            else -> ColorTickMinor
        }

        val strokeWidth = when {
            isCenter -> 2.2f
            isMajor -> 1.6f
            isIntermediate -> 1.2f
            else -> 1.0f
        }

        drawLine(
            color = tickColor,
            start = Offset(tickX, centerY - halfH),
            end = Offset(tickX, centerY + halfH),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Отрисовывает центральную визирную нить настройки с индикаторной точкой.
 */
private fun DrawScope.renderNeedleCursor(
    canvasW: Float,
    canvasH: Float,
    centerY: Float,
    isLive: Boolean
) {
    val centerX = canvasW / 2f
    val needleColor = if (isLive) ColorNeedleActive else ColorNeedleIdle

    // Тонкая вертикальная визирная струна настройки
    drawLine(
        color = needleColor,
        start = Offset(centerX, 2f),
        end = Offset(centerX, canvasH - 2f),
        strokeWidth = 2.0f,
        cap = StrokeCap.Round
    )

    // Верхняя индикаторная бусина
    drawCircle(
        color = needleColor,
        radius = 3.6f,
        center = Offset(centerX, 6f)
    )

    // Нижняя фиксирующая микроточка
    drawCircle(
        color = needleColor,
        radius = 2.2f,
        center = Offset(centerX, canvasH - 6f)
    )
}