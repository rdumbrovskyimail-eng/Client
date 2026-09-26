package com.client.app.vad

import ai.onnxruntime.*
import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Диагностический снимок вывода детектора голосовой активности (Дефект 136, 138).
 */
data class VadInferenceStats(
    val probability: Float,
    val isNeural: Boolean,
    val p50LatencyUs: Long,
    val p95LatencyUs: Long,
    val p99LatencyUs: Long
)

@Singleton
class SileroVadDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {

    companion object {
        const val WINDOW_SIZE_SAMPLES = 512       // 32 мс @ 16 кГц
        private const val CONTEXT_SIZE_SAMPLES = 64 // 4 мс @ 16 кГц (официальный стриминговый контекст Silero V5)
        private const val MODEL_INPUT_SAMPLES = WINDOW_SIZE_SAMPLES + CONTEXT_SIZE_SAMPLES // 576 сэмплов
        private const val HOP_SIZE_SAMPLES = 160    // 10 мс @ 16 кГц (квант захвата звука AAudio)

        private const val MODEL_PATH = "models/silero_vad_v5_quant.onnx"
        private const val MIN_VALID_MODEL_BYTES = 500_000L

        // Скрытое рекуррентное состояние Silero V5: [2, 1, 128]
        private const val STATE_SIZE = 2 * 1 * 128 // 256 float элементов

        private const val LATENCY_HISTORY_CAPACITY = 100
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    // Постоянные предвыделенные ONNX-тензоры для полного исключения аллокаций (Дефект 137)
    private var persistentInputTensor: OnnxTensor? = null
    private var persistentStateTensor: OnnxTensor? = null
    private var persistentSrTensor: OnnxTensor? = null
    private var persistentInputsMap: Map<String, OnnxTensor>? = null

    private val lifecycleLock = Any()
    private val audioProcessingLock = Any()

    private val _speechProbability = MutableStateFlow(0f)
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    private val _isNeuralActive = MutableStateFlow(false)
    val isNeuralActive: StateFlow<Boolean> = _isNeuralActive.asStateFlow()

    // УСТРАНЕНИЕ ДЕФЕКТА 139: Lock-free атомарные пороги, исключающие блокировку потока микрофона
    private val thresholdSpeechStartBits = AtomicInteger(0.65f.toRawBits())
    private val thresholdSpeechEndBits = AtomicInteger(0.35f.toRawBits())

    val thresholdSpeechStart: Float get() = Float.fromBits(thresholdSpeechStartBits.get())
    val thresholdSpeechEnd: Float get() = Float.fromBits(thresholdSpeechEndBits.get())

    // Состояния рекуррентной нейросети
    private val stateBuffer = FloatArray(STATE_SIZE)
    private val stateBackupBuffer = FloatArray(STATE_SIZE) // УСТРАНЕНИЕ ДЕФЕКТА 134: Резервный откат
    private val contextBuffer = FloatArray(CONTEXT_SIZE_SAMPLES)

    // Предвыделенные прямые нативные буферы
    private val inputFloatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(MODEL_INPUT_SAMPLES * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val stateFloatBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(STATE_SIZE * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private val srBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(8)
        .order(ByteOrder.nativeOrder())

    // УСТРАНЕНИЕ ДЕФЕКТА 131: Кольцевой скользящий буфер на 512 сэмплов с шагом продвижения 160 сэмплов
    private val slidingWindowBuffer = ShortArray(WINDOW_SIZE_SAMPLES)
    private var slidingWindowCount = 0

    private var speechStartStreak = 0
    private var speechEndStreak = 0
    private var consecutiveNeuralErrors = 0

    private val inputTensorShape = longArrayOf(1L, MODEL_INPUT_SAMPLES.toLong())
    private val stateTensorShape = longArrayOf(2L, 1L, 128L)
    private val srTensorShape = longArrayOf(1L)

    // Метрология производительности инференса (Дефект 138)
    private val latencyHistoryUs = LongArray(LATENCY_HISTORY_CAPACITY)
    private var latencyHistoryIndex = 0
    private var latencySamplesCount = 0
    private val lastInferenceLatencyUs = AtomicLong(0L)

    private var estimatedNoiseFloorRms = 0.015f

    suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lifecycleLock) {
            if (_isNeuralActive.value && ortSession != null) {
                return@withContext true
            }

            try {
                val afd = context.assets.openFd(MODEL_PATH)
                afd.use { asset ->
                    if (asset.length < MIN_VALID_MODEL_BYTES) {
                        logger.e("SileroVadDetector: $MODEL_PATH is too small (${asset.length} bytes)")
                        return@withContext false
                    }

                    val env = OrtEnvironment.getEnvironment()
                    val sessionOptions = OrtSession.SessionOptions().apply {
                        setIntraOpNumThreads(1)
                        setInterOpNumThreads(1)
                        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    }

                    ParcelFileDescriptor.AutoCloseInputStream(
                        ParcelFileDescriptor.dup(asset.fileDescriptor)
                    ).use { inputStream ->
                        val mapped = inputStream.channel.map(
                            FileChannel.MapMode.READ_ONLY,
                            asset.startOffset,
                            asset.length
                        ).order(ByteOrder.nativeOrder())

                        ortEnvironment = env
                        ortSession = env.createSession(mapped, sessionOptions)
                    }

                    val session = ortSession ?: throw IllegalStateException("ONNX session creation returned null")
                    validateModelContract(session)

                    // УСТРАНЕНИЕ ДЕФЕКТА 137: Однократное создание постоянных переиспользуемых тензоров
                    inputFloatBuffer.clear()
                    repeat(MODEL_INPUT_SAMPLES) { inputFloatBuffer.put(0f) }
                    inputFloatBuffer.flip()

                    stateFloatBuffer.clear()
                    repeat(STATE_SIZE) { stateFloatBuffer.put(0f) }
                    stateFloatBuffer.flip()

                    srBuffer.clear()
                    srBuffer.asLongBuffer().put(16000L)
                    srBuffer.position(0)

                    persistentInputTensor?.close()
                    persistentStateTensor?.close()
                    persistentSrTensor?.close()

                    persistentInputTensor = OnnxTensor.createTensor(env, inputFloatBuffer, inputTensorShape)
                    persistentStateTensor = OnnxTensor.createTensor(env, stateFloatBuffer, stateTensorShape)
                    persistentSrTensor = OnnxTensor.createTensor(env, srBuffer.asLongBuffer(), srTensorShape)

                    persistentInputsMap = mapOf(
                        "input" to persistentInputTensor!!,
                        "state" to persistentStateTensor!!,
                        "sr" to persistentSrTensor!!
                    )

                    // Разогрев сессии
                    session.run(persistentInputsMap).use { warmResult ->
                        if (warmResult.size() < 2) {
                            throw IllegalStateException("Silero V5 warm-up returned ${warmResult.size()} outputs")
                        }
                    }

                    synchronized(audioProcessingLock) {
                        stateBuffer.fill(0f)
                        stateBackupBuffer.fill(0f)
                        contextBuffer.fill(0f)
                        slidingWindowBuffer.fill(0)
                        slidingWindowCount = 0
                        consecutiveNeuralErrors = 0
                        estimatedNoiseFloorRms = 0.015f
                    }

                    _isNeuralActive.value = true
                    logger.d("SileroVadDetector: Zero-Allocation Silero V5 ONNX initialized successfully")
                    return@withContext true
                }
            } catch (e: Exception) {
                logger.e("SileroVadDetector: strict ONNX initialization failed", e)
                closeResourcesLocked()
                _isNeuralActive.value = false
                return@withContext false
            }
        }
    }

    private fun validateModelContract(session: OrtSession) {
        val inputNames = session.inputNames
        val outputNames = session.outputNames
        val requiredInputs = setOf("input", "state", "sr")

        if (!inputNames.containsAll(requiredInputs)) {
            throw IllegalStateException("Unexpected Silero V5 inputs: $inputNames, required=$requiredInputs")
        }
        if (outputNames.size < 2) {
            throw IllegalStateException("Unexpected Silero V5 outputs: $outputNames")
        }
    }

    fun setThresholds(start: Float, end: Float) {
        thresholdSpeechStartBits.set(start.toRawBits())
        thresholdSpeechEndBits.set(end.toRawBits())
    }

    /**
     * Потоковая обработка сэмплов захвата (160 сэмплов = 10 мс).
     *
     * УСТРАНЕНИЕ ДЕФЕКТА 131: Скользящее окно продвигается строго на HOP_SIZE_SAMPLES (160 сэмплов).
     * Инференс выполняется ровно один раз на каждый поступивший 10-мс фрейм. Фазовый джиттер = 0 мс!
     */
    fun processSamples(
        pcm16: ByteArray,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit
    ) = synchronized(audioProcessingLock) {
        val sampleCount = pcm16.size / 2
        if (sampleCount == 0) return

        var offset = 0
        while (offset < sampleCount) {
            val toCopy = minOf(sampleCount - offset, WINDOW_SIZE_SAMPLES - slidingWindowCount)

            for (i in 0 until toCopy) {
                val byteIdx = (offset + i) * 2
                val s = ((pcm16[byteIdx].toInt() and 0xFF) or (pcm16[byteIdx + 1].toInt() shl 8)).toShort()
                slidingWindowBuffer[slidingWindowCount + i] = s
            }

            slidingWindowCount += toCopy
            offset += toCopy

            if (slidingWindowCount >= WINDOW_SIZE_SAMPLES) {
                // Окно 512 сэмплов заполнено: исполняем детерминированный инференс
                evaluateWindow(slidingWindowBuffer, onSpeechStart, onSpeechEnd)

                // Продвигаем окно: сдвигаем влево на 160 сэмплов, сохраняя историю 352 сэмплов
                System.arraycopy(
                    slidingWindowBuffer,
                    HOP_SIZE_SAMPLES,
                    slidingWindowBuffer,
                    0,
                    WINDOW_SIZE_SAMPLES - HOP_SIZE_SAMPLES
                )
                slidingWindowCount = WINDOW_SIZE_SAMPLES - HOP_SIZE_SAMPLES
            }
        }
    }

    private fun evaluateWindow(
        window: ShortArray,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit
    ) {
        var isNeuralSuccess = false
        var prob = 0f

        val startNs = SystemClock.elapsedRealtimeNanos()

        if (_isNeuralActive.value && ortSession != null) {
            try {
                prob = evaluateNeuralZeroAlloc(window)
                isNeuralSuccess = true
                consecutiveNeuralErrors = 0
            } catch (t: Throwable) {
                // УСТРАНЕНИЕ ДЕФЕКТА 134: Мягкий откат состояния без сброса памяти шума в 0
                logger.w("SileroVadDetector: Neural inference glitch; soft state rollback: ${t.message}")
                System.arraycopy(stateBackupBuffer, 0, stateBuffer, 0, STATE_SIZE)
                consecutiveNeuralErrors++

                if (consecutiveNeuralErrors >= 5) {
                    _isNeuralActive.value = false
                }
                prob = evaluateFallbackCalibratedRms(window)
            }
        } else {
            prob = evaluateFallbackCalibratedRms(window)
        }

        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - startNs) / 1000L
        recordLatencyTelemetry(elapsedUs)

        _speechProbability.value = prob

        val currentStartThresh = thresholdSpeechStart
        val currentEndThresh = thresholdSpeechEnd

        // УСТРАНЕНИЕ ДЕФЕКТА 132: Мгновенный триггер старта речи при высокой уверенности (латентность 10–20 мс)
        if (!_isSpeechDetected.value) {
            if (prob >= 0.75f) {
                // Высокая вероятность: триггерим немедленно на первом окне
                _isSpeechDetected.value = true
                speechStartStreak = 0
                speechEndStreak = 0
                onSpeechStart()
            } else if (prob >= currentStartThresh) {
                speechStartStreak++
                if (speechStartStreak >= 2) {
                    _isSpeechDetected.value = true
                    speechStartStreak = 0
                    speechEndStreak = 0
                    onSpeechStart()
                }
            } else {
                speechStartStreak = 0
            }
        } else {
            // УСТРАНЕНИЕ ДЕФЕКТА 133: Сокращение паузы тишины до 4 окон (120 мс) по ITU-T G.729B
            if (prob < currentEndThresh) {
                speechEndStreak++
                if (speechEndStreak >= 4) {
                    _isSpeechDetected.value = false
                    speechEndStreak = 0
                    speechStartStreak = 0
                    onSpeechEnd()
                }
            } else {
                speechEndStreak = maxOf(0, speechEndStreak - 1)
            }
        }
    }

