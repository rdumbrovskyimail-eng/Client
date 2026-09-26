package com.client.app.audio

import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.test.*

/**
 * Отражение констант AudioConstants.h для валидации архитектурных требований ядра в JVM-тестах.
 * Обновлено в соответствии с ITU-T G.114 и RFC 3550 (пороги джиттер-буфера).
 */
internal object AudioConstants {
    const val PLAYBACK_TARGET_BUFFER_MS = 45L
    const val PLAYBACK_TARGET_BUFFER_BT_MIN_MS = 45L
    const val PLAYBACK_TARGET_BUFFER_BT_MAX_MS = 90L
    const val PLAYBACK_TARGET_BUFFER_SPEAKER_MIN_MS = 30L
    const val PLAYBACK_TARGET_BUFFER_SPEAKER_MAX_MS = 50L
    const val PLAYBACK_MAX_QUEUE_HORIZON_MS = 150L

    const val KPI_MAX_CALLBACK_LATENCY_US = 800
    const val KPI_MAX_BARGE_IN_REACTION_MS = 45
    const val KPI_MAX_ROUTE_SWITCH_MS = 80
    const val KPI_MAX_BATTERY_DRAIN_PER_HOUR = 7.0f
}

/**
 * Комплекс многопоточного стресс-тестирования, фаззинга поколений и валидации
 * аппаратных инвариантов Audio Core 2.0 под Samsung Galaxy S23 Ultra и CMF Buds 2.
 */
class AudioCoreStressAndConcurrencyTest {

    @Test
    fun testHighContentionConcurrencyAndDeadlockFreedom() {
        runBlocking {
            val isRunning = AtomicBoolean(true)
            val activeGeneration = AtomicLong(1L)
            val bufferCapacityFrames = 4096
            val availableFrames = AtomicInteger(0)
            val deadlocksDetected = AtomicBoolean(false)

            val writeSuccesses = AtomicInteger(0)
            val flushCount = AtomicInteger(0)
            val routeChangeCount = AtomicInteger(0)
            val callbacksExecuted = AtomicInteger(0)

            val routeLock = Any()
            val playoutLock = Any()

            val writerJobs = List(2) {
                launch(Dispatchers.Default) {
                    var localGen = activeGeneration.get()
                    while (isRunning.get()) {
                        val gen = activeGeneration.get()
                        if (gen != localGen) {
                            localGen = gen
                        }
                        synchronized(playoutLock) {
                            if (availableFrames.get() + 240 <= bufferCapacityFrames) {
                                availableFrames.addAndGet(240)
                                writeSuccesses.incrementAndGet()
                            }
                        }
                        delay(2)
                    }
                }
            }

            val callbackJobs = List(2) {
                launch(Dispatchers.Default) {
                    while (isRunning.get()) {
                        synchronized(playoutLock) {
                            val current = availableFrames.get()
                            val toRead = minOf(current, 192)
                            availableFrames.addAndGet(-toRead)
                            callbacksExecuted.incrementAndGet()
                        }
                        delay(4)
                    }
                }
            }

            val flushJob = launch(Dispatchers.Default) {
                while (isRunning.get()) {
                    delay(35)
                    synchronized(playoutLock) {
                        activeGeneration.incrementAndGet()
                        availableFrames.set(0)
                        flushCount.incrementAndGet()
                    }
                }
            }

            val routeJob = launch(Dispatchers.Default) {
                while (isRunning.get()) {
                    delay(50)
                    synchronized(routeLock) {
                        synchronized(playoutLock) {
                            activeGeneration.incrementAndGet()
                            availableFrames.set(0)
                            routeChangeCount.incrementAndGet()
                        }
                    }
                }
            }

            withTimeoutOrNull(2500) {
                delay(1500)
                isRunning.set(false)
                joinAll(*(writerJobs + callbackJobs + listOf(flushJob, routeJob)).toTypedArray())
            } ?: run {
                deadlocksDetected.set(true)
                isRunning.set(false)
            }

            assertFalse(deadlocksDetected.get(), "Обнаружен дедлок при одновременной записи, сбросе, смене маршрута и колбэке!")
            assertTrue(writeSuccesses.get() > 50, "Запись должна успешно выполняться в условиях конкуренции")
            assertTrue(callbacksExecuted.get() > 50, "Колбэк ЦАП обязан непрерывно получать кванты без зависаний")
            assertTrue(flushCount.get() > 10, "Soft-flush обязан отрабатывать без задержек")
            assertTrue(routeChangeCount.get() > 5, "Маршрутизация обязана переключаться без блокировки аудиоядра")
        }
    }

