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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
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
                CONTEXT_SIZE_SAMPLES

        private const val MODEL_PATH =
            "models/silero_vad_v5_quant.onnx"

        private const val MIN_VALID_MODEL_BYTES =
            500_000L
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

    // Состояние модели Silero V5: тензор [2, 1, 128]
    private val stateBuffer =
        FloatArray(
            2 * 1 * 128
        )

    private val contextBuffer =
        FloatArray(
            CONTEXT_SIZE_SAMPLES
        )

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
                stateBuffer.size * 4
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

    private var persistentInputTensor:
        OnnxTensor? = null

    private var persistentStateTensor:
        OnnxTensor? = null

    private var persistentSrTensor:
        OnnxTensor? = null

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

                        inputFloatBuffer.clear()
                        stateFloatBuffer.clear()

                        stateFloatBuffer.put(
                            stateBuffer
                        )
                        stateFloatBuffer.flip()

                        persistentInputTensor =
                            OnnxTensor.createTensor(
                                env,
                                inputFloatBuffer,
                                longArrayOf(
                                    1L,
                                    MODEL_INPUT_SAMPLES
                                        .toLong()
                                )
                            )

                        persistentStateTensor =
                            OnnxTensor.createTensor(
                                env,
                                stateFloatBuffer,
                                longArrayOf(
                                    2L,
                                    1L,
                                    128L
                                )
                            )

                        persistentSrTensor =
                            OnnxTensor.createTensor(
                                env,
                                longArrayOf(
                                    16000L
                                )
                            )

                        inputFloatBuffer.clear()
                        repeat(
                            MODEL_INPUT_SAMPLES
                        ) {
                            inputFloatBuffer.put(0f)
                        }
                        inputFloatBuffer.flip()

                        stateFloatBuffer.clear()
                        repeat(
                            stateBuffer.size
                        ) {
                            stateFloatBuffer.put(0f)
                        }
                        stateFloatBuffer.flip()

                        val inputTensor =
                            persistentInputTensor
                                ?: throw IllegalStateException(
                                    "Silero V5 input tensor was not created"
                                )

                        val stateTensor =
                            persistentStateTensor
                                ?: throw IllegalStateException(
                                    "Silero V5 state tensor was not created"
                                )

                        val srTensor =
                            persistentSrTensor
                                ?: throw IllegalStateException(
                                    "Silero V5 sample-rate tensor was not created"
                                )

                        session.run(
                            mapOf(
                                "input" to inputTensor,
                                "state" to stateTensor,
                                "sr" to srTensor
                            )
                        ).use { result ->

                            if (result.size() < 2) {
                                throw IllegalStateException(
                                    "Silero V5 warm-up returned ${result.size()} outputs"
                                )
                            }

                            val probability =
                                result.get(0).value

                            if (probability !is Array<*>) {
                                throw IllegalStateException(
                                    "Silero V5 probability output has unexpected type: ${probability?.javaClass}"
                                )
                            }

                            val returnedState =
                                result.get(1).value

                            if (returnedState !is Array<*>) {
                                throw IllegalStateException(
                                    "Silero V5 state output has unexpected type: ${returnedState?.javaClass}"
                                )
                            }
                        }

                        stateBuffer.fill(0f)
                        contextBuffer.fill(0f)

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
        val prob = try {
            if (
                isNeuralModelLoaded &&
                ortSession != null &&
                ortEnvironment != null
            ) {
                evaluateNeural(window)
            } else {
                evaluateFallbackRms(window)
            }
        } catch (t: Throwable) {
            logger.e("SileroVadDetector: V5 inference failed; switching to RMS fallback", t)
            isNeuralModelLoaded = false
            closeResourcesLocked()
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
                // Адаптивное подавление шума (Hangover) по стандарту ITU-T G.729B:
                // Вместо мгновенного сброса счётчика тишины при единичном щелчке или вздохе,
                // плавно снижаем счётчик, исключая зависание VAD в шумной среде.
                speechEndStreak = maxOf(0, speechEndStreak - 2)
            }
        }
    }

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

        val inputTensor =
            persistentInputTensor
                ?: throw IllegalStateException(
                    "Silero V5 input tensor is unavailable"
                )

        val stateTensor =
            persistentStateTensor
                ?: throw IllegalStateException(
                    "Silero V5 state tensor is unavailable"
                )

        val srTensor =
            persistentSrTensor
                ?: throw IllegalStateException(
                    "Silero V5 sample-rate tensor is unavailable"
                )

        inputFloatBuffer.clear()
        inputFloatBuffer.put(contextBuffer)

        for (i in 0 until WINDOW_SIZE_SAMPLES) {
            inputFloatBuffer.put(window[i] / 32768.0f)
        }
        inputFloatBuffer.flip()

        stateFloatBuffer.clear()
        stateFloatBuffer.put(stateBuffer)
        stateFloatBuffer.flip()

        return try {

            val inputs =
                mapOf(
                    "input" to inputTensor,
                    "state" to stateTensor,
                    "sr" to srTensor
                )

            session.run(inputs).use { result ->

                val outputProb =
                    (
                        result.get(0).value as Array<FloatArray>
                    )[0][0]

                @Suppress("UNCHECKED_CAST")
                val nextState =
                    result.get(1).value as Array<Array<FloatArray>>

                var idx = 0
                for (i in 0 until 2) {
                    for (j in 0 until 128) {
                        stateBuffer[idx++] = nextState[i][0][j]
                    }
                }

                val contextStart =
                    WINDOW_SIZE_SAMPLES - CONTEXT_SIZE_SAMPLES

                for (i in 0 until CONTEXT_SIZE_SAMPLES) {
                    contextBuffer[i] =
                        window[contextStart + i] / 32768.0f
                }

                outputProb
            }

        } catch (e: Exception) {
            logger.e(
                "SileroVadDetector: V5 inference failure",
                e
            )

            isNeuralModelLoaded = false
            closeResourcesLocked()

            throw IllegalStateException(
                "Silero V5 inference failed",
                e
            )
        }
    }

    private fun closeResourcesLocked() {
        runCatching { persistentInputTensor?.close() }
        runCatching { persistentStateTensor?.close() }
        runCatching { persistentSrTensor?.close() }
        runCatching { ortSession?.close() }

        persistentInputTensor = null
        persistentStateTensor = null
        persistentSrTensor = null
        ortSession = null
        ortEnvironment = null
    }

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

        return (
            (rms - 0.012f) / 0.045f
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

        _isSpeechDetected.value = false
        _speechProbability.value = 0f
    }
}