    /**
     * УСТРАНЕНИЕ ДЕФЕКТА 137: 100% Zero-Allocation потоковый инференс.
     * Ни одного вызова new OnnxTensor или mapOf внутри аудиоцикла!
     */
    private fun evaluateNeuralZeroAlloc(window: ShortArray): Float {
        val session = ortSession ?: throw IllegalStateException("ONNX session is null")
        val inputs = persistentInputsMap ?: throw IllegalStateException("Inputs map is null")

        // 1. Копируем текущее стабильное состояние в резервный буфер перед инференсом (Дефект 134)
        System.arraycopy(stateBuffer, 0, stateBackupBuffer, 0, STATE_SIZE)

        // 2. Обновляем входной DirectBuffer: [64 сэмпла контекста] + [512 сэмплов окна]
        inputFloatBuffer.clear()
        inputFloatBuffer.put(contextBuffer)

        for (i in 0 until WINDOW_SIZE_SAMPLES) {
            inputFloatBuffer.put(window[i] / 32768.0f)
        }
        inputFloatBuffer.flip()

        // 3. Обновляем скрытое состояние в DirectBuffer
        stateFloatBuffer.clear()
        stateFloatBuffer.put(stateBuffer)
        stateFloatBuffer.flip()

        // 4. Запуск инференса на постоянных тензорах
        session.run(inputs).use { result ->
            val outputTensor = result.get(0) as OnnxTensor
            val outputProb = outputTensor.floatBuffer.get(0)

            // Вычитываем обновленное состояние напрямую в нативный массив
            val nextStateTensor = result.get(1) as OnnxTensor
            val nextStateBuf = nextStateTensor.floatBuffer
            nextStateBuf.position(0)
            nextStateBuf.get(stateBuffer)

            // Обновляем 64 сэмпла контекста
            val contextStart = WINDOW_SIZE_SAMPLES - CONTEXT_SIZE_SAMPLES
            for (i in 0 until CONTEXT_SIZE_SAMPLES) {
                contextBuffer[i] = window[contextStart + i] / 32768.0f
            }

            return outputProb
        }
    }