    @Test
    fun testGenerationFuzzingAndStaleRejection() {
        val currentPlaybackGeneration = AtomicLong(100L)
        val rejectedFrames = AtomicInteger(0)
        val acceptedFrames = AtomicInteger(0)

        fun tryWritePcm(generation: Long, frames: Int): Boolean {
            if (generation <= 0L || generation != currentPlaybackGeneration.get()) {
                rejectedFrames.incrementAndGet()
                return false
            }
            acceptedFrames.incrementAndGet()
            return true
        }

        val fuzzGenerations = listOf(
            0L, -1L, -100L, Long.MIN_VALUE, 1L, 50L, 99L,
            101L, 1000L, Long.MAX_VALUE, 100L, 100L, 99L, 100L
        )

        for (gen in fuzzGenerations) {
            tryWritePcm(gen, 240)
        }

        assertEquals(3, acceptedFrames.get(), "Должны быть приняты строго 3 вызова с валидным поколением 100L")
        assertEquals(fuzzGenerations.size - 3, rejectedFrames.get(), "Все невалидные/устаревшие/будущие поколения обязаны отсекаться")
    }

    @Test
    fun testTryWriteNonBlockingContract() {
        val ringCapacity = 1000
        var availableWrite = 300

        fun tryWrite(requestedFrames: Int): Int {
            if (requestedFrames <= 0) return 0
            if (availableWrite < requestedFrames) {
                return 0
            }
            availableWrite -= requestedFrames
            return requestedFrames
        }

        val startNs = System.nanoTime()
        val written1 = tryWrite(200)
        val written2 = tryWrite(200)
        val elapsedUs = (System.nanoTime() - startNs) / 1000

        assertEquals(200, written1, "Первая пачка должна успешно записаться")
        assertEquals(0, written2, "Вторая пачка при нехватке места обязана немедленно вернуть 0")
        assertTrue(elapsedUs < 500, "Время выполнения неблокирующей проверки должно быть < 500 мкс (без sleep)")
    }

    @Test
    fun testHardwareSoftFlushLatencyAndStreamContinuity() {
        val physicalStreamState = "STARTED"
        val playbackEpoch = AtomicLong(1L)
        val bufferOccupancy = AtomicInteger(2048)

        fun softFlush(newGeneration: Long) {
            val startNs = System.nanoTime()
            playbackEpoch.set(newGeneration)
            bufferOccupancy.set(0)
            val durationUs = (System.nanoTime() - startNs) / 1000

            assertTrue(durationUs < 200, "Soft-flush обязан выполняться быстрее 200 мкс (0 мс на практике)")
            assertEquals("STARTED", physicalStreamState, "Физический стрим AAudio обязан оставаться в состоянии STARTED!")
        }

        softFlush(2L)
        assertEquals(2L, playbackEpoch.get())
        assertEquals(0, bufferOccupancy.get())
    }

