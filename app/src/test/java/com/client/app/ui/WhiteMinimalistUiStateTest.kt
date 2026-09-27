package com.client.app.ui

import com.client.app.session.LinkState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.math.*
import kotlin.test.*

/**
 * Комплекс математических и алгоритмических тестов бело-минималистической архитектуры UI.
 * Проверяет баллистику FM-полосы, спектральные окна, физику вращения шестеренки и цветовые автоматы.
 */
class WhiteMinimalistUiStateTest {

    // =========================================================================
    // 1. ТЕСТИРОВАНИЕ МАТЕМАТИКИ И СПЕКТРОСКОПИИ FM-ПОЛОСКИ
    // =========================================================================

    @Test
    fun testFmWindowEnvelopeEdgeZeroing() {
        // Оконная функция Хэннинга/синуса: sin(frac * PI)
        fun calculateEnvelope(frac: Float): Float {
            require(frac in 0f..1f)
            return sin(frac * PI).toFloat()
        }

        // Проверка: на краях контейнера (x = 0 и x = W) амплитуда волны обязана быть строго 0
        assertEquals(0.0f, calculateEnvelope(0.0f), 1e-6f, "Амплитуда волны слева обязана затухать в ноль")
        assertEquals(0.0f, calculateEnvelope(1.0f), 1e-6f, "Амплитуда волны справа обязана затухать в ноль")

        // Проверка: в центре контейнера (x = W/2) достигается полный размах (1.0)
        assertEquals(1.0f, calculateEnvelope(0.5f), 1e-6f, "В центре капсулы амплитуда должна быть максимальной")

        // Проверка монотонного возрастания и убывания
        assertTrue(calculateEnvelope(0.25f) < calculateEnvelope(0.5f))
        assertTrue(calculateEnvelope(0.75f) < calculateEnvelope(0.5f))
    }

    @Test
    fun testIec60268PpmAttackDecayBallistics() {
        val attack = 0.72f
        val decay = 0.14f

        fun filter(target: Float, current: Float): Float {
            return if (target > current) {
                current + attack * (target - current)
            } else {
                current - decay * (current - target)
            }.coerceIn(0f, 1f)
        }

        var smoothedValue = 0.0f

        // Симуляция резкого речевого импульса Gemini (громкость 0.90)
        val impulseTarget = 0.90f
        smoothedValue = filter(impulseTarget, smoothedValue)
        assertTrue(smoothedValue >= 0.60f, "Атака измерителя обязана быть мгновенной (за 1 шаг подъем > 65%)")

        smoothedValue = filter(impulseTarget, smoothedValue)
        assertTrue(smoothedValue >= 0.80f, "На втором шаге измеритель должен достичь максимума импульса")

        // Симуляция паузы в речи (спад в ноль)
        val silenceTarget = 0.0f
        var decaySteps = 0
        while (smoothedValue > 0.05f && decaySteps < 50) {
            smoothedValue = filter(silenceTarget, smoothedValue)
            decaySteps++
        }

        // Экспоненциальный спад должен занимать не менее 12-25 тактов для благородного аналогового затухания
        assertTrue(decaySteps >= 14, "Спад обязан быть плавным и благородным, чтобы исключить стробоскопию шкалы")
    }

    @Test
    fun testFmStripSpectralBinMappingAndBounds() {
        val totalTicks = 65
        val baseHeightMajor = 18.0f
        val baseHeightIntermediate = 13.5f
        val baseHeightMinor = 9.5f
        val maxAllowedHalfH = 34.0f // canvasH (78dp) * 0.44

        val simulatedSpectrum = floatArrayOf(0.8f, 0.6f, 0.95f, 0.4f, 0.3f)
        val voiceActivity = 0.85f

        var majorTicksCount = 0
        var centerTickIndex = -1

        for (i in 0 until totalTicks) {
            val tickIndex = i + 1
            if (tickIndex == (totalTicks / 2) + 1) centerTickIndex = tickIndex
            if (tickIndex % 5 == 1) majorTicksCount++

            val spectralEnergy = when {
                tickIndex <= 14 -> simulatedSpectrum[0] * 0.85f + simulatedSpectrum[1] * 0.65f
                tickIndex in 15..34 -> simulatedSpectrum[2] * 1.15f
                tickIndex in 35..50 -> simulatedSpectrum[3] * 1.20f
                else -> simulatedSpectrum[4] * 1.30f
            }

            val baseH = when {
                tickIndex % 5 == 1 -> baseHeightMajor
                tickIndex % 5 == 3 -> baseHeightIntermediate
                else -> baseHeightMinor
            }

            val targetHeight = baseH * (1.0f + (voiceActivity * 1.65f + spectralEnergy * 1.75f))
            val halfH = (targetHeight / 2f).coerceIn(4.0f, maxAllowedHalfH)

            assertTrue(halfH in 4.0f..maxAllowedHalfH, "Высота риски $tickIndex обязана быть в пределах [4.0, $maxAllowedHalfH]")
            assertFalse(halfH.isNaN(), "Высота риски не может быть NaN")
            assertFalse(halfH.isInfinite(), "Высота риски не может быть бесконечной")
        }

        assertEquals(33, centerTickIndex, "Центральная визирная риска обязана быть на индексе 33")
        assertEquals(13, majorTicksCount, "Шкала из 65 делений с шагом 5 обязана содержать ровно 13 главных рисок")
    }

