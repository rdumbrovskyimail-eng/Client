package com.client.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Process
import com.client.app.haptics.HapticBargeInManager
import com.client.app.util.AppLogger
import com.client.app.vad.SileroVadDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Строго упорядоченные события микрофонного тракта.
 *
 * AAD ON:
 * Audio* -> SpeechEnd -> StreamStop
 *
 * Manual VAD:
 * SpeechStart -> PreRoll* -> CurrentAudio -> Audio* -> SpeechEnd -> StreamStop
 */
sealed interface AudioStreamEvent {
    class Audio(val pcm: ByteArray) : AudioStreamEvent
    data object SpeechStart : AudioStreamEvent
    data object SpeechEnd : AudioStreamEvent
    data object StreamStop : AudioStreamEvent
}

enum class CaptureShutdownResult {
    GRACEFUL_LOSSLESS,
    FORCED_TIMEOUT
}

private data class RouteTransitionRequest(
    val profile: RouteProfile,
    val generation: Long
)

@Singleton
class NativeAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: NativeAudioBridge,
    private val vadDetector: SileroVadDetector,
    private val hapticManager: HapticBargeInManager,
    private val router: AudioDeviceRouter,
    private val logger: AppLogger
) {
    companion object {
        private const val BURST_BYTES = 160 * 2 // 10 мс @ 16 кГц PCM16
        private const val PLAYBACK_GRACE_PERIOD_MS = 300L
        private const val BARGE_IN_DEBOUNCE_MS = 500L
        private const val PRE_ROLL_FRAMES_CAPACITY = 20 // До 200 мс доступного предзаписанного контекста
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _outLevel = MutableStateFlow(0f)
    val outLevel: StateFlow<Float> = _outLevel.asStateFlow()

    private val _bargeInEvents = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bargeInEvents: SharedFlow<Unit> = _bargeInEvents.asSharedFlow()

    private val _focusLost = MutableSharedFlow<Boolean>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val focusLost: SharedFlow<Boolean> = _focusLost.asSharedFlow()

    // Основной FIFO канал с гарантией доставки (SUSPEND исключает вытеснение управляющих сигналов)
    private val _micOutput = Channel<AudioStreamEvent>(
        capacity = 512,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val micOutput: ReceiveChannel<AudioStreamEvent> = _micOutput

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in NativeAudioEngine", throwable)
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    private val audioLifecycleMutex = Mutex()
    private val captureDirectMutex = Mutex()

    private val routeTransitionChannel = Channel<RouteTransitionRequest>(Channel.CONFLATED)

    /*
     * Generation физического жизненного цикла аудиоядра.
     * Защищает от запоздалых коллбэков маршрутизации старых экземпляров.
     * НЕ является сессионной эпохой Gemini WebSocket.
     */
    private val engineGeneration = AtomicLong(0)

    @Volatile
    private var streamStopGeneration: Long = -1L

    // Режим работы VAD: true = Hybrid VAD (AAD ON), false = Manual VAD (AAD OFF)
    @Volatile var isAadMode: Boolean = true

    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7)
    val spectrumUniforms = AtomicReference(FloatArray(5))

    private val bufferPool = ArrayDeque<ByteArray>(32).apply {
        repeat(32) { add(ByteArray(BURST_BYTES)) }
    }
    private val poolLock = Any()
    
    // Циклический буфер контекста (до 200 мс)
    private val leadInBuffer = ArrayDeque<ByteArray>(32)

    @Volatile private var lastPlaybackStartMs = 0L
    @Volatile private var lastBargeInMs = 0L
    
    @Volatile var isBargeInActive = false; private set

    init {
        engineScope.launch {
            for (req in routeTransitionChannel) {
                applyRouteInternal(req)
            }
        }
    }

    /* ═════════════════════════ AUDIO FOCUS ═════════════════════════ */

    private fun requestAudioFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                            logger.w("NativeAudioEngine: AudioFocus потерян ($change)")
                            _focusLost.tryEmit(true)
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            logger.d("NativeAudioEngine: AudioFocus восстановлен")
                            _focusLost.tryEmit(false)
                        }
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change ->
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                        _focusLost.tryEmit(true)
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        _focusLost.tryEmit(false)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    /* ═════════════════════════ ЖИЗНЕННЫЙ ЦИКЛ ═════════════════════════ */

    suspend fun start(): Boolean = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_isCapturing.value) return@withContext true

            if (!requestAudioFocus()) {
                logger.e("NativeAudioEngine: Сбой запроса AudioFocus")
                return@withContext false
            }

            vadDetector.prepare()
            vadDetector.resetState()
            isBargeInActive = false

            val currentGen = engineGeneration.get()
            streamStopGeneration = -1L

            router.start { profile ->
                routeTransitionChannel.trySend(RouteTransitionRequest(profile, currentGen))
            }

            val profile = router.currentProfile.value
            val inited = captureDirectMutex.withLock {
                bridge.initAudioRoute(
                    isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                    sampleRate = profile.sampleRateOut,
                    inputDeviceId = profile.inputDeviceId,
                    outputDeviceId = profile.outputDeviceId
                )
            }
            if (!inited) {
                abandonAudioFocus()
                return@withContext false
            }

            vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)

            val started = captureDirectMutex.withLock {
                bridge.startAudio()
            }
            if (!started) {
                logger.e("NativeAudioEngine: Сбой запуска AAudio")
                abandonAudioFocus()
                return@withContext false
            }

            _isCapturing.value = true
            _isPlaying.value = true

            startLoops()
            true
        }
    }

    private fun startLoops() {
        captureJob = engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var isSpeechActiveManual = false

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = captureDirectMutex.withLock {
                    if (!_isCapturing.value) return@withLock 0
                    bridge.readCaptureDirect(captureDirectBuffer, BURST_BYTES)
                }

                if (bytesRead > 0) {
                    val frame = obtainBuffer()
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(frame, 0, bytesRead)

                    val validPcm = if (bytesRead == BURST_BYTES) frame else frame.copyOf(bytesRead)

                    val now = System.currentTimeMillis()
                    val currentOut = _outLevel.value
                    val currentMic = _micLevel.value
                    val isBluetooth = router.currentProfile.value.path == AudioRoutePath.CMF_BUDS_WIRELESS
                    val isAiRendering = !isBluetooth && (currentOut > 0.04f)

                    // 1. Детекция VAD выполняется первой для детерминированного порядка событий
                    var speechStartedOnFrame = false
                    var speechEndedOnFrame = false

                    vadDetector.processSamples(
                        pcm16 = validPcm,
                        onSpeechStart = { speechStartedOnFrame = true },
                        onSpeechEnd = { speechEndedOnFrame = true }
                    )

                    // Передача аудиокадра между корутинами использует одну immutable ByteArray-копию на полный 10-ms frame
                    val currentAudioBytes = if (validPcm === frame) frame.copyOf(bytesRead) else validPcm

                    // 2. Разделение продюсера по режимам (AAD ON vs Manual VAD)
                    if (isAadMode) {
                        // ── РЕЖИМ 1: AAD = ON (Hybrid VAD) ──
                        // Непрерывный поток аудио без дублирования пре-ролла
                        if (speechStartedOnFrame) {
                            if (isAiRendering) {
                                val echoThreshold = maxOf(0.12f, currentOut * 0.42f)
                                val canBargeIn = (now - lastPlaybackStartMs > PLAYBACK_GRACE_PERIOD_MS) &&
                                                 (now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS) &&
                                                 (currentMic > echoThreshold)

                                if (canBargeIn) {
                                    lastBargeInMs = now
                                    isBargeInActive = true
                                    bridge.flushPlayback()
                                    hapticManager.triggerBargeIn()
                                    _bargeInEvents.tryEmit(Unit)

                                    // Выталкиваем придержанный буфер эхоподавления (не отправлялся ранее)
                                    val preRoll = mutableListOf<ByteArray>()
                                    synchronized(poolLock) {
                                        while (leadInBuffer.isNotEmpty()) {
                                            preRoll.add(leadInBuffer.removeFirst())
                                        }
                                    }
                                    for (pf in preRoll) {
                                        _micOutput.send(AudioStreamEvent.Audio(pf))
                                    }
                                }
                            } else if (currentOut > 0.04f) {
                                if (now - lastPlaybackStartMs > 180L && now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS) {
                                    lastBargeInMs = now
                                    isBargeInActive = true
                                    bridge.flushPlayback()
                                    bridge.triggerBargeInEarcon()
                                    hapticManager.triggerBargeIn()
                                    _bargeInEvents.tryEmit(Unit)
                                }
                            }
                        }

                        if (isBluetooth || !isAiRendering || isBargeInActive) {
                            _micOutput.send(AudioStreamEvent.Audio(currentAudioBytes))
                        } else {
                            synchronized(poolLock) {
                                leadInBuffer.addLast(currentAudioBytes)
                                val maxPreRoll = router.currentProfile.value.leadInBufferSizeFrames / 160
                                while (leadInBuffer.size > maxPreRoll) {
                                    leadInBuffer.removeFirst()
                                }
                            }
                        }

                        if (speechEndedOnFrame) {
                            _micOutput.send(AudioStreamEvent.SpeechEnd)
                        }
                    } else {
                        // ── РЕЖИМ 2: AAD = OFF (Manual VAD) ──
                        // В тишине кадры не отправляются в канал, а только наполняют leadInBuffer
                        if (speechStartedOnFrame) {
                            val preRoll = mutableListOf<ByteArray>()
                            synchronized(poolLock) {
                                while (leadInBuffer.isNotEmpty()) {
                                    preRoll.add(leadInBuffer.removeFirst())
                                }
                            }

                            // SpeechStart идет строго первым
                            _micOutput.send(AudioStreamEvent.SpeechStart)

                            // До 200 мс пре-ролла отправляются следом
                            for (pf in preRoll) {
                                _micOutput.send(AudioStreamEvent.Audio(pf))
                            }

                            _micOutput.send(AudioStreamEvent.Audio(currentAudioBytes))
                            isSpeechActiveManual = true
                        } else if (isSpeechActiveManual) {
                            _micOutput.send(AudioStreamEvent.Audio(currentAudioBytes))
                        } else {
                            // Сохраняем до 200 мс контекста в тишине без отправки в FIFO
                            synchronized(poolLock) {
                                leadInBuffer.addLast(currentAudioBytes)
                                while (leadInBuffer.size > PRE_ROLL_FRAMES_CAPACITY) {
                                    leadInBuffer.removeFirst()
                                }
                            }
                        }

                        if (speechEndedOnFrame) {
                            _micOutput.send(AudioStreamEvent.SpeechEnd)
                            isSpeechActiveManual = false
                        }
                    }

                    recycleBuffer(frame)
                } else {
                    delay(2)
                }
            }
        }

        spectrumJob = engineScope.launch {
            var tick = 0
            while (isActive) {
                bridge.getSpectrumData(spectrumRawData)
                val updated = FloatArray(5)
                System.arraycopy(spectrumRawData, 0, updated, 0, 5)
                spectrumUniforms.set(updated)

                if (tick++ % 4 == 0) {
                    _micLevel.value = (spectrumRawData[5] * 3.5f).coerceIn(0f, 1f)
                    _outLevel.value = (spectrumRawData[6] * 3.5f).coerceIn(0f, 1f)
                }
                delay(8)
            }
        }
    }

    private suspend fun applyRouteInternal(
        req: RouteTransitionRequest
    ) = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            val currentGeneration = engineGeneration.get()

            if (req.generation != currentGeneration) {
                logger.d(
                    "NativeAudioEngine: stale route ignored " +
                        "reqGen=${req.generation}, " +
                        "currentGen=$currentGeneration"
                )
                return@withContext
            }

            if (!_isPlaying.value && !_isCapturing.value) {
                logger.d(
                    "NativeAudioEngine: route ignored — engine stopped"
                )
                return@withContext
            }

            captureDirectMutex.withLock {
                bridge.stopAudio()

                val success = bridge.initAudioRoute(
                    isBluetooth = req.profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                    sampleRate = req.profile.sampleRateOut,
                    inputDeviceId = req.profile.inputDeviceId,
                    outputDeviceId = req.profile.outputDeviceId
                )

                if (!success) {
                    logger.e("NativeAudioEngine: route reinitialization failed")
                    return@withLock
                }

                vadDetector.setThresholds(
                    req.profile.vadThresholdStart,
                    req.profile.vadThresholdEnd
                )

                if (_isCapturing.value) {
                    bridge.startAudio()
                }
            }
        }
    }

    private suspend fun cancelAndJoinBounded(
        job: Job,
        timeoutMs: Long
    ): Boolean {
        if (!job.isActive) return true
        job.cancel()
        return withTimeoutOrNull(timeoutMs) {
            job.join()
            true
        } ?: false
    }

    private suspend fun enqueueStreamStopOnce(
        generation: Long
    ): Boolean {
        if (streamStopGeneration == generation) {
            return true
        }

        val enqueued = withTimeoutOrNull(500L) {
            _micOutput.send(AudioStreamEvent.StreamStop)
            true
        } ?: false

        if (enqueued) {
            streamStopGeneration = generation
            return true
        }

        logger.w("NativeAudioEngine: StreamStop enqueue timeout for generation=$generation")

        _micOutput.tryReceive()

        if (_micOutput.trySend(AudioStreamEvent.StreamStop).isSuccess) {
            streamStopGeneration = generation
            return true
        }

        return false
    }

    /**
     * Bounded Graceful Shutdown продюсера захвата:
     * - Идемпотентность по состоянию _isCapturing.
     * - Ожидание завершения продюсера ограничено gracefulTimeoutMs.
     * - Bounded cancellation (100 мс) при превышении таймаута без вечного зависания.
     * - Очистка буфера контекста после остановки продюсера.
     * - Идемпотентная постановка StreamStop с привязкой к generation.
     */
    suspend fun stopCaptureGraceful(
        gracefulTimeoutMs: Long = 1500L
    ): CaptureShutdownResult {
        var shutdownStatus = CaptureShutdownResult.GRACEFUL_LOSSLESS

        _isCapturing.value = false

        val job = captureJob

        if (job != null && job.isActive) {
            val gracefulCompleted = withTimeoutOrNull(gracefulTimeoutMs) {
                job.join()
                true
            } ?: false

            if (!gracefulCompleted) {
                logger.w("NativeAudioEngine: producer graceful shutdown timeout=${gracefulTimeoutMs}ms")
                shutdownStatus = CaptureShutdownResult.FORCED_TIMEOUT

                val cancelledAndJoined = cancelAndJoinBounded(
                    job = job,
                    timeoutMs = 100L
                )

                if (!cancelledAndJoined) {
                    logger.e("NativeAudioEngine: captureJob did not terminate after bounded cancellation")
                }
            }
        }

        captureJob = null

        synchronized(poolLock) {
            leadInBuffer.clear()
        }

        val currentGeneration = engineGeneration.get()

        if (!enqueueStreamStopOnce(currentGeneration)) {
            shutdownStatus = CaptureShutdownResult.FORCED_TIMEOUT
            logger.e("NativeAudioEngine: unable to enqueue StreamStop for generation=$currentGeneration")
        }

        return shutdownStatus
    }

    suspend fun stop() = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!_isCapturing.value && !_isPlaying.value) {
                engineGeneration.incrementAndGet()
                streamStopGeneration = -1L

                while (routeTransitionChannel.tryReceive().isSuccess) {
                    // drain stale route events
                }

                return@withContext
            }

            _isCapturing.value = false
            _isPlaying.value = false

            engineGeneration.incrementAndGet()
            streamStopGeneration = -1L

            val currentCaptureJob = captureJob

            if (currentCaptureJob != null && currentCaptureJob.isActive) {
                currentCaptureJob.cancel()
                val stopped = withTimeoutOrNull(500L) {
                    currentCaptureJob.join()
                    true
                } ?: false

                if (!stopped) {
                    logger.e("NativeAudioEngine: captureJob did not terminate during physical stop")
                }
            }

            captureJob = null

            val currentSpectrumJob = spectrumJob

            if (currentSpectrumJob != null && currentSpectrumJob.isActive) {
                currentSpectrumJob.cancel()
                val stopped = withTimeoutOrNull(500L) {
                    currentSpectrumJob.join()
                    true
                } ?: false

                if (!stopped) {
                    logger.e("NativeAudioEngine: spectrumJob did not terminate during physical stop")
                }
            }

            spectrumJob = null

            captureDirectMutex.withLock {
                bridge.stopAudio()
            }

            abandonAudioFocus()
            router.stop()
            vadDetector.resetState()

            _micLevel.value = 0f
            _outLevel.value = 0f
            isBargeInActive = false

            synchronized(poolLock) {
                leadInBuffer.clear()
            }

            while (_micOutput.tryReceive().isSuccess) {
                // drain stale mic events
            }

            while (routeTransitionChannel.tryReceive().isSuccess) {
                // drain stale route requests
            }
        }
    }

    fun setVolume(volume: Float) = bridge.setVolume(volume.coerceIn(0f, 1f))
    fun setMicGain(gain: Float) = bridge.setMicGain(gain.coerceIn(0.5f, 2.0f))

    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty() || !_isPlaying.value) return
        
        if (isBargeInActive) {
            return
        }

        if (_outLevel.value < 0.02f) {
            lastPlaybackStartMs = System.currentTimeMillis()
        }

        var offset = 0
        var remaining = pcm.size
        var attempts = 0

        while (remaining > 0 && attempts < 8) {
            val written = bridge.writePlaybackByteArray(pcm, offset, remaining)
            if (written >= remaining) break
            if (written > 0) {
                offset += written
                remaining -= written
            }
            attempts++
            Thread.yield()
        }
    }

    fun flushPlayback() {
        bridge.flushPlayback()
        _outLevel.value = 0f
    }

    fun resetBargeInState() {
        isBargeInActive = false
    }

    private fun obtainBuffer(): ByteArray = synchronized(poolLock) {
        if (bufferPool.isNotEmpty()) bufferPool.removeFirst() else ByteArray(BURST_BYTES)
    }

    private fun recycleBuffer(buf: ByteArray) = synchronized(poolLock) {
        if (bufferPool.size < 64) bufferPool.addLast(buf)
    }
}