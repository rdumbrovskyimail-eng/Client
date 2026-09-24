// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioEngine.kt
package com.client.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.client.app.haptics.HapticBargeInManager
import com.client.app.util.AppLogger
import com.client.app.vad.SileroVadDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

sealed interface AudioStreamEvent {

    class Audio(
        val pcm: ByteArray
    ) : AudioStreamEvent

    data object SpeechStart :
        AudioStreamEvent

    data object SpeechEnd :
        AudioStreamEvent

    data object StreamStop :
        AudioStreamEvent
}

enum class CaptureShutdownResult {
    GRACEFUL_LOSSLESS,
    FORCED_TIMEOUT
}

sealed interface AudioFocusEvent {
    data object LossPermanent : AudioFocusEvent
    data object LossTransient : AudioFocusEvent
    data object Gain : AudioFocusEvent
}

private data class RouteTransitionRequest(
    val profile: RouteProfile,
    val generation: Long
)

@Singleton
class NativeAudioEngine @Inject constructor(
    @ApplicationContext
    private val context: Context,

    private val bridge: NativeAudioBridge,

    private val vadDetector: SileroVadDetector,

    private val hapticManager:
        HapticBargeInManager,

    private val router:
        AudioDeviceRouter,

    private val logger:
        AppLogger
) {

    companion object {

        private const val BURST_BYTES =
            160 * 2

        // По стандартам ITU-T G.168: защитный интервал для подавления переходных процессов включения ЦАП
        private const val PLAYBACK_GRACE_PERIOD_MS =
            350L

        private const val BARGE_IN_DEBOUNCE_MS =
            500L

        // Минимальное время удержания гейта перебивания (Barge-In)
        private const val BARGE_IN_MIN_HOLD_MS =
            250L

        // Гарантированный предельный дедлайн локального восстановления
        private const val BARGE_IN_HARD_RECOVERY_MS =
            3000L

        private const val BARGE_IN_RECOVERY_POLL_MS =
            50L

        private const val PRE_ROLL_FRAMES_CAPACITY =
            20

        // Ограничение накопления необработанного захваченного аудио в памяти (~16 секунд 16 кГц PCM16)
        private const val MAX_MIC_OUTPUT_BACKLOG_BYTES =
            512L * 1024L

        // Предельный аппаратный порог замирания записи JNI в ЦАП (1.8 секунды).
        // Предотвращает выбрасывание сэмплов длинных ответов при быстром сетевом наполнении буфера.
        private const val PLAYBACK_WRITE_STALL_TIMEOUT_MS = 1800L
    }

    private val audioManager =
        context.getSystemService(
            Context.AUDIO_SERVICE
        ) as AudioManager

    private var focusRequest:
        AudioFocusRequest? = null

    private val _isCapturing =
        MutableStateFlow(false)

    val isCapturing:
        StateFlow<Boolean> =
        _isCapturing.asStateFlow()

    private val _isPlaying =
        MutableStateFlow(false)

    val isPlaying:
        StateFlow<Boolean> =
        _isPlaying.asStateFlow()

    private val _micLevel =
        MutableStateFlow(0f)

    val micLevel:
        StateFlow<Float> =
        _micLevel.asStateFlow()

    private val _outLevel =
        MutableStateFlow(0f)

    val outLevel:
        StateFlow<Float> =
        _outLevel.asStateFlow()

    private val _bargeInEvents =
        MutableSharedFlow<Unit>(
            replay = 0,
            extraBufferCapacity = 8,
            onBufferOverflow =
                BufferOverflow.DROP_OLDEST
        )

    val bargeInEvents:
        SharedFlow<Unit> =
        _bargeInEvents.asSharedFlow()

    private val _focusEvents =
        MutableStateFlow<AudioFocusEvent>(AudioFocusEvent.Gain)
    val focusEvents:
        StateFlow<AudioFocusEvent> =
        _focusEvents.asStateFlow()

    private val _micOutput =
        Channel<AudioStreamEvent>(
            Channel.UNLIMITED
        )

    private val queuedMicOutputBytes =
        AtomicLong(0L)

    val micOutput:
        ReceiveChannel<AudioStreamEvent> =
        _micOutput

    private val coroutineExceptionHandler =
        CoroutineExceptionHandler {
                _,
                throwable ->
            logger.e(
                "Unhandled coroutine exception in NativeAudioEngine",
                throwable
            )
        }

    private val engineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                coroutineExceptionHandler
        )

    private val captureExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(
                {
                    runCatching {
                        Process.setThreadPriority(
                            Process.THREAD_PRIORITY_URGENT_AUDIO
                        )
                    }.onFailure {
                        logger.w(
                            "NativeAudioEngine: failed to set capture thread priority: ${it.message}"
                        )
                    }
                    runnable.run()
                },
                "NativeAudioCapture"
            )
        }

    private val captureDispatcher =
        captureExecutor.asCoroutineDispatcher()

    private var captureJob:
        Job? = null

    private var spectrumJob:
        Job? = null

    private var healthJob:
        Job? = null

    private val captureInstanceId =
        AtomicLong(0L)

    private val audioLifecycleMutex =
        Mutex()

    private val captureEventLock =
        Any()

    private val captureDirectMutex =
        Mutex()

    private val playbackOperationLock =
        ReentrantLock()

    private val routeTransitionChannel =
        Channel<RouteTransitionRequest>(
            Channel.CONFLATED
        )

    private val engineGeneration =
        AtomicLong(0)

    private val playbackGeneration =
        AtomicLong(1L)

    private val playbackStartGeneration =
        AtomicLong(0L)

    private val playbackDesired = AtomicBoolean(false)
    private val captureDesired = AtomicBoolean(false)

    val currentPlaybackGeneration: Long
        get() = playbackGeneration.get()

    fun setVadThresholds(start: Float, end: Float) {
        val s = start.coerceIn(0.05f, 0.95f)
        val e = end.coerceIn(0.01f, s)
        vadDetector.setThresholds(s, e)
    }

    @Volatile
    private var streamStopGeneration:
        Long = -1L

    @Volatile
    var isAadMode: Boolean = true

    private val captureDirectBuffer =
        ByteBuffer.allocateDirect(
            BURST_BYTES * 4
        ).order(
            ByteOrder.LITTLE_ENDIAN
        )

    private val spectrumRawData =
        FloatArray(7)

    private val spectrumUniformUpdate =
        FloatArray(5)

    val spectrumUniforms =
        AtomicReference(
            FloatArray(5)
        )

    private val bufferPool =
        ArrayDeque<ByteArray>(32).apply {
            repeat(32) {
                add(
                    ByteArray(
                        BURST_BYTES
                    )
                )
            }
        }

    private val poolLock =
        Any()

    private val leadInBuffer =
        ArrayDeque<ByteArray>(32)

    @Volatile
    private var lastPlaybackStartMs =
        0L

    @Volatile
    private var lastBargeInMs =
        0L

    @Volatile
    var isBargeInActive = false
        private set

    @Volatile
    private var bargeInTimestampMs =
        0L

    private var bargeInLeaseJob:
        Job? = null

    init {
        engineScope.launch {
            for (
                req in
                routeTransitionChannel
            ) {
                applyRouteInternal(req)
            }
        }
    }

    private fun requestAudioFocus():
        Boolean {

        val attrs =
            AudioAttributes.Builder()
                .setUsage(
                    AudioAttributes
                        .USAGE_VOICE_COMMUNICATION
                )
                .setContentType(
                    AudioAttributes
                        .CONTENT_TYPE_SPEECH
                )
                .build()

        return if (
            Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
        ) {

            val req =
                AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN
                )
                    .setAudioAttributes(attrs)
                    .setAcceptsDelayedFocusGain(
                        false
                    )
                    .setOnAudioFocusChangeListener {
                        change ->
                        when (change) {

                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {

                                logger.w(
                                    "NativeAudioEngine: AudioFocus потерян ($change)"
                                )

                                _focusEvents.tryEmit(
                                    if (change == AudioManager.AUDIOFOCUS_LOSS) AudioFocusEvent.LossPermanent
                                    else AudioFocusEvent.LossTransient
                                )
                            }

                            AudioManager.AUDIOFOCUS_GAIN -> {

                                logger.d(
                                    "NativeAudioEngine: AudioFocus восстановлен"
                                )

                                _focusEvents.tryEmit(AudioFocusEvent.Gain)
                            }

                            else -> Unit
                        }
                    }
                    .build()

            focusRequest = req

            audioManager.requestAudioFocus(
                req
            ) ==
                AudioManager
                    .AUDIOFOCUS_REQUEST_GRANTED

        } else {

            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                { change ->
                    if (
                        change ==
                            AudioManager.AUDIOFOCUS_LOSS ||
                        change ==
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    ) {

                        _focusEvents.tryEmit(
                            if (change == AudioManager.AUDIOFOCUS_LOSS) AudioFocusEvent.LossPermanent
                            else AudioFocusEvent.LossTransient
                        )

                    } else if (
                        change ==
                            AudioManager.AUDIOFOCUS_GAIN
                    ) {

                        _focusEvents.tryEmit(AudioFocusEvent.Gain)
                    }
                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            ) ==
                AudioManager
                    .AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {

        runCatching {

            if (
                Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.O
            ) {

                focusRequest?.let {
                    audioManager
                        .abandonAudioFocusRequest(it)
                }

                focusRequest = null

            } else {

                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(
                    null
                )
            }
        }
    }

    private fun logActualNativeRoute(profile: RouteProfile, context: String) {
        val actualIn = bridge.getActiveInputDeviceId()
        val actualOut = bridge.getActiveOutputDeviceId()
        val actualPlayRate = bridge.getActualPlaybackSampleRate()
        val actualPlayChannels = bridge.getActualPlaybackChannels()
        val actualPlayFormat = bridge.getActualPlaybackFormat()
        val mmap = bridge.isMmapActive()
        val exclusive = bridge.isExclusiveSharingActive()

        if (profile.inputDeviceId > 0 && actualIn > 0 && actualIn != profile.inputDeviceId) {
            logger.w(
                "NativeAudioEngine: actual input device differs from requested route ($context): requested=${profile.inputDeviceId}, actual=$actualIn"
            )
        }
        if (profile.outputDeviceId > 0 && actualOut > 0 && actualOut != profile.outputDeviceId) {
            logger.w(
                "NativeAudioEngine: actual output device differs from requested route ($context): requested=${profile.outputDeviceId}, actual=$actualOut"
            )
        }

        logger.d(
            "NativeAudioEngine: actual native route ($context): in=$actualIn out=$actualOut playRate=${actualPlayRate}Hz ch=$actualPlayChannels fmt=$actualPlayFormat exclusive=$exclusive mmap=$mmap"
        )
    }

    suspend fun start(): Boolean {
        if (!startPlayback()) return false
        if (startCapture()) return true
        stop()
        return false
    }

    suspend fun startPlayback(): Boolean =
        audioLifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (_isPlaying.value) {
                    playbackDesired.set(true)
                    return@withContext true
                }

                if (!requestAudioFocus()) {
                    playbackDesired.set(false)
                    logger.e(
                        "NativeAudioEngine: Сбой запроса AudioFocus"
                    )
                    return@withContext false
                }

                synchronized(captureEventLock) {
                    streamStopGeneration = -1L
                }

                try {
                    router.start { profile ->
                        routeTransitionChannel
                            .trySend(
                                RouteTransitionRequest(
                                    profile,
                                    engineGeneration.get()
                                )
                            )
                    }
                } catch (t: Throwable) {
                    logger.e("NativeAudioEngine: AudioDeviceRouter startup failed", t)
                    abandonAudioFocus()
                    return@withContext false
                }

                val profile =
                    router.currentProfile.value

                val inited =
                    captureDirectMutex.withLock {
                        playbackOperationLock.lock()
                        try {
                            bridge.initAudioRoute(
                                isBluetooth =
                                    profile.path ==
                                        AudioRoutePath.BLUETOOTH_COMMUNICATION,
                                sampleRate =
                                    profile.sampleRateOut,
                                inputDeviceId =
                                    profile.inputDeviceId,
                                outputDeviceId =
                                    profile.outputDeviceId
                            )
                        } finally {
                            playbackOperationLock.unlock()
                        }
                    }

                if (!inited) {
                    logger.e(
                        "NativeAudioEngine: Сбой инициализации playback route"
                    )
                    router.stop()
                    abandonAudioFocus()
                    return@withContext false
                }

                logActualNativeRoute(profile, "startPlayback")

                vadDetector.setThresholds(
                    profile.vadThresholdStart,
                    profile.vadThresholdEnd
                )

                captureDirectMutex.withLock {
                    playbackOperationLock.lock()
                    try {
                        bridge.flushPlayback(currentPlaybackGeneration)
                    } finally {
                        playbackOperationLock.unlock()
                    }
                }

                val started =
                    captureDirectMutex.withLock {
                        playbackOperationLock.lock()
                        try {
                            bridge.startPlaybackAudio()
                        } finally {
                            playbackOperationLock.unlock()
                        }
                    }

                if (!started) {
                    playbackDesired.set(false)
                    logger.e(
                        "NativeAudioEngine: Сбой запуска playback AAudio"
                    )
                    captureDirectMutex.withLock {
                        playbackOperationLock.lock()
                        try {
                            bridge.stopAudio()
                        } finally {
                            playbackOperationLock.unlock()
                        }
                    }
                    router.stop()
                    abandonAudioFocus()
                    return@withContext false
                }

                _isPlaying.value = true
                playbackDesired.set(true)
                startLoops()
                true
            }
        }

    suspend fun startCapture(): Boolean =
        audioLifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (_isCapturing.value) {
                    captureDesired.set(true)
                    return@withContext true
                }

                val existingCaptureJob = captureJob
                if (existingCaptureJob != null && !existingCaptureJob.isCompleted) {
                    val joined =
                        withTimeoutOrNull(500L) {
                            existingCaptureJob.join()
                            true
                        } ?: false

                    if (!joined) {
                        logger.e(
                            "NativeAudioEngine: previous capture worker is still terminating; refusing a second producer"
                        )
                        return@withContext false
                    }
                }

                if (captureJob != null) {
                    captureJob = null
                }

                if (!_isPlaying.value) {
                    logger.e(
                        "NativeAudioEngine: Нельзя запустить capture без активного playback"
                    )
                    return@withContext false
                }

                if (!vadDetector.prepare()) {
                    logger.e(
                        "NativeAudioEngine: Silero VAD model is unavailable or invalid"
                    )
                    return@withContext false
                }

                vadDetector.resetState()
                resetBargeInState()

                val profile =
                    router.currentProfile.value
                vadDetector.setThresholds(
                    profile.vadThresholdStart,
                    profile.vadThresholdEnd
                )

                synchronized(captureEventLock) {
                    streamStopGeneration = -1L
                }

                val started =
                    captureDirectMutex.withLock {
                        bridge.startCaptureAudio()
                    }

                if (!started) {
                    captureDesired.set(false)
                    logger.e(
                        "NativeAudioEngine: Сбой запуска capture AAudio"
                    )
                    return@withContext false
                }

                _isCapturing.value = true
                captureDesired.set(true)
                synchronized(captureEventLock) {
                    captureInstanceId.incrementAndGet()
                }
                startLoops()
                true
            }
        }

    /**
     * Быстрый безаллокационный расчёт среднеквадратичной мощности (RMS) для 10-мс кванта PCM16.
     */
    private fun calculatePcm16Rms(pcm: ByteArray, bytesCount: Int): Float {
        val sampleCount = bytesCount / 2
        if (sampleCount <= 0) return 0f
        var sumSq = 0.0
        var i = 0
        while (i < bytesCount - 1) {
            val sample = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)
            val s16 = sample.toShort()
            val norm = s16 / 32768.0
            sumSq += norm * norm
            i += 2
        }
        return sqrt(sumSq / sampleCount).toFloat()
    }

    private fun startLoops() {

        if (
            _isCapturing.value &&
            captureJob?.isCompleted != false
        ) {
            val instanceId = captureInstanceId.get()

            captureJob =
                engineScope.launch(captureDispatcher) {

                var isSpeechActiveManual = false
                var zeroReadStreak = 0
                // Фильтр подтверждения перебивания (Double-Talk Hangover по ITU-T G.168 / ACM TOCHI)
                var bargeInCandidateStreak = 0

                try {
                    while (
                        isActive &&
                        _isCapturing.value &&
                        captureDesired.get() &&
                        captureInstanceId.get() == instanceId
                    ) {

                    captureDirectBuffer.clear()

                    val bytesRead =
                        captureDirectMutex.withLock {

                            if (
                                !_isCapturing.value ||
                                !captureDesired.get() ||
                                captureInstanceId.get() != instanceId
                            ) {
                                return@withLock 0
                            }

                            bridge.readCaptureDirect(
                                captureDirectBuffer,
                                BURST_BYTES
                            )
                        }

                    if (bytesRead > 0) {
                        zeroReadStreak = 0

                        if (
                            captureInstanceId.get() != instanceId ||
                            !captureDesired.get() ||
                            !_isCapturing.value
                        ) {
                            continue
                        }

                        val frame =
                            obtainBuffer()

                        captureDirectBuffer.position(
                            0
                        )

                        captureDirectBuffer.get(
                            frame,
                            0,
                            bytesRead
                        )

                        val validPcm =
                            if (
                                bytesRead ==
                                    BURST_BYTES
                            ) {
                                frame
                            } else {
                                frame.copyOf(
                                    bytesRead
                                )
                            }

                        val now =
                            SystemClock
                                .elapsedRealtime()

                        // Мгновенные уровни с нулевым лагом:
                        // 1. Мощность микрофона рассчитывается прямо из текущего кадра
                        val instantaneousMic = maxOf(
                            calculatePcm16Rms(validPcm, bytesRead),
                            bridge.getMicRms()
                        )
                        // 2. Мощность динамика считывается атомарно из C++ движка
                        val instantaneousOut = bridge.getOutRms()

                        val isBluetooth =
                            router
                                .currentProfile
                                .value
                                .path ==
                                AudioRoutePath
                                    .BLUETOOTH_COMMUNICATION

                        val isAiRendering =
                            !isBluetooth && (instantaneousOut > 0.035f)

                        var speechStartedOnFrame =
                            false

                        var speechEndedOnFrame =
                            false

                        if (captureInstanceId.get() != instanceId || !captureDesired.get() || !_isCapturing.value) {
                            continue
                        }

                        vadDetector.processSamples(
                            pcm16 = validPcm,
                            onSpeechStart = {
                                speechStartedOnFrame =
                                    true
                            },

                            onSpeechEnd = {
                                speechEndedOnFrame =
                                    true
                            }
                        )

                        val currentAudioBytes =
                            validPcm

                        if (isAadMode) {

                            // Проверка условий истинного перебивания с защитой от акустического эха
                            val isVocalized = speechStartedOnFrame || vadDetector.isSpeechDetected.value

                            if (isVocalized) {
                                val canBargeInTimers = (now - lastPlaybackStartMs > PLAYBACK_GRACE_PERIOD_MS) &&
                                                       (now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS)

                                val exceedsAcousticBoundary = if (isAiRendering) {
                                    // Формула Geigel DTD с учётом нелинейной компрессии Smart PA на пиках
                                    val nonLinearOffset = if (instantaneousOut > 0.50f) {
                                        (instantaneousOut - 0.50f) * 0.35f
                                    } else {
                                        0.0f
                                    }
                                    val echoThreshold = maxOf(0.16f, (instantaneousOut * 0.65f) + nonLinearOffset)
                                    instantaneousMic > echoThreshold
                                } else if (isBluetooth) {
                                    // Режим гарнитуры: эхо динамика исключено физически.
                                    // Порог 0.025f (~ -32 dBFS) надёжно отсекает шумы дыхания, пропуская речь пользователя.
                                    val btNoiseThreshold = 0.025f
                                    instantaneousMic > btNoiseThreshold
                                } else {
                                    // Динамик молчит — свободный ввод
                                    true
                                }

                                if (exceedsAcousticBoundary) {
                                    bargeInCandidateStreak++
                                } else {
                                    bargeInCandidateStreak = maxOf(0, bargeInCandidateStreak - 1)
                                }

                                // Подтверждение Double-Talk Hangover: минимум 2 кадра подряд
                                val isConfirmedUserInterruption = (isAiRendering || isBluetooth) &&
                                    (bargeInCandidateStreak >= 2) && canBargeInTimers

                                if (isConfirmedUserInterruption) {
                                    lastBargeInMs = now
                                    bargeInCandidateStreak = 0

                                    activateBargeIn(now)
                                    hapticManager.triggerBargeIn()
                                    _bargeInEvents.tryEmit(Unit)

                                    val preRoll = mutableListOf<ByteArray>()
                                    synchronized(poolLock) {
                                        while (leadInBuffer.isNotEmpty()) {
                                            preRoll.add(leadInBuffer.removeFirst())
                                        }
                                    }

                                    for (pf in preRoll) {
                                        sendMicEvent(
                                            AudioStreamEvent.Audio(pf),
                                            instanceId
                                        )
                                    }
                                }
                            } else {
                                bargeInCandidateStreak = 0
                            }

                            if (
                                isBluetooth ||
                                !isAiRendering ||
                                isBargeInActive
                            ) {

                                sendMicEvent(
                                    AudioStreamEvent
                                        .Audio(
                                            currentAudioBytes
                                        ),
                                    instanceId
                                )

                            } else {

                                synchronized(
                                    poolLock
                                ) {

                                    leadInBuffer
                                        .addLast(
                                            currentAudioBytes
                                        )

                                    val maxPreRoll =
                                        router
                                            .currentProfile
                                            .value
                                            .leadInBufferSizeFrames /
                                            160

                                    while (
                                        leadInBuffer.size >
                                            maxPreRoll
                                    ) {

                                        recycleBuffer(
                                            leadInBuffer
                                                .removeFirst()
                                        )
                                    }
                                }
                            }

                            if (
                                speechEndedOnFrame &&
                                _isCapturing.value &&
                                captureInstanceId.get() == instanceId
                            ) {
                                sendMicEvent(
                                    AudioStreamEvent
                                        .SpeechEnd,
                                    instanceId
                                )
                            }

                        } else {

                            if (
                                speechStartedOnFrame
                            ) {

                                val preRoll =
                                    mutableListOf<
                                        ByteArray
                                    >()

                                synchronized(
                                    poolLock
                                ) {

                                    while (
                                        leadInBuffer
                                            .isNotEmpty()
                                    ) {

                                        preRoll.add(
                                            leadInBuffer
                                                .removeFirst()
                                        )
                                    }
                                }

                                sendMicEvent(
                                    AudioStreamEvent
                                        .SpeechStart,
                                    instanceId
                                )

                                for (
                                    pf in
                                    preRoll
                                ) {

                                    sendMicEvent(
                                        AudioStreamEvent
                                            .Audio(pf)
                                    )
                                }

                                sendMicEvent(
                                    AudioStreamEvent
                                        .Audio(
                                            currentAudioBytes
                                        ),
                                    instanceId
                                )

                                isSpeechActiveManual =
                                    true

                            } else if (
                                isSpeechActiveManual
                            ) {

                                sendMicEvent(
                                    AudioStreamEvent
                                        .Audio(
                                            currentAudioBytes
                                        ),
                                    instanceId
                                )

                            } else {

                                synchronized(
                                    poolLock
                                ) {

                                    leadInBuffer
                                        .addLast(
                                            currentAudioBytes
                                        )

                                    while (
                                        leadInBuffer.size >
                                            PRE_ROLL_FRAMES_CAPACITY
                                    ) {

                                        recycleBuffer(
                                            leadInBuffer
                                                .removeFirst()
                                        )
                                    }
                                }
                            }

                            if (
                                speechEndedOnFrame &&
                                _isCapturing.value &&
                                captureInstanceId.get() == instanceId
                            ) {
                                sendMicEvent(
                                    AudioStreamEvent
                                        .SpeechEnd,
                                    instanceId
                                )
                                isSpeechActiveManual = false
                            }
                        }

                        if (currentAudioBytes !== frame) {
                            recycleBuffer(frame)
                        }

                    } else {
                        val pollDelayMs = if (++zeroReadStreak > 3) 10L else 5L
                        delay(pollDelayMs)
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    logger.e(
                        "NativeAudioEngine: capture loop failed",
                        t
                    )
                }
                throw t
            } finally {
                if (captureInstanceId.get() == instanceId) {
                    _isCapturing.value = false
                    enqueueStreamStopOnce(
                        engineGeneration.get(),
                        instanceId
                    )
                    runCatching {
                        captureDirectMutex.withLock {
                            bridge.stopCaptureAudio()
                        }
                    }.onFailure {
                        logger.e(
                            "NativeAudioEngine: failed to stop capture after loop termination",
                            it
                        )
                    }
                }
            }
        }

        }

        if (
            _isPlaying.value &&
            spectrumJob?.isActive != true
        ) {
            spectrumJob =
                engineScope.launch {

                var tick = 0
                while (isActive) {

                    bridge.getSpectrumData(
                        spectrumRawData
                    )

                    System.arraycopy(
                        spectrumRawData,
                        0,
                        spectrumUniformUpdate,
                        0,
                        5
                    )

                    spectrumUniforms.set(spectrumUniformUpdate.copyOf())
                    if (
                        tick++ % 4 == 0
                    ) {

                        _micLevel.value =
                            (
                                spectrumRawData[5] *
                                    3.5f
                            ).coerceIn(
                                0f,
                                1f
                            )

                        _outLevel.value =
                            (
                                spectrumRawData[6] *
                                    3.5f
                            ).coerceIn(
                                0f,
                                1f
                            )
                    }

                    delay(8)
                }
            }
        }

        if (
            _isPlaying.value &&
            healthJob?.isActive != true
        ) {
            healthJob =
                engineScope.launch {
                    while (
                        isActive &&
                        (playbackDesired.get() || captureDesired.get())
                    ) {
                        try {
                            if (!vadDetector.isNeuralActive) {
                                vadDetector.prepare()
                            }
                            if (bridge.isAudioDisconnected()) {
                                logger.w(
                                    "NativeAudioEngine: AAudio health fault detected; restarting active streams"
                                )

                                applyRouteInternal(
                                    RouteTransitionRequest(
                                        router.currentProfile.value,
                                        engineGeneration.get()
                                    )
                                )
                            }
                        } catch (t: Throwable) {
                            if (t !is CancellationException) {
                                logger.e(
                                    "NativeAudioEngine: audio recovery attempt failed",
                                    t
                                )
                            }
                        }

                        delay(100)
                    }
                }
        }
    }

    private suspend fun applyRouteInternal(
        req: RouteTransitionRequest
    ) =
        audioLifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                val currentGeneration = engineGeneration.get()
                if (req.generation != currentGeneration) return@withContext

                val keepPlaying = playbackDesired.get()
                val keepCapturing = captureDesired.get()
                if (!keepPlaying && !keepCapturing) return@withContext

                if (keepCapturing) {
                    _isCapturing.value = false
                    synchronized(captureEventLock) {
                        captureInstanceId.incrementAndGet()
                    }

                    val oldCaptureJob = captureJob
                    if (oldCaptureJob != null && oldCaptureJob.isActive) {
                        oldCaptureJob.cancel()
                    }

                    captureDirectMutex.withLock {
                        bridge.stopCaptureAudio()
                    }

                    if (oldCaptureJob != null) {
                        val stopped =
                            withTimeoutOrNull(1000L) {
                                oldCaptureJob.join()
                                true
                            } ?: false

                        if (!stopped) {
                            logger.e(
                                "NativeAudioEngine: previous capture worker did not terminate during route recovery; keeping it as the lifecycle ownership barrier"
                            )
                            engineScope.launch {
                                delay(100L)
                                if (
                                    req.generation == engineGeneration.get() &&
                                    captureDesired.get()
                                ) {
                                    routeTransitionChannel.trySend(req)
                                }
                            }
                            return@withContext
                        }
                    }

                    captureJob = null

                    synchronized(poolLock) {
                        recycleLeadInBuffersLocked()
                    }
                    vadDetector.resetState()
                }

                if (keepPlaying) {
                    invalidateAndFlushPlayback("route recovery")
                }

                captureDirectMutex.withLock {
                    playbackOperationLock.lock()
                    try {
                        bridge.stopAudio()

                        val success = bridge.initAudioRoute(
                            isBluetooth =
                                req.profile.path == AudioRoutePath.BLUETOOTH_COMMUNICATION,
                            sampleRate = req.profile.sampleRateOut,
                            inputDeviceId = req.profile.inputDeviceId,
                            outputDeviceId = req.profile.outputDeviceId
                        )

                        if (!success) {
                            _isPlaying.value = false
                            _isCapturing.value = false
                            logger.e(
                                "NativeAudioEngine: route reinitialization failed; desired lifecycle remains armed for health retry"
                            )
                        } else {
                            logActualNativeRoute(req.profile, "routeRecovery")
                            vadDetector.resetState()
                            synchronized(poolLock) {
                                recycleLeadInBuffersLocked()
                            }
                            vadDetector.setThresholds(
                                req.profile.vadThresholdStart,
                                req.profile.vadThresholdEnd
                            )

                            val playbackRecovered =
                                !keepPlaying || bridge.startPlaybackAudio()
                            _isPlaying.value = playbackRecovered && keepPlaying

                            if (!playbackRecovered && keepPlaying) {
                                logger.e(
                                    "NativeAudioEngine: не удалось восстановить playback после смены route; health loop повторит попытку"
                                )
                            }

                            val captureRecovered =
                                !keepCapturing || bridge.startCaptureAudio()
                            _isCapturing.value = captureRecovered && keepCapturing

                            if (!captureRecovered && keepCapturing) {
                                logger.e(
                                    "NativeAudioEngine: не удалось восстановить capture после смены route; health loop повторит попытку"
                                )
                            }

                            if (playbackRecovered && keepPlaying) {
                                playbackStartGeneration.set(
                                    currentPlaybackGeneration
                                )
                                lastPlaybackStartMs = SystemClock.elapsedRealtime() + PLAYBACK_GRACE_PERIOD_MS
                                lastBargeInMs = SystemClock.elapsedRealtime() + BARGE_IN_DEBOUNCE_MS
                                resetBargeInState()
                            }

                            if (captureRecovered && keepCapturing) {
                                synchronized(captureEventLock) {
                                    captureInstanceId.incrementAndGet()
                                }
                                startLoops()
                            }
                        }
                    } finally {
                        playbackOperationLock.unlock()
                    }
                }
            }
        }

    private suspend fun enqueueStreamStopOnce(
        generation: Long,
        expectedCaptureInstanceId: Long? = null
    ): Boolean =
        synchronized(captureEventLock) {
            if (streamStopGeneration == generation) {
                return@synchronized true
            }

            val enqueued =
                sendMicEventLocked(
                    AudioStreamEvent.StreamStop,
                    expectedCaptureInstanceId
                )

            if (enqueued) {
                streamStopGeneration = generation
            }

            enqueued
        }

    suspend fun stopCaptureGraceful(
        gracefulTimeoutMs: Long = 1500L
    ): CaptureShutdownResult =
        withContext(NonCancellable) {
            audioLifecycleMutex.withLock {
                withContext(Dispatchers.IO) {
                    captureDesired.set(false)
                    _isCapturing.value = false

                    synchronized(captureEventLock) {
                        captureInstanceId.incrementAndGet()
                    }

                    var shutdownStatus =
                        CaptureShutdownResult.GRACEFUL_LOSSLESS
                    val job = captureJob

                    if (job?.isActive == true) {
                        job.cancel()
                    }

                    captureDirectMutex.withLock {
                        bridge.stopCaptureAudio()
                    }

                    if (job != null) {
                        val completed =
                            withTimeoutOrNull(
                                gracefulTimeoutMs.coerceAtLeast(0L)
                            ) {
                                job.join()
                                true
                            } ?: false

                        if (!completed) {
                            shutdownStatus =
                                CaptureShutdownResult.FORCED_TIMEOUT
                            logger.e(
                                "NativeAudioEngine: capture worker did not terminate after native capture stop"
                            )
                        }
                    }

                    if (job?.isCompleted == true) {
                        captureJob = null
                    }

                    synchronized(poolLock) {
                        recycleLeadInBuffersLocked()
                    }
                    vadDetector.resetState()

                    if (!enqueueStreamStopOnce(engineGeneration.get())) {
                        shutdownStatus =
                            CaptureShutdownResult.FORCED_TIMEOUT
                    }

                    return@withContext shutdownStatus
                }
            }
        }

    suspend fun stop() =
        withContext(NonCancellable) {
            audioLifecycleMutex.withLock {
                withContext(Dispatchers.IO) {

                captureDesired.set(false)
                playbackDesired.set(false)

                _isCapturing.value =
                    false

                _isPlaying.value =
                    false

                engineGeneration
                    .incrementAndGet()

                synchronized(captureEventLock) {
                    streamStopGeneration = -1L
                    captureInstanceId.incrementAndGet()
                }

                val captureToStop = captureJob
                if (captureToStop?.isActive == true) {
                    captureToStop.cancel()
                }

                spectrumJob?.let { job ->
                    if (job.isActive) {
                        job.cancel()
                        withTimeoutOrNull(500L) { job.join() }
                    }
                }
                spectrumJob = null

                healthJob?.let { job ->
                    if (job.isActive) {
                        job.cancel()
                        withTimeoutOrNull(500L) { job.join() }
                    }
                }
                healthJob = null

                captureDirectMutex.withLock {
                    playbackOperationLock.lock()
                    try {
                        bridge.stopAudio()
                    } finally {
                        playbackOperationLock.unlock()
                    }
                }

                if (captureToStop != null) {
                    val completed =
                        withTimeoutOrNull(500L) {
                            captureToStop.join()
                            true
                        } ?: false

                    if (completed) {
                        captureJob = null
                    } else {
                        logger.e(
                            "NativeAudioEngine: terminal capture worker still terminating after native stop; retaining lifecycle ownership barrier"
                        )
                    }
                } else {
                    captureJob = null
                }

                abandonAudioFocus()

                router.stop()

                vadDetector.resetState()

                _micLevel.value =
                    0f

                _outLevel.value =
                    0f

                resetBargeInState()

                synchronized(
                    poolLock
                ) {

                    recycleLeadInBuffersLocked()
                }
                drainMicOutput()

                while (
                    routeTransitionChannel
                        .tryReceive()
                        .isSuccess
                ) {}
                }
            }
        }

    /**
     * Адаптивное аппаратное ожидание полного проигрывания сэмплов из буферов ЦАП (Watchdog прогресса).
     *
     * Устраняет дефект жесткого тайм-аута в 2.5 с (Проблема №1). Воспроизведение длится столько, сколько реально
     * звучит сгенерированная речь (5, 15, 60+ секунд). Ожидание завершается неудачей ТОЛЬКО в случае,
     * если сэмплы застряли и аппаратный ЦАП не забрал ни одного кадра за время [stallTimeoutMs].
     */
    suspend fun awaitPlaybackDrained(
        generation: Long,
        stallTimeoutMs: Long = 1800L
    ): Boolean = withContext(Dispatchers.IO) {
        val sampleRate = bridge.getActualPlaybackSampleRate().let { if (it > 0) it else 48000 }
        var lastPending = bridge.getPendingPlaybackFrames()
        var lastProgressTime = SystemClock.elapsedRealtime()
        val startTime = lastProgressTime

        var maxAllowedDurationMs = (lastPending * 1000L / sampleRate) + 2500L

        while (isActive) {
            if (generation != currentPlaybackGeneration) {
                return@withContext true
            }

            val currentPending = bridge.getPendingPlaybackFrames()
            val now = SystemClock.elapsedRealtime()

            if (currentPending == 0L) {
                delay(80L)
                if (generation == currentPlaybackGeneration && bridge.getPendingPlaybackFrames() == 0L) {
                    return@withContext true
                }
                continue
            }

            if (currentPending < lastPending) {
                lastPending = currentPending
                lastProgressTime = now

                val remainingMs = (currentPending * 1000L / sampleRate) + 1500L
                val projectedTotal = (now - startTime) + remainingMs
                if (projectedTotal > maxAllowedDurationMs) {
                    maxAllowedDurationMs = projectedTotal
                }
            } else if (currentPending > lastPending) {
                lastPending = currentPending
                lastProgressTime = now
                maxAllowedDurationMs += ((currentPending - lastPending) * 1000L / sampleRate) + 500L
            } else {
                if (now - lastProgressTime >= stallTimeoutMs) {
                    logger.w("NativeAudioEngine: playback stalled for ${now - lastProgressTime} ms with $currentPending pending frames")
                    return@withContext false
                }
            }

            if (now - startTime > maxAllowedDurationMs) {
                logger.w("NativeAudioEngine: playback exceeded dynamic maximum duration ($maxAllowedDurationMs ms)")
                return@withContext false
            }

            delay(15L)
        }

        generation != currentPlaybackGeneration || bridge.getPendingPlaybackFrames() == 0L
    }

    fun setVolume(
        volume: Float
    ) =
        bridge.setVolume(
            volume.coerceIn(
                0f,
                1f
            )
        )

    fun setMicGain(
        gain: Float
    ) =
        bridge.setMicGain(
            gain.coerceIn(
                0.5f,
                2.0f
            )
        )

    fun invalidateAndFlushPlayback(reason: String = ""): Long {
        playbackOperationLock.lock()
        try {
            val current = playbackGeneration.get()
            val next =
                if (current == Long.MAX_VALUE) 1L
                else current + 1L

            bridge.flushPlayback(next)
            playbackGeneration.set(next)

            _outLevel.value = 0f
            logger.d(
                "NativeAudioEngine: playback generation committed [Gen=$next, Reason='$reason']"
            )
            return next
        } finally {
            playbackOperationLock.unlock()
        }
    }

    /**
     * Потоковая передача PCM фрейма в C++ SPSC кольцевой буфер с честным Backpressure.
     *
     * Устраняет дефект выбрасывания аудиокадров через 200 мс (Проблема №2). Если C++ буфер временно полон
     * из-за быстрого сетевого прихода пакетов, корутина ждёт освобождения места.
     * Отказ и прерывание записи происходят только при реальной остановке ЦАПа более чем на 1.8 с.
     */
    suspend fun enqueuePlayback(
        pcm: ByteArray,
        generation: Long
    ) {

        if (
            pcm.isEmpty() ||
            !_isPlaying.value
        ) {
            return
        }

        if (
            isBargeInActive ||
            generation != currentPlaybackGeneration
        ) {
            return
        }

        if (
            playbackStartGeneration.getAndSet(
                generation
            ) != generation
        ) {
            lastPlaybackStartMs =
                SystemClock.elapsedRealtime()
        }

        var offset = 0
        val total = pcm.size
        var lastSuccessfulWriteMs = SystemClock.elapsedRealtime()

        while (
            offset < total &&
            _isPlaying.value
        ) {

            if (
                isBargeInActive ||
                generation != currentPlaybackGeneration
            ) {
                return
            }

            val written =
                bridge.writePlaybackByteArray(
                    pcm,
                    offset,
                    total - offset,
                    generation
                )

            val now = SystemClock.elapsedRealtime()

            if (written > 0) {
                offset += written
                lastSuccessfulWriteMs = now
            } else {
                // Нативный C++ буфер временно заполнен.
                // Применяем честное противодавление (Non-Destructive Suspend Backpressure).
                if (now - lastSuccessfulWriteMs >= PLAYBACK_WRITE_STALL_TIMEOUT_MS) {
                    logger.w(
                        "NativeAudioEngine: playback write stalled for ${now - lastSuccessfulWriteMs} ms; aborting stuck frame"
                    )
                    break
                }
                delay(4)
            }
        }
    }

    fun triggerBargeInEarcon() {
        bridge.triggerBargeInEarcon()
    }

    private fun activateBargeIn(
        now: Long
    ) {

        isBargeInActive =
            true

        bargeInTimestampMs =
            now

        bargeInLeaseJob?.cancel()

        bargeInLeaseJob =
            engineScope.launch {

                val minReleaseAt =
                    now +
                    BARGE_IN_MIN_HOLD_MS

                val hardDeadline =
                    now +
                    BARGE_IN_HARD_RECOVERY_MS

                while (
                    isActive &&
                    isBargeInActive
                ) {

                    val current =
                        SystemClock
                            .elapsedRealtime()

                    if (
                        current >=
                            hardDeadline
                    ) {

                        logger.w(
                            "NativeAudioEngine: Barge-in hard recovery deadline reached"
                        )

                        resetBargeInState()

                        break
                    }

                    if (
                        current >=
                            minReleaseAt &&
                        !vadDetector
                            .isSpeechDetected
                            .value
                    ) {

                        resetBargeInState()
                        break
                    }

                    delay(
                        BARGE_IN_RECOVERY_POLL_MS
                    )
                }
            }
    }

    fun resetBargeInState() {

        isBargeInActive =
            false

        bargeInTimestampMs =
            0L

        bargeInLeaseJob?.cancel()

        bargeInLeaseJob =
            null
    }

    private fun drainMicOutput() {
        synchronized(captureEventLock) {
            while (true) {
                val result = _micOutput.tryReceive()
                val event = result.getOrNull() ?: break
                if (event is AudioStreamEvent.Audio) {
                    releaseCapturedBuffer(event.pcm)
                }
            }
        }
    }

    fun drainPendingMicOutput() {
        drainMicOutput()
    }

    private fun sendMicEvent(
        event: AudioStreamEvent,
        expectedCaptureInstanceId: Long? = null
    ): Boolean =
        synchronized(captureEventLock) {
            sendMicEventLocked(
                event,
                expectedCaptureInstanceId
            )
        }

    private fun sendMicEventLocked(
        event: AudioStreamEvent,
        expectedCaptureInstanceId: Long?
    ): Boolean {
        if (
            expectedCaptureInstanceId != null &&
            captureInstanceId.get() != expectedCaptureInstanceId
        ) {
            if (event is AudioStreamEvent.Audio) {
                recycleBuffer(event.pcm)
            }
            return false
        }

        if (event is AudioStreamEvent.Audio) {
            val bytes = event.pcm.size.toLong()
            val next = queuedMicOutputBytes.addAndGet(bytes)

            if (next > MAX_MIC_OUTPUT_BACKLOG_BYTES) {
                queuedMicOutputBytes.addAndGet(-bytes)
                recycleBuffer(event.pcm)

                logger.e(
                    "NativeAudioEngine: mic output backlog exceeded ${MAX_MIC_OUTPUT_BACKLOG_BYTES} bytes; stopping capture producer"
                )

                _isCapturing.value = false

                if (streamStopGeneration != engineGeneration.get()) {
                    if (
                        _micOutput
                            .trySend(AudioStreamEvent.StreamStop)
                            .isSuccess
                    ) {
                        streamStopGeneration =
                            engineGeneration.get()
                    }
                }

                return false
            }
        }

        val result = _micOutput.trySend(event)

        if (result.isFailure && event is AudioStreamEvent.Audio) {
            releaseCapturedBuffer(event.pcm)
        }

        return result.isSuccess
    }

    private fun decrementQueuedMicOutputBytes(
        bytes: Long
    ) {
        if (bytes <= 0L) return

        while (true) {
            val current = queuedMicOutputBytes.get()
            val next = (current - bytes).coerceAtLeast(0L)
            if (queuedMicOutputBytes.compareAndSet(current, next)) {
                return
            }
        }
    }

    fun releaseCapturedBuffer(
        pcm: ByteArray
    ) {
        val bytes = pcm.size.toLong()
        decrementQueuedMicOutputBytes(bytes)
        recycleBuffer(pcm)
    }

    private fun recycleLeadInBuffersLocked() {
        while (leadInBuffer.isNotEmpty()) {
            recycleBuffer(leadInBuffer.removeFirst())
        }
    }

    private fun obtainBuffer():
        ByteArray =
        synchronized(poolLock) {

            if (
                bufferPool.isNotEmpty()
            ) {
                bufferPool.removeFirst()
            } else {
                ByteArray(
                    BURST_BYTES
                )
            }
        }

    private fun recycleBuffer(
        buf: ByteArray
    ) =
        synchronized(poolLock) {

            if (
                buf.size == BURST_BYTES &&
                bufferPool.size < 64
            ) {
                bufferPool.addLast(
                    buf
                )
            }
        }
}