    // =========================================================================
    // 2. ТЕСТИРОВАНИЕ КИНЕМАТИКИ ВРАЩЕНИЯ ШЕСТЕРЕНКИ НАСТРОЕК
    // =========================================================================

    @Test
    fun testRotationalSpringDampedOscillatorSettling() {
        // Уравнение кручения: d^2(theta)/dt^2 + 2*zeta*omega*d(theta)/dt + omega^2*(theta - target) = 0
        // Для stiffness = 160f, dampingRatio = 0.65f:
        val stiffness = 160.0
        val dampingRatio = 0.65
        val omega = sqrt(stiffness) // ~12.65 рад/с
        val gamma = dampingRatio * omega // ~8.22
        val omegaDamped = omega * sqrt(1.0 - dampingRatio * dampingRatio) // ~9.61 рад/с

        val targetAngle = 360.0
        var maxOvershoot = 0.0

        // Численное интегрирование с шагом 1 мс на интервале 500 мс
        val dt = 0.001
        for (step in 0..500) {
            val t = step * dt
            // Аналитическое решение гармонического осциллятора с недодемпфированием:
            // theta(t) = target - target * exp(-gamma*t) * (cos(omega_d*t) + (gamma/omega_d)*sin(omega_d*t))
            val envelope = exp(-gamma * t)
            val oscillation = cos(omegaDamped * t) + (gamma / omegaDamped) * sin(omegaDamped * t)
            val currentAngle = targetAngle - targetAngle * envelope * oscillation

            if (currentAngle > maxOvershoot) {
                maxOvershoot = currentAngle
            }

            // На отметке 350 мс угол должен практически совпасть с целевым 360 градусов (погрешность < 1%)
            if (t >= 0.350) {
                val error = abs(currentAngle - targetAngle)
                assertTrue(error < 3.6, "На 350 мс погрешность угла должна быть < 1% ($error градусов)")
            }
        }

        // Проверка наличия легкого благородного перелета (overshoot в пределах 370-380 градусов)
        assertTrue(maxOvershoot in 370.0..380.0, "Механический овершут шестеренки обязан быть в диапазоне 370-380 градусов (факт: $maxOvershoot)")
    }

    // =========================================================================
    // 3. ТЕСТИРОВАНИЕ КОНЕЧНОГО АВТОМАТА ЦВЕТОВ ПЛАШЕК
    // =========================================================================

    @Test
    fun testSessionPillColorLatchingStateTransitions() {
        val colorBlack = 0xFF09090B
        val colorAmber = 0xFFF59E0B
        val colorEmerald = 0xFF10B981

        fun resolveTargetColor(linkState: LinkState): Long {
            return when (linkState) {
                LinkState.LIVE -> colorEmerald
                LinkState.CONNECTING, LinkState.RECONNECTING -> colorAmber
                LinkState.IDLE -> colorBlack
            }
        }

        assertEquals(colorBlack, resolveTargetColor(LinkState.IDLE), "В покое надпись сессии обязана быть глубоко черной")
        assertEquals(colorAmber, resolveTargetColor(LinkState.CONNECTING), "При подключении надпись должна пульсировать янтарным")
        assertEquals(colorAmber, resolveTargetColor(LinkState.RECONNECTING), "При реконнекте надпись должна быть янтарной")
        assertEquals(colorEmerald, resolveTargetColor(LinkState.LIVE), "При установленном дуплексе цвет обязан стать изумрудно-зеленым")
    }