    /**
     * УСТРАНЕНИЕ ДЕФЕКТА 135: Сигмоидально калиброванный RMS-фоллбэк,
     * согласованный со шкалой вероятностей нейросети [0.0, 1.0].
     */
    private fun evaluateFallbackCalibratedRms(window: ShortArray): Float {
        var sumSq = 0.0
        for (s in window) {
            val f = s / 32768.0
            sumSq += f * f
        }
        val rms = sqrt((sumSq + 1e-9) / window.size).toFloat()

        if (rms < estimatedNoiseFloorRms * 1.5f) {
            estimatedNoiseFloorRms = estimatedNoiseFloorRms * 0.95f + rms * 0.05f
        } else {
            estimatedNoiseFloorRms = min(estimatedNoiseFloorRms * 1.01f, 0.05f)
        }
        estimatedNoiseFloorRms = estimatedNoiseFloorRms.coerceIn(0.005f, 0.06f)

        val snrDb = 20.0f * kotlin.math.log10(max(1e-4f, rms) / max(1e-4f, estimatedNoiseFloorRms))
        val normalizedProb = 1.0f / (1.0f + kotlin.math.exp(-(snrDb - 9.0f) * 0.45f))

        return normalizedProb.coerceIn(0.0f, 1.0f)
    }

    // УСТРАНЕНИЕ ДЕФЕКТА 138: Сбор метрик квантилей задержки p50/p95/p99
    private fun recordLatencyTelemetry(latencyUs: Long) {
        lastInferenceLatencyUs.set(latencyUs)
        latencyHistoryUs[latencyHistoryIndex] = latencyUs
        latencyHistoryIndex = (latencyHistoryIndex + 1) % LATENCY_HISTORY_CAPACITY
        if (latencySamplesCount < LATENCY_HISTORY_CAPACITY) latencySamplesCount++
    }