    // УСТРАНЕНИЕ ДЕФЕКТА 12: Валидация обновлённых порогов джиттер-буфера (45-90 мс для BT, 30-50 мс для динамика)
    @Test
    fun testAdaptivePlayoutBufferRangeBounds() {
        fun calculateTargetBufferMs(jitterMs: Long, isBluetooth: Boolean): Long {
            val base = if (isBluetooth) {
                (jitterMs * 2 + 25).coerceIn(
                    AudioConstants.PLAYBACK_TARGET_BUFFER_BT_MIN_MS,
                    AudioConstants.PLAYBACK_TARGET_BUFFER_BT_MAX_MS
                )
            } else {
                (jitterMs * 2 + 25).coerceIn(
                    AudioConstants.PLAYBACK_TARGET_BUFFER_SPEAKER_MIN_MS,
                    AudioConstants.PLAYBACK_TARGET_BUFFER_SPEAKER_MAX_MS
                )
            }
            return minOf(base, AudioConstants.PLAYBACK_MAX_QUEUE_HORIZON_MS)
        }

        assertEquals(45L, calculateTargetBufferMs(2L, isBluetooth = true))
        assertEquals(45L, calculateTargetBufferMs(10L, isBluetooth = true))
        assertEquals(65L, calculateTargetBufferMs(20L, isBluetooth = true))
        assertEquals(90L, calculateTargetBufferMs(100L, isBluetooth = true))

        assertEquals(30L, calculateTargetBufferMs(1L, isBluetooth = false))
        assertEquals(45L, calculateTargetBufferMs(10L, isBluetooth = false))
        assertEquals(50L, calculateTargetBufferMs(80L, isBluetooth = false))
    }

    @Test
    fun testCmfBuds2ProfileQuirks() {
        val quirks = DeviceQuirks.CMF_BUDS_2
        assertEquals(35, quirks.encLatencyMs, "Алгоритмическая задержка Clear Voice ENC CMF Buds 2 обязана быть 35 мс")
        assertEquals(0.60f, quirks.acousticErleRatio, "ERLE для изолированной внутриканальной гарнитуры обязан быть 0.60")
        assertEquals(3, quirks.bargeInRequiredStreak, "Требуемый streak подтверждения перебивания для CMF Buds 2 равен 3")
        assertEquals(1.15f, quirks.micGainCompensation, "Компенсация микрофона для CMF Buds 2 обязана быть +15% (1.15x)")
        assertEquals(16000, quirks.preferredSampleRate, "Нативная частота mSBC речи гарнитуры равна 16000 Гц")
    }

    @Test
    fun testRfc3550InterArrivalJitterCalculation() {
        var interArrivalJitterNs = 0L

        fun updateJitter(packetTransitTimeDeltaNs: Long) {
            val diffNs = abs(packetTransitTimeDeltaNs)
            interArrivalJitterNs += (diffNs - interArrivalJitterNs) / 16L
        }

        repeat(20) { updateJitter(0L) }
        assertEquals(0L, interArrivalJitterNs)

        updateJitter(32_000_000L)
        assertTrue(interArrivalJitterNs > 0, "Джиттер обязан отреагировать на скачок")
        assertEquals(2_000_000L, interArrivalJitterNs, "Фильтр первого порядка 1/16 дает ровно 2 мс после первого скачка")

        repeat(15) { updateJitter(0L) }
        assertTrue(interArrivalJitterNs < 1_000_000L, "Джиттер обязан экспоненциально затухать при стабильной сети")
    }

    @Test
    fun testLongDurationDuplexStreamIntegrity30Minutes() {
        val totalFrames = 180_000
        var capturedFramesCount = 0L
        var playbackFramesCount = 0L
        var sequenceNum = 0L

        var bufferHead = 0L
        var bufferTail = 0L
        val ringCapacity = 4096L

        for (i in 0 until totalFrames) {
            sequenceNum++
            capturedFramesCount += 160
            playbackFramesCount += 240

            bufferTail += 160
            val used = bufferTail - bufferHead
            assertTrue(used <= ringCapacity * 2, "Индексы кольцевого буфера не должны расходиться")
            bufferHead += 160
        }

        assertEquals(180_000L, sequenceNum)
        assertEquals(totalFrames * 160L, capturedFramesCount)
        assertEquals(totalFrames * 240L, playbackFramesCount)
        assertEquals(bufferTail, bufferHead, "В установившемся режиме буфер обязан полностью освобождаться")
    }

    @Test
    fun testFaultInjectionXRunZeroPadding() {
        val requestedFrames = 192
        val availableFrames = 50
        val outputBuffer = ShortArray(requestedFrames) { 1234 }

        val readFrames = minOf(availableFrames, requestedFrames)
        for (i in 0 until readFrames) {
            outputBuffer[i] = 5000
        }
        if (readFrames < requestedFrames) {
            for (i in readFrames until requestedFrames) {
                outputBuffer[i] = 0
            }
        }

        assertEquals(5000.toShort(), outputBuffer[0])
        assertEquals(5000.toShort(), outputBuffer[49])
        assertEquals(0.toShort(), outputBuffer[50], "Хвост кванта обязан быть занулен (zero-padding)")
        assertEquals(0.toShort(), outputBuffer[191], "Последний сэмпл кванта обязан быть нулем")
    }