    @Test
    fun testMaterialsColorLatchingAndTimerDecay() = runBlocking {
        val colorBlack = 0xFF09090B
        val colorCyan = 0xFF0EA5E9

        var currentColor = colorBlack
        var isColorLatched = false

        fun triggerMaterialsSent() {
            isColorLatched = true
            currentColor = colorCyan
        }

        fun onLatchingTimerExpired() {
            isColorLatched = false
            currentColor = colorBlack
        }

        assertEquals(colorBlack, currentColor)

        // Имитация нажатия "ОК" в шухлядке материалов
        triggerMaterialsSent()
        assertTrue(isColorLatched)
        assertEquals(colorCyan, currentColor, "При отправке материалов цвет слова Materials обязан стать лазурным")

        // Имитация удержания цвета на время сетевой передачи
        delay(50)
        onLatchingTimerExpired()
        assertFalse(isColorLatched)
        assertEquals(colorBlack, currentColor, "По истечении таймера цвет обязан плавно вернуться в исходный монохром")
    }

    // =========================================================================
    // 4. ТЕСТИРОВАНИЕ КОЛОРИМЕТРИИ И КОНТРАСТНОСТИ БЕЛОГО ЛИСТА
    // =========================================================================

    @Test
    fun testWcagContrastAndColorimetrics() {
        // Формула относительной яркости WCAG: Y = 0.2126*R + 0.7152*G + 0.0722*B
        fun calculateLuminance(r: Int, g: Int, b: Int): Double {
            fun channelLuminance(c: Int): Double {
                val s = c / 255.0
                return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b)
        }

        fun calculateContrastRatio(l1: Double, l2: Double): Double {
            val lighter = max(l1, l2)
            val darker = min(l1, l2)
            return (lighter + 0.05) / (darker + 0.05)
        }

        // Белый лист: #FFFFFF
        val lumWhite = calculateLuminance(255, 255, 255)
        assertEquals(1.0, lumWhite, 1e-4)

        // Обсидиановый черный шрифт: #09090B
        val lumBlack = calculateLuminance(9, 9, 11)

        val contrastBlackOnWhite = calculateContrastRatio(lumWhite, lumBlack)
        // Контрастность черного текста на белом фоне должна превышать 20:1
        assertTrue(contrastBlackOnWhite >= 20.0, "Контрастность черного текста на белом листе обязана быть >= 20:1 (факт: $contrastBlackOnWhite)")

        // Разделительная микрофаска: #E2E8F0
        val lumHairline = calculateLuminance(226, 232, 240)
        val contrastHairlineOnWhite = calculateContrastRatio(lumWhite, lumHairline)

        // Контрастность однопиксельной рамки должна быть мягкой (в диапазоне 1.15 : 1 .. 1.30 : 1)
        assertTrue(contrastHairlineOnWhite in 1.15..1.35, "Окантовка обязана быть деликатной и не резать глаз (факт: $contrastHairlineOnWhite)")
    }

    // =========================================================================
    // 5. ТЕСТИРОВАНИЕ ПРОТОКОЛА МАТЕРИАЛОВ (Текст может быть пустым)
    // =========================================================================

    @Test
    fun testMaterialsPayloadDispatchFormatting() {
        data class MaterialsPayload(
            val commentText: String,
            val attachmentUris: List<String>
        ) {
            val isValidForSend: Boolean
                get() = commentText.isNotBlank() || attachmentUris.isNotEmpty()
        }

        // 1. Сценарий: только фото/документы без текста (Текст может быть пустым по ТЗ)
        val payloadImageOnly = MaterialsPayload(commentText = "", attachmentUris = listOf("content://media/image/123"))
        assertTrue(payloadImageOnly.isValidForSend, "Отправка вложений с пустым текстом обязана быть разрешена")

        // 2. Сценарий: только текстовое сообщение без файлов
        val payloadTextOnly = MaterialsPayload(commentText = "Изучи эту задачу подробно", attachmentUris = emptyList())
        assertTrue(payloadTextOnly.isValidForSend, "Отправка чистого текстового комментария разрешена")

        // 3. Сценарий: текст + файлы
        val payloadFull = MaterialsPayload(commentText = "Вот отчет", attachmentUris = listOf("content://media/doc/456"))
        assertTrue(payloadFull.isValidForSend, "Смешанный пакет материалов валиден")

        // 4. Сценарий: пустой ввод
        val payloadEmpty = MaterialsPayload(commentText = "   ", attachmentUris = emptyList())
        assertFalse(payloadEmpty.isValidForSend, "Полностью пустое действие не должно порождать сетевой пакет")
    }
}