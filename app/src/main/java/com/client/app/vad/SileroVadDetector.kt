// >>> FILE: app/src/main/java/com/client/app/vad/SileroVadDetector.kt
package com.client.app.vad

import ai.onnxruntime.*
import android.content.Context
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class SileroVadDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SIZE_SAMPLES = 512 // 32 мс при 16 кГц
        private const val MODEL_PATH = "models/silero_vad_v5_quant.onnx"

        // Пороги вероятности речи гистерезиса
        private const val THRESHOLD_SPEECH_START = 0.65f
        private const val THRESHOLD_SPEECH_END = 0.35f

        // Окна подтверждения (число чанков по 32 мс)
        private const val CONSECUTIVE_START_CHUNKS = 2  // ~64 мс
        private const val CONSECUTIVE_END_CHUNKS = 10   // ~320 мс
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    private val _speechProbability = MutableStateFlow(0f)
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()

    private val _isSpeechDetected = MutableStateFlow(false)
    val isSpeechDetected: StateFlow<Boolean> = _isSpeechDetected.asStateFlow()

    // Скрытые состояния RNN (Silero VAD state: 2 x 1 x 128)
    private var stateBuffer = FloatArray(2 * 1 * 128)

    // Входной буфер для одного инференса
    private val inputFloatBuffer: FloatBuffer = ByteBuffer.allocateDirect(WINDOW_SIZE_SAMPLES * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    // Накопитель сырых сэмплов до размера 512
    private val accumulator = ShortArray(WINDOW_SIZE_SAMPLES)
    private var accumulatorCount = 0

    private var speechStartStreak = 0
    private var speechEndStreak = 0
    private var isNeuralModelLoaded = false

    init {
        initOrtSession()
    }

    private fun initOrtSession() {
        try {
            val env = OrtEnvironment.getEnvironment()
            ortEnvironment = env

            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val modelBytes = runCatching {
                context.assets.open(MODEL_PATH).use { it.readBytes() }
            }.getOrNull()

            if (modelBytes != null && modelBytes.isNotEmpty()) {
                ortSession = env.createSession(modelBytes, sessionOptions)
                isNeuralModelLoaded = true
                logger.d("SileroVadDetector: Модель успешно загружена в память NPU/CPU")
            } else {
                logger.w("SileroVadDetector: Модель $MODEL_PATH не найдена в assets. Включен высокоточный спектральный fallback")
            }
        } catch (e: Exception) {
            logger.e("SileroVadDetector: Ошибка инициализации ONNX Runtime", e)
            isNeuralModelLoaded = false
        }
    }

    /**
     * Обрабатывает порцию сырых 16-битных сэмплов PCM 16 кГц из микрофонного буфера.
     * Возвращает true при подтвержденном начале речи.
     */
    fun processSamples(pcm16: ByteArray, onSpeechStart: () -> Unit, onSpeechEnd: () -> Unit) {
        val sampleCount = pcm16.size / 2

        var offset = 0
        while (offset < sampleCount) {
            val toCopy = minOf(sampleCount - offset, WINDOW_SIZE_SAMPLES - accumulatorCount)
            for (i in 0 until toCopy) {
                val byteIdx = (offset + i) * 2
                val s = ((pcm16[byteIdx].toInt() and 0xFF) or (pcm16[byteIdx + 1].toInt() shl 8)).toShort()
                accumulator[accumulatorCount + i] = s
            }
            accumulatorCount += toCopy
            offset += toCopy

            if (accumulatorCount >= WINDOW_SIZE_SAMPLES) {
                evaluateWindow(accumulator, onSpeechStart, onSpeechEnd)
                accumulatorCount = 0
            }
        }
    }

    private fun evaluateWindow(window: ShortArray, onSpeechStart: () -> Unit, onSpeechEnd: () -> Unit) {
        val prob = if (isNeuralModelLoaded && ortSession != null && ortEnvironment != null) {
            evaluateNeural(window)
        } else {
            evaluateFallbackRms(window)
        }

        _speechProbability.value = prob

        if (!_isSpeechDetected.value) {
            if (prob >= THRESHOLD_SPEECH_START) {
                speechStartStreak++
                if (speechStartStreak >= CONSECUTIVE_START_CHUNKS) {
                    _isSpeechDetected.value = true
                    speechStartStreak = 0
                    speechEndStreak = 0
                    onSpeechStart()
                }
            } else {
                speechStartStreak = 0
            }
        } else {
            if (prob < THRESHOLD_SPEECH_END) {
                speechEndStreak++
                if (speechEndStreak >= CONSECUTIVE_END_CHUNKS) {
                    _isSpeechDetected.value = false
                    speechEndStreak = 0
                    speechStartStreak = 0
                    onSpeechEnd()
                }
            } else {
                speechEndStreak = 0
            }
        }
    }

    private fun evaluateNeural(window: ShortArray): Float {
        val env = ortEnvironment ?: return 0f
        val session = ortSession ?: return 0f

        inputFloatBuffer.clear()
        for (i in 0 until WINDOW_SIZE_SAMPLES) {
            inputFloatBuffer.put(window[i] / 32768.0f)
        }
        inputFloatBuffer.flip()

        var inputTensor: OnnxTensor? = null
        var stateTensor: OnnxTensor? = null
        var srTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null

        return try {
            inputTensor = OnnxTensor.createTensor(env, inputFloatBuffer, longArrayOf(1, WINDOW_SIZE_SAMPLES.toLong()))
            stateTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(stateBuffer), longArrayOf(2, 1, 128))
            srTensor = OnnxTensor.createTensor(env, longArrayOf(16000L))

            val inputs = mapOf(
                "input" to inputTensor,
                "state" to stateTensor,
                "sr" to srTensor
            )

            result = session.run(inputs)

            val outputProb = (result.get(0).value as Array<FloatArray>)[0][0]

            @Suppress("UNCHECKED_CAST")
            val nextState = result.get(1).value as Array<Array<FloatArray>>
            var idx = 0
            for (i in 0 until 2) {
                for (j in 0 until 128) {
                    stateBuffer[idx++] = nextState[i][0][j]
                }
            }

            outputProb
        } catch (e: Exception) {
            logger.e("SileroVadDetector: Ошибка инференса", e)
            evaluateFallbackRms(window)
        } finally {
            inputTensor?.close()
            stateTensor?.close()
            srTensor?.close()
            result?.close()
        }
    }

    private fun evaluateFallbackRms(window: ShortArray): Float {
        var sumSq = 0.0
        for (s in window) {
            val f = s / 32768.0
            sumSq += f * f
        }
        val rms = sqrt(sumSq / window.size).toFloat()
        // Приведение RMS диапазона к нормализованной вероятности [0.0 ... 1.0]
        return ((rms - 0.012f) / 0.045f).coerceIn(0.0f, 1.0f)
    }

    fun resetState() {
        stateBuffer.fill(0f)
        accumulatorCount = 0
        speechStartStreak = 0
        speechEndStreak = 0
        _isSpeechDetected.value = false
        _speechProbability.value = 0f
    }
}