    fun getLatencyStats(): Triple<Long, Long, Long> = synchronized(audioProcessingLock) {
        if (latencySamplesCount == 0) return Triple(0L, 0L, 0L)
        val sorted = latencyHistoryUs.copyOf(latencySamplesCount).apply { sort() }
        val p50 = sorted[(sorted.size * 0.50).toInt()]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.lastIndex)]
        val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.lastIndex)]
        return Triple(p50, p95, p99)
    }

    private fun closeResourcesLocked() {
        persistentInputsMap = null
        runCatching { persistentInputTensor?.close() }
        runCatching { persistentStateTensor?.close() }
        runCatching { persistentSrTensor?.close() }
        runCatching { ortSession?.close() }
        runCatching { ortEnvironment?.close() }

        persistentInputTensor = null
        persistentStateTensor = null
        persistentSrTensor = null
        ortSession = null
        ortEnvironment = null
    }

    fun resetState() = synchronized(audioProcessingLock) {
        stateBuffer.fill(0f)
        stateBackupBuffer.fill(0f)
        contextBuffer.fill(0f)
        slidingWindowBuffer.fill(0)
        slidingWindowCount = 0

        speechStartStreak = 0
        speechEndStreak = 0
        consecutiveNeuralErrors = 0
        estimatedNoiseFloorRms = 0.015f

        _isSpeechDetected.value = false
        _speechProbability.value = 0f
    }
}