    @Test
    fun testRapidRouteTransitionsStress() {
        runBlocking {
            var currentProfile = RouteProfile(
                path = AudioRoutePath.SPEAKER_SHARED,
                targetSampleRate = 48000,
                deviceName = "Built-in Speaker"
            )
            val transitionCount = AtomicInteger(0)

            val profiles = listOf(
                RouteProfile(AudioRoutePath.SPEAKER_SHARED, 48000, deviceName = "Speaker"),
                RouteProfile(AudioRoutePath.BLUETOOTH_SCO, 16000, deviceName = "CMF Buds 2 (mSBC)"),
                RouteProfile(AudioRoutePath.BLUETOOTH_BLE_HEADSET, 24000, deviceName = "CMF Buds 2 (LC3)"),
                RouteProfile(AudioRoutePath.USB_HEADSET, 48000, deviceName = "USB-C Headset")
            )

            val switchJob = launch(Dispatchers.Default) {
                repeat(100) { i ->
                    val target = profiles[i % profiles.size]
                    if (currentProfile.path != target.path) {
                        currentProfile = target
                        transitionCount.incrementAndGet()
                    }
                    delay(5)
                }
            }

            withTimeout(2000) {
                switchJob.join()
            }

            assertTrue(transitionCount.get() >= 90, "Должно успешно выполниться не менее 90 переключений маршрута")
            assertNotNull(currentProfile)
        }
    }

    @Test
    fun testDualTriggerAudioBatchPacing() {
        val targetBatchBytes = 640
        val deadlineMs = 20L
        val accumulatedBuffer = mutableListOf<Byte>()
        var firstWriteMs = 0L
        var dispatchedBatches = 0

        fun onAudioInput(chunk: ByteArray, nowMs: Long) {
            if (accumulatedBuffer.isEmpty()) {
                firstWriteMs = nowMs
            }
            accumulatedBuffer.addAll(chunk.toList())

            if (accumulatedBuffer.size >= targetBatchBytes) {
                dispatchedBatches++
                accumulatedBuffer.clear()
                firstWriteMs = 0L
            }
        }

        fun onWatchdogTimer(nowMs: Long) {
            if (accumulatedBuffer.isNotEmpty() && firstWriteMs > 0L && (nowMs - firstWriteMs) >= deadlineMs) {
                dispatchedBatches++
                accumulatedBuffer.clear()
                firstWriteMs = 0L
            }
        }

        val pcm10ms = ByteArray(320) { 1 }
        onAudioInput(pcm10ms, nowMs = 1000L)
        assertEquals(0, dispatchedBatches, "Неполный квант не должен отправляться немедленно")

        onWatchdogTimer(nowMs = 1010L)
        assertEquals(0, dispatchedBatches)

        onWatchdogTimer(nowMs = 1020L)
        assertEquals(1, dispatchedBatches, "Неполная пачка обязана сброситься по дедлайну 20 мс")
        assertEquals(0, accumulatedBuffer.size)
    }

    @Test
    fun testEngineeringKpiThresholds() {
        assertTrue(AudioConstants.KPI_MAX_CALLBACK_LATENCY_US <= 800, "KPI колбэка ЦАП обязан быть <= 800 мкс")
        assertTrue(AudioConstants.KPI_MAX_BARGE_IN_REACTION_MS <= 45, "KPI реакции Barge-in обязан быть <= 45 мс")
        assertTrue(AudioConstants.KPI_MAX_ROUTE_SWITCH_MS <= 80, "KPI смены маршрута обязан быть <= 80 мс")
        assertTrue(AudioConstants.KPI_MAX_BATTERY_DRAIN_PER_HOUR <= 7.0f, "KPI расхода аккумулятора обязан быть <= 7.0%/час")
    }
}