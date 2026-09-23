package com.client.app.vad

import ai.onnxruntime.*
import android.content.Context
import android.os.ParcelFileDescriptor
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
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

@Singleton
class SileroVadDetector @Inject constructor(
    @ApplicationContext
    private val context: Context,

    private val logger: AppLogger
) {

    companion object {

        const val WINDOW_SIZE_SAMPLES =
            512

        // Официальный стриминговый контекст Silero V5 при 16 кГц
        private const val CONTEXT_SIZE_SAMPLES =
            64

        private const val MODEL_INPUT_SAMPLES =
            WINDOW_SIZE_SAMPLES +
                CONTEXT_SIZE_SAMPLES // 576 сэмплов

        private const val MODEL_PATH =
            "models/silero_vad_v5_quant.onnx"

        private const val MIN_VALID_MODEL_BYTES =
            500_000L

        // Размер скрытого рекуррентного вектора состояния Silero V5: [2, 1, 128]
        private const val STATE_SIZE =
            2 * 1 * 128 // 256 float элементов
    }

    private var ortEnvironment:
        OrtEnvironment? = null

    private var ortSession:
        OrtSession? = null

    private val vadLock =
        ReentrantLock()

    private val _speechProbability =
        MutableStateFlow(0f)

    val speechProbability:
        StateFlow<Float> =
        _speechProbability.asStateFlow()

    private val _isSpeechDetected =
        MutableStateFlow(false)

    val isSpeechDetected:
        StateFlow<Boolean> =
        _isSpeechDetected.asStateFlow()

    private var thresholdSpeechStart =
        0.65f

    private var thresholdSpeechEnd =
        0.35f

    // Состояние модели Silero V5: плоский массив 256 float [2, 1, 128]
    private val stateBuffer =
        FloatArray(
            STATE_SIZE
        )

    private val contextBuffer =
        FloatArray(
            CONTEXT_SIZE_SAMPLES
        )

    // Предвыделенные прямые нативные буферы для исключения аллокаций в аудиоцикле
    private val inputFloatBuffer:
        FloatBuffer =
        ByteBuffer
            .allocateDirect(
                MODEL_INPUT_SAMPLES * 4
            )
            .order(
                ByteOrder.nativeOrder()
            )
            .asFloatBuffer()

    private val stateFloatBuffer:
        FloatBuffer =
        ByteBuffer
            .allocateDirect(
                STATE_SIZE * 4
            )
            .order(
                ByteOrder.nativeOrder()
            )
            .asFloatBuffer()

    private val accumulator =
        ShortArray(
            WINDOW_SIZE_SAMPLES
        )

    private var accumulatorCount =
        0

    private var speechStartStreak =
        0

    private var speechEndStreak =
        0

    @Volatile
    private var isNeuralModelLoaded =
        false

    val isNeuralActive:
        Boolean
        get() = isNeuralModelLoaded

    // Адаптивное отслеживание фонового шума для RMS-фоллбэка (Проблема №5)
    private var estimatedNoiseFloorRms =
        0.015f

    suspend fun prepare():
        Boolean =
        withContext(Dispatchers.IO) {

            vadLock.withLock {

                if (isNeuralModelLoaded) {
                    return@withContext true
                }

                try {
                    val afd =
                        context.assets.openFd(
                            MODEL_PATH
                        )

                    afd.use { asset ->

                        if (
                            asset.length <
                                MIN_VALID_MODEL_BYTES
                        ) {
                            logger.e(
                                "SileroVadDetector: $MODEL_PATH is too small (${asset.length} bytes); expected a real Silero V5 ONNX asset"
                            )
                            return@withContext false
                        }

                        val env =
                            OrtEnvironment
                                .getEnvironment()

                        val sessionOptions =
                            OrtSession
                                .SessionOptions()
                                .apply {
                                    setIntraOpNumThreads(1)
                                    setInterOpNumThreads(1)
                                    setOptimizationLevel(
                                        OrtSession
                                            .SessionOptions
                                            .OptLevel
                                            .ALL_OPT
                                    )
                                }

                        ParcelFileDescriptor
                            .AutoCloseInputStream(
                                ParcelFileDescriptor.dup(
                                    asset.fileDescriptor
                                )
                            )
                            .use { inputStream ->

                                val mapped =
                                    inputStream
                                        .channel
                                        .map(
                                            FileChannel
                                                .MapMode
                                                .READ_ONLY,
                                            asset.startOffset,
                                            asset.length
                                        )
                                        .order(
                                            ByteOrder.nativeOrder()
                                        )

                                ortEnvironment =
                                    env

                                ortSession =
                                    env.createSession(
                                        mapped,
                                        sessionOptions
                                    )
                            }

                        val session =
                            ortSession
                                ?: throw IllegalStateException(
                                    "ONNX session creation returned null"
                                )

                        validateModelContract(
                            session
                        )

                        // Безопасный разогрев модели с локальным временем жизни тензоров
                        inputFloatBuffer.clear()
                        repeat(MODEL_INPUT_SAMPLES) { inputFloatBuffer.put(0f) }
                        inputFloatBuffer.flip()

                        stateFloatBuffer.clear()
                        repeat(STATE_SIZE) { stateFloatBuffer.put(0f) }
                        stateFloatBuffer.flip()

                        OnnxTensor.createTensor(
                            env,
                            inputFloatBuffer,
                            longArrayOf(1L, MODEL_INPUT_SAMPLES.toLong())
                        ).use { warmInput ->
                            OnnxTensor.createTensor(
                                env,
                                stateFloatBuffer,
                                longArrayOf(2L, 1L, 128L)
                            ).use { warmState ->
                                OnnxTensor.createTensor(
                                    env,
                                    longArrayOf(16000L)
                                ).use { warmSr ->
                                    session.run(
                                        mapOf(
                                            "input" to warmInput,
                                            "state" to warmState,
                                            "sr" to warmSr
                                        )
                                    ).use { warmResult ->
                                        if (warmResult.size() < 2) {
                                            throw IllegalStateException(
                                                "Silero V5 warm-up returned ${warmResult.size()} outputs"
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        stateBuffer.fill(0f)
                        contextBuffer.fill(0f)
                        estimatedNoiseFloorRms = 0.015f

                        isNeuralModelLoaded =
                            true

                        logger.d(
                            "SileroVadDetector: Silero V5 ONNX initialized (${asset.length} bytes, input=$MODEL_INPUT_SAMPLES samples)"
                        )

                        return@withContext true
                    }

                } catch (e: Exception) {
                    logger.e(
                        "SileroVadDetector: strict ONNX initialization failed",
                        e
                    )

                    closeResourcesLocked()
                    isNeuralModelLoaded = false
                    return@withContext false
                }
            }
        }

    private fun validateModelContract(
        session: OrtSession
    ) {
        val inputNames =
            session.inputNames

        val outputNames =
            session.outputNames

        val requiredInputs =
            setOf(
                "input",
                "state",
                "sr"
            )

        if (!inputNames.containsAll(requiredInputs)) {
            throw IllegalStateException(
                "Unexpected Silero V5 inputs: $inputNames, required=$requiredInputs"
            )
        }

        if (outputNames.size < 2) {
            throw IllegalStateException(
                "Unexpected Silero V5 outputs: $outputNames"
            )
        }
    }

    fun setThresholds(
        start: Float,
        end: Float
    ) = vadLock.withLock {
        thresholdSpeechStart = start
        thresholdSpeechEnd = end
    }

    fun processSamples(
        pcm16: ByteArray,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit
    ) = vadLock.withLock {

        val sampleCount =
            pcm16.size / 2

        var offset = 0

        while (offset < sampleCount) {

            val toCopy =
                minOf(
                    sampleCount - offset,
                    WINDOW_SIZE_SAMPLES - accumulatorCount
                )

            for (i in 0 until toCopy) {

                val byteIdx =
                    (offset + i) * 2

                val s =
                    (
                        (
                            pcm16[byteIdx]
                                .toInt() and 0xFF
                        ) or
                        (
                            pcm16[byteIdx + 1]
                                .toInt() shl 8
                        )
                    ).toShort()

                accumulator[accumulatorCount + i] = s
            }

            accumulatorCount += toCopy
            offset += toCopy

            if (accumulatorCount >= WINDOW_SIZE_SAMPLES) {
                evaluateWindow(
                    accumulator,
                    onSpeechStart,
                    onSpeechEnd
                )
                accumulatorCount = 0
            }
        }
    }

    private fun evaluateWindow(
        window: ShortArray,
        onSpeechStart: () -> Unit,
        onSpeechEnd: () -> Unit
    ) {
        // Защита от перманентной деградации нейросети: при единичном сбое инференса
        // сессия НЕ закрывается, состояние сбрасывается локально, и работа продолжается.
        val prob = if (isNeuralModelLoaded && ortSession != null && ortEnvironment != null) {
            try {
                evaluateNeural(window)
            } catch (t: Throwable) {
                logger.w("SileroVadDetector: Neural inference glitch on current window; resetting state: ${t.message}")
                stateBuffer.fill(0f)
                contextBuffer.fill(0f)
                evaluateFallbackRms(window)
            }
        } else {
            evaluateFallbackRms(window)
        }

        _speechProbability.value = prob

        if (!_isSpeechDetected.value) {

            if (prob >= thresholdSpeechStart) {
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

            if (prob < thresholdSpeechEnd) {
                speechEndStreak++

                if (speechEndStreak >= 10) {
                    _isSpeechDetected.value = false
                    speechEndStreak = 0
                    speechStartStreak = 0
                    onSpeechEnd()
                }
            } else {
                // Адаптивное подавление шума (Hangover) по стандарту ITU-T G.729B
                speechEndStreak = maxOf(0, speechEndStreak - 2)
            }
        }
    }

    /**
     * Потоковый нейросетевой инференс Silero V5.
     *
     * 1. Устранены рассинхронизация памяти и застрявшие статические тензоры: тензоры создаются
     *    с локальным временем жизни (.use { }) непосредственно перед вызовом session.run().
     * 2. Zero-Allocation State Flow: обновлённое скрытое состояние вычитывается напрямую
     *    из нативного буфера OnnxTensor в предвыделенный массив stateBuffer без создания
     *    Java-объектов Array<Array<FloatArray>>.
     */
    private fun evaluateNeural(
        window: ShortArray
    ): Float {

        val env =
            ortEnvironment
                ?: throw IllegalStateException(
                    "Silero V5 environment is unavailable"
                )

        val session =
            ortSession
                ?: throw IllegalStateException(
                    "Silero V5 session is unavailable"
                )

        // 1. Формируем входной буфер: [контекст прошлых 64 сэмплов] + [текущее окно 512 сэмплов]
        inputFloatBuffer.clear()
        inputFloatBuffer.put(contextBuffer)

        for (i in 0 until WINDOW_SIZE_SAMPLES) {
            inputFloatBuffer.put(window[i] / 32768.0f)
        }
        inputFloatBuffer.flip()

        // 2. Формируем тензор скрытого состояния из актуального вектора памяти
        stateFloatBuffer.clear()
        stateFloatBuffer.put(stateBuffer)
        stateFloatBuffer.flip()

        // 3. Детерминированное локальное создание тензоров с гарантированным освобождением нативной памяти
        return OnnxTensor.createTensor(
            env,
            inputFloatBuffer,
            longArrayOf(1L, MODEL_INPUT_SAMPLES.toLong())
        ).use { inputTensor ->
            OnnxTensor.createTensor(
                env,
                stateFloatBuffer,
                longArrayOf(2L, 1L, 128L)
            ).use { stateTensor ->
                OnnxTensor.createTensor(
                    env,
                    longArrayOf(16000L)
                ).use { srTensor ->

                    val inputs =
                        mapOf(
                            "input" to inputTensor,
                            "state" to stateTensor,
                            "sr" to srTensor
                        )

                    session.run(inputs).use { result ->

                        // Извлечение вероятности речи без создания промежуточных Java-массивов
                        val outputTensor =
                            result.get(0) as OnnxTensor
                        val outputProb =
                            outputTensor.floatBuffer.get(0)

                        // Безаллокационное обновление скрытого состояния памяти напрямую из нативного буфера
                        val nextStateTensor =
                            result.get(1) as OnnxTensor
                        val nextStateBuf =
                            nextStateTensor.floatBuffer

                        nextStateBuf.position(0)
                        nextStateBuf.get(stateBuffer)

                        // Сохранение последних 64 сэмплов текущего окна в качестве контекста для следующего шага
                        val contextStart =
                            WINDOW_SIZE_SAMPLES - CONTEXT_SIZE_SAMPLES

                        for (i in 0 until CONTEXT_SIZE_SAMPLES) {
                            contextBuffer[i] =
                                window[contextStart + i] / 32768.0f
                        }

                        outputProb
                    }
                }
            }
        }
    }

    private fun closeResourcesLocked() {
        runCatching { ortSession?.close() }
        runCatching { ortEnvironment?.close() }

        ortSession = null
        ortEnvironment = null
    }

    /**
     * Адаптивный RMS-фоллбэк с динамическим подавлением эха динамика (Проблема №5).
     *
     * Устранён жесткий порог 0.012 (-38 dBFS), вызывавший ложный Barge-In от собственного динамика.
     * Реализовано динамическое отслеживание шума окружения и требование превышения SNR.
     */
    private fun evaluateFallbackRms(
        window: ShortArray
    ): Float {
        var sumSq = 0.0

        for (s in window) {
            val f = s / 32768.0
            sumSq += f * f
        }

        val rms =
            sqrt(
                (sumSq + 1e-9) / window.size
            ).toFloat()

        // Адаптивное отслеживание фонового уровня шума
        if (rms < estimatedNoiseFloorRms * 1.5f) {
            estimatedNoiseFloorRms = estimatedNoiseFloorRms * 0.95f + rms * 0.05f
        } else {
            estimatedNoiseFloorRms = min(estimatedNoiseFloorRms * 1.01f, 0.05f)
        }
        estimatedNoiseFloorRms = estimatedNoiseFloorRms.coerceIn(0.005f, 0.06f)

        // Динамический порог SNR: требует превышения полезного сигнала над фоновым эхом
        val dynamicThreshold = max(0.032f, estimatedNoiseFloorRms * 2.2f)
        val dynamicRange = max(0.040f, estimatedNoiseFloorRms * 3.0f)

        return (
            (rms - dynamicThreshold) / dynamicRange
        ).coerceIn(0.0f, 1.0f)
    }

    fun resetState() = vadLock.withLock {
        stateBuffer.fill(0f)
        contextBuffer.fill(0f)

        inputFloatBuffer.clear()
        stateFloatBuffer.clear()

        accumulatorCount = 0
        speechStartStreak = 0
        speechEndStreak = 0

        estimatedNoiseFloorRms = 0.015f

        _isSpeechDetected.value = false
        _speechProbability.value = 0f
    }
}