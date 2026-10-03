package com.client.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Data Plane: Высокочастотные события PCM-потока микрофона (100 фреймов/с).
 */
class AudioStreamDataEvent(
    var pcm: ByteArray,
    var length: Int = pcm.size,
    var sequenceNumber: Long = 0L,
    var captureTimestampNs: Long = 0L
)

/**
 * Control Plane: События речевого взаимодействия VAD и управления жизненным циклом.
 */
sealed interface AudioStreamControlEvent {
    data object SpeechStart : AudioStreamControlEvent
    data object SpeechEnd : AudioStreamControlEvent
    data object StreamStop : AudioStreamControlEvent
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

enum class AudioEngineState {
    IDLE,
    STARTING,
    RUNNING,
    RECOVERING,
    STOPPING
}

private data class RouteTransitionRequest(
    val profile: RouteProfile,
    val generation: Long
)

/**
 * Упорядоченный поток микрофона: аудиокадры и управляющие события идут через ОДИН канал,
 * поэтому activityStart/audioStreamEnd никогда не обгоняют и не отстают от своих кадров.
 */
sealed interface MicEvent {
    class Audio(val data: AudioStreamDataEvent) : MicEvent
    class Control(val event: AudioStreamControlEvent) : MicEvent
}

/**
 * Детектор перебивания с адаптивной оценкой связи «динамик → микрофон».
 *
 * Остаток эха после аппаратного AEC пропорционален уровню того, что реально выдаёт ЦАП.
 * Коэффициент связи оценивается по нижней огибающей отношения mic/ref, пока модель говорит,
 * а пользователь молчит: быстро вниз, медленно вверх. Голос пользователя признаётся, только
 * если микрофон превышает ожидаемое эхо с запасом И Silero подтверждает речь. Порог сам
 * подстраивается под громкость, маршрут и положение телефона — без фиксированных «ERLE»-констант.
 */
class EchoAwareBargeInDetector {
    @Volatile var coupling: Float = SPEAKER_INITIAL_COUPLING
        private set
    @Volatile private var minCoupling = 0.01f
    @Volatile private var maxCoupling = 1.5f
    @Volatile private var echoMargin = 2.8f
    @Volatile private var minProbability = 0.6f
    @Volatile var candidateFrames: Int = 4
        private set
    @Volatile var confirmMs: Long = 450L
        private set
    private var streak = 0

    fun configure(isHeadset: Boolean) {
        if (isHeadset) {
            minCoupling = 0.002f
            maxCoupling = 0.6f
            echoMargin = 2.0f
            minProbability = 0.6f
            candidateFrames = 4
            confirmMs = 250L
            coupling = HEADSET_INITIAL_COUPLING
        } else {
            minCoupling = 0.015f
            maxCoupling = 1.5f
            echoMargin = 3.0f
            minProbability = 0.7f
            candidateFrames = 6
            confirmMs = 300L
            coupling = SPEAKER_INITIAL_COUPLING
        }
        streak = 0
    }

    /** Обучение на кадре, где пользователь заведомо не перебивает (эхо без голоса). */
    fun adapt(micRms: Float, refNow: Float, refMax: Float) {
        if (refMax < REF_MIN_RMS || refNow < refMax * 0.6f) return
        val ratio = (micRms / refMax).coerceIn(0f, 4f)
        // Кадры заметно громче ожидаемого эха (возможный голос пользователя) в обучение не идут
        if (ratio > coupling * echoMargin) return
        val rate = if (ratio < coupling) FALL_RATE else RISE_RATE
        coupling = (coupling + (ratio - coupling) * rate).coerceIn(minCoupling, maxCoupling)
    }

    /** true — кадр похож на голос пользователя поверх остаточного эха. */
    fun isUserSpeechFrame(
        micRms: Float,
        refMax: Float,
        vadProbability: Float,
        noiseFloor: Float,
        convergenceBoost: Float
    ): Boolean {
        val expectedEcho = coupling * refMax
        val threshold = maxOf(noiseFloor * 3.0f, expectedEcho * echoMargin * convergenceBoost, ABS_MIN_RMS)
        return vadProbability >= minProbability && micRms > threshold
    }

    /** Кандидат на перебивание: N подряд кадров голоса поверх эха. */
    fun evaluateCandidate(userSpeech: Boolean): Boolean {
        streak = if (userSpeech) streak + 1 else 0
        return streak >= candidateFrames
    }

    fun onFalseAlarm() {
        coupling = (coupling * 1.3f).coerceIn(minCoupling, maxCoupling)
        streak = 0
    }

    fun resetStreak() {
        streak = 0
    }

    companion object {
        const val SPEAKER_INITIAL_COUPLING = 0.35f
        const val HEADSET_INITIAL_COUPLING = 0.05f
        const val REF_MIN_RMS = 0.004f
        const val ABS_MIN_RMS = 0.006f
        // Симметричное сглаживание: оценка средней утечки эха, а не её редкого минимума
        private const val FALL_RATE = 0.02f
        private const val RISE_RATE = 0.02f
    }
}

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
        private const val BURST_BYTES = 160 * 2 // 10 мс @ 16 кГц 16-бит моно
        private const val PLAYBACK_GRACE_PERIOD_MS = 450L
        private const val BARGE_IN_DEBOUNCE_MS = 450L
        private const val BARGE_IN_MIN_HOLD_MS = 350L
        private const val BARGE_IN_HARD_RECOVERY_MS = 3000L
        private const val BARGE_IN_RECOVERY_POLL_MS = 50L
        private const val FRAME_SAMPLES = 160                 // 10 мс @ 16 кГц
        private const val CAPTURE_WAIT_TIMEOUT_MS = 40
        private const val PRE_ROLL_IDLE_FRAMES = 45           // 450 мс: покрывает подтверждение речи и начало слова
        private const val IDLE_SPEECH_CONFIRM_FRAMES = 15     // 150 мс уверенной близкой речи до открытия реплики
        private const val NEAR_FIELD_MIN_PROB = 0.6f
        private const val NEAR_FIELD_SNR = 3.2f               // ≈ +10 дБ над шумовым фоном
        private const val NEAR_FIELD_MIN_RMS = 0.008f         // ≈ −42 дБFS: тихие далёкие источники отсекаются
        private const val PRE_ROLL_BARGE_IN_FRAMES = 12       // 120 мс до кандидата: без эха модели в аплинке
        private const val PRE_ROLL_MAX_FRAMES = 80
        private const val OUT_ACTIVE_RMS = 0.003f
        private const val RENDER_TAIL_MS = 250L               // хвост эха/латентности после последнего звука модели
        private const val AEC_CONVERGENCE_MS = 300L
        private const val BARGE_IN_CANDIDATE_MAX_MS = 600L
        private const val BARGE_IN_FALSE_ALARM_SILENT_FRAMES = 12
        private const val BARGE_IN_REFRACTORY_MS = 300L
        private const val REFERENCE_HISTORY_FRAMES = 12       // 120 мс: покрывает задержку тракта эха
        private const val VAD_PREPARE_RETRY_MS = 5000L
        private const val ROUTE_RECOVERY_ATTEMPTS = 3
        private const val ROUTE_RECOVERY_RETRY_MS = 400L

        private const val MAX_MIC_OUTPUT_BACKLOG_BYTES = 64L * 1024L
        private const val PLAYBACK_WRITE_STALL_TIMEOUT_MS = 1500L
        private const val MAX_JNI_WRITE_CHUNK_BYTES = 4096
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val _engineState = MutableStateFlow(AudioEngineState.IDLE)
    val engineState: StateFlow<AudioEngineState> = _engineState.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _outLevel = MutableStateFlow(0f)
    val outLevel: StateFlow<Float> = _outLevel.asStateFlow()

    private val _bargeInEvents = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bargeInEvents: SharedFlow<Unit> = _bargeInEvents.asSharedFlow()

    private val _focusEvents = MutableStateFlow<AudioFocusEvent>(AudioFocusEvent.Gain)
    val focusEvents: StateFlow<AudioFocusEvent> = _focusEvents.asStateFlow()

    private val _micOutput = Channel<MicEvent>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { event ->
            if (event is MicEvent.Audio) {
                releaseCapturedBuffer(event.data.pcm)
                recycleAudioEvent(event.data)
            }
        }
    )
    val micOutput: ReceiveChannel<MicEvent> = _micOutput

    private val queuedMicOutputBytes = AtomicLong(0L)

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in NativeAudioEngine", throwable)
    }

    private val engineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler
    )

    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread({
            runCatching {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            }.onFailure {
                logger.w("NativeAudioEngine: failed to set capture thread priority: ${it.message}")
            }
            runnable.run()
        }, "NativeAudioCapture")
    }

    private val captureDispatcher = captureExecutor.asCoroutineDispatcher()

    private var captureJob: Job? = null
    private var spectrumJob: Job? = null
    private var healthJob: Job? = null

    private val captureInstanceId = AtomicLong(0L)
    private val audioLifecycleMutex = Mutex()
    private val captureEventLock = Any()
    private val captureDirectMutex = Mutex()

    private val routeTransitionChannel = Channel<RouteTransitionRequest>(capacity = 16)

    private val engineGeneration = AtomicLong(0)
    private val playbackGeneration = AtomicLong(1L)
    private val playbackStartGeneration = AtomicLong(0L)

    private val playbackDesired = AtomicBoolean(false)
    private val captureDesired = AtomicBoolean(false)

    private val errorMetadataBuffer = LongArray(4)

    val currentPlaybackGeneration: Long
        get() = playbackGeneration.get()

    fun setEndOfSpeechHangoverMs(ms: Int) {
        vadDetector.setEndHangoverMs(ms)
    }

    /** Прогрев Silero заранее: при старте сессии модель VAD уже загружена, микрофон открывается без задержки. */
    suspend fun prewarmVad(): Boolean = vadDetector.prepare()

    /** Hi-Fi Bluetooth (A2DP + микрофон телефона) / голосовая гарнитура (HFP). Применяется на лету. */
    fun setBluetoothHiFiEnabled(enabled: Boolean) {
        if (router.bluetoothHiFiEnabled == enabled) return
        router.bluetoothHiFiEnabled = enabled
        router.requestReevaluation()
    }

    fun setVadThresholds(start: Float, end: Float) {
        val s = start.coerceIn(0.05f, 0.95f)
        val e = end.coerceIn(0.01f, s)
        vadDetector.setThresholds(s, e)
    }

    @Volatile private var streamStopGeneration: Long = -1L
    @Volatile var isAadMode: Boolean = true

    private val captureDirectBuffer = ByteBuffer.allocateDirect(BURST_BYTES * 4).order(ByteOrder.LITTLE_ENDIAN)
    private val playbackDirectBuffer = ByteBuffer.allocateDirect(8192 * 2).order(ByteOrder.LITTLE_ENDIAN)
    private val playbackDirectMutex = Mutex()

    private val spectrumRawData = FloatArray(7)
    private val spectrumUniformUpdate = FloatArray(5)
    val spectrumUniforms = AtomicReference(FloatArray(5))

    private val bufferPool = ArrayDeque<ByteArray>(32).apply {
        repeat(32) { add(ByteArray(BURST_BYTES)) }
    }
    private val poolLock = Any()
    private val leadInBuffer = ArrayDeque<ByteArray>(32)

    private val audioEventPool = ArrayDeque<AudioStreamDataEvent>(64)
    private val audioEventPoolLock = Any()

    private val bargeInDetector = EchoAwareBargeInDetector()

    @Volatile private var currentPlaybackVolume: Float = 1.0f

    // Перебивание разрешено (activityHandling != NO_INTERRUPTION)
    @Volatile var localBargeInEnabled: Boolean = true

    // Вызывается синхронно ДО сброса вывода при подтверждённом перебивании (транспорт отсекает хвост генерации)
    @Volatile var bargeInCommitListener: (() -> Unit)? = null

    // Фраза пользователя сейчас передаётся на сервер (для бесшовного GoAway)
    @Volatile var isUplinkActive: Boolean = false
        private set

    @Volatile private var nextVadPrepareAttemptMs = 0L

    // Аппаратные эффекты захвата (AEC/NS) текущей аудиосессии; живут в потоке захвата
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var attachedEffectSessionId = 0

    @Volatile var isBargeInActive = false
        private set

    @Volatile private var bargeInTimestampMs = 0L
    private var bargeInLeaseJob: Job? = null

    init {
        engineScope.launch {
            for (req in routeTransitionChannel) {
                applyRouteInternal(req)
            }
        }
    }

    private fun obtainAudioEvent(
        pcm: ByteArray,
        length: Int,
        seq: Long,
        tsNs: Long
    ): AudioStreamDataEvent = synchronized(audioEventPoolLock) {
        if (audioEventPool.isNotEmpty()) {
            val item = audioEventPool.removeFirst()
            item.pcm = pcm
            item.length = length
            item.sequenceNumber = seq
            item.captureTimestampNs = tsNs
            item
        } else {
            AudioStreamDataEvent(pcm, length, seq, tsNs)
        }
    }

    fun recycleAudioEvent(event: AudioStreamDataEvent) = synchronized(audioEventPoolLock) {
        if (audioEventPool.size < 64) {
            audioEventPool.addLast(event)
        }
    }

    private fun applyAcousticProfileForRoute(profile: RouteProfile) {
        val quirks = profile.quirks
        when {
            profile.path == AudioRoutePath.SPEAKER_SHARED -> vadDetector.setThresholds(start = 0.55f, end = 0.35f)
            profile.path == AudioRoutePath.BLUETOOTH_A2DP_HIFI -> vadDetector.setThresholds(start = 0.50f, end = 0.35f)
            profile.isBluetooth -> vadDetector.setThresholds(start = 0.45f, end = 0.30f)
            else -> vadDetector.setThresholds(start = 0.50f, end = 0.35f)
        }
        bargeInDetector.configure(isHeadset = profile.path.isHeadset)
        routeMicCompensation = quirks.micGainCompensation
        bridge.setMicGain((userMicGain * routeMicCompensation).coerceIn(0.5f, 2.0f))
        bridge.setOutputEqProfile(
            when (profile.path) {
                AudioRoutePath.SPEAKER_SHARED -> 0
                AudioRoutePath.BLUETOOTH_SCO -> 1
                else -> 2
            }
        )
        logger.d("NativeAudioEngine: Акустический профиль применен: ${quirks.deviceModel} (ENC Delay=${quirks.encLatencyMs}ms, ERLE=${quirks.acousticErleRatio})")
    }

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
                            _focusEvents.tryEmit(
                                if (change == AudioManager.AUDIOFOCUS_LOSS) AudioFocusEvent.LossPermanent
                                else AudioFocusEvent.LossTransient
                            )
                        }
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            logger.d("NativeAudioEngine: AudioFocus восстановлен")
                            _focusEvents.tryEmit(AudioFocusEvent.Gain)
                        }
                        else -> Unit
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
                        _focusEvents.tryEmit(
                            if (change == AudioManager.AUDIOFOCUS_LOSS) AudioFocusEvent.LossPermanent
                            else AudioFocusEvent.LossTransient
                        )
                    } else if (change == AudioManager.AUDIOFOCUS_GAIN) {
                        _focusEvents.tryEmit(AudioFocusEvent.Gain)
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

    private fun logActualNativeRoute(profile: RouteProfile, context: String) {
        val actualIn = bridge.getActiveInputDeviceId()
        val actualOut = bridge.getActiveOutputDeviceId()
        val actualPlayRate = bridge.getActualPlaybackSampleRate()
        val actualPlayChannels = bridge.getActualPlaybackChannels()
        val actualPlayFormat = bridge.getActualPlaybackFormat()
        val mmap = bridge.isMmapActive()
        val exclusive = bridge.isExclusiveSharingActive()

        logger.d("NativeAudioEngine: actual native route ($context): in=$actualIn out=$actualOut playRate=${actualPlayRate}Hz ch=$actualPlayChannels fmt=$actualPlayFormat exclusive=$exclusive mmap=$mmap quirks=${profile.quirks.deviceModel}")
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

                _engineState.value = AudioEngineState.STARTING

                if (!requestAudioFocus()) {
                    playbackDesired.set(false)
                    _engineState.value = AudioEngineState.IDLE
                    logger.e("NativeAudioEngine: Сбой запроса AudioFocus")
                    return@withContext false
                }

                synchronized(captureEventLock) {
                    streamStopGeneration = -1L
                }

                try {
                    router.start { profile ->
                        routeTransitionChannel.trySend(
                            RouteTransitionRequest(profile, engineGeneration.get())
                        )
                    }
                } catch (t: Throwable) {
                    logger.e("NativeAudioEngine: AudioDeviceRouter startup failed", t)
                    abandonAudioFocus()
                    _engineState.value = AudioEngineState.IDLE
                    return@withContext false
                }

                val profile = router.currentProfile.value

                val inited = captureDirectMutex.withLock {
                    bridge.setMediaPlaybackUsage(profile.path == AudioRoutePath.BLUETOOTH_A2DP_HIFI)
                    bridge.initAudioRoute(
                        isBluetooth = profile.isBluetooth,
                        sampleRate = profile.targetSampleRate,
                        inputDeviceId = profile.inputDeviceId,
                        outputDeviceId = profile.outputDeviceId
                    )
                }

                if (!inited) {
                    logger.e("NativeAudioEngine: Сбой инициализации playback route")
                    router.stop()
                    abandonAudioFocus()
                    _engineState.value = AudioEngineState.IDLE
                    return@withContext false
                }

                logActualNativeRoute(profile, "startPlayback")
                applyAcousticProfileForRoute(profile)

                captureDirectMutex.withLock {
                    bridge.flushPlayback(currentPlaybackGeneration)
                }

                val started = captureDirectMutex.withLock {
                    bridge.startPlaybackAudio()
                }

                if (!started) {
                    playbackDesired.set(false)
                    logger.e("NativeAudioEngine: Сбой запуска playback AAudio")
                    captureDirectMutex.withLock {
                        bridge.stopAudio()
                    }
                    router.stop()
                    abandonAudioFocus()
                    _engineState.value = AudioEngineState.IDLE
                    return@withContext false
                }

                _isPlaying.value = true
                playbackDesired.set(true)
                _engineState.value = AudioEngineState.RUNNING
                startLoops()
                true
            }
        }

    private suspend fun startVerifiedCapture(
        profile: RouteProfile,
        context: String
    ): Boolean {
        val maxAttempts = if (profile.isBluetooth) 3 else 1
        var captureOpened = false
        var dspActivated = false
        var admissionCommitted = false

        try {
            repeat(maxAttempts) { attempt ->
                captureOpened = captureDirectMutex.withLock {
                    bridge.startCaptureAudio()
                }

                if (!captureOpened) {
                    logger.w("NativeAudioEngine: physical capture start failed [$context], attempt=${attempt + 1}/$maxAttempts")
                } else {
                    val actualInputId = bridge.getActiveInputDeviceId()
                    val routeValid = router.isInputDeviceMatchingRoute(profile, actualInputId)

                    if (routeValid) {
                        val dspStarted = captureDirectMutex.withLock {
                            bridge.activateCaptureDspAudio()
                        }

                        if (!dspStarted) {
                            logger.e("NativeAudioEngine: DSP activation failed [$context]")
                        } else {
                            dspActivated = true

                            val committed = captureDirectMutex.withLock {
                                bridge.commitCaptureAdmission()
                            }

                            if (committed) {
                                admissionCommitted = true
                                logger.d("NativeAudioEngine: capture route verified and committed [$context], attempt=${attempt + 1}/$maxAttempts, input=$actualInputId")
                                return true
                            }
                            logger.e("NativeAudioEngine: capture admission commit failed [$context]")
                        }
                    } else {
                        logger.w("NativeAudioEngine: capture route rejected [$context], attempt=${attempt + 1}/$maxAttempts, actualInput=$actualInputId")
                    }

                    captureDirectMutex.withLock {
                        runCatching { bridge.stopCaptureAudio() }
                    }
                    captureOpened = false
                    dspActivated = false
                }

                if (attempt + 1 < maxAttempts) {
                    delay(100L)
                }
            }
        } finally {
            if (captureOpened && !admissionCommitted) {
                withContext(NonCancellable) {
                    captureDirectMutex.withLock {
                        runCatching { bridge.stopCaptureAudio() }.onFailure {
                            logger.e("NativeAudioEngine: failed to cleanup capture after cancellation [$context]", it)
                        }
                    }
                }
            }
        }

        logger.e("NativeAudioEngine: capture route could not be established [$context]")
        return false
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
                    val joined = withTimeoutOrNull(500L) {
                        existingCaptureJob.join()
                        true
                    } ?: false

                    if (!joined) {
                        logger.e("NativeAudioEngine: previous capture worker is still terminating; refusing second producer")
                        return@withContext false
                    }
                }
                captureJob = null

                if (!_isPlaying.value) {
                    logger.e("NativeAudioEngine: Нельзя запустить capture без активного playback")
                    return@withContext false
                }

                if (!vadDetector.prepare()) {
                    logger.e("NativeAudioEngine: Silero VAD model is unavailable or invalid")
                    return@withContext false
                }

                vadDetector.resetState()
                resetBargeInState()

                val profile = router.currentProfile.value
                applyAcousticProfileForRoute(profile)

                synchronized(captureEventLock) {
                    streamStopGeneration = -1L
                }

                val started = startVerifiedCapture(
                    profile = profile,
                    context = "startCapture"
                )

                if (!started) {
                    captureDesired.set(false)
                    _isCapturing.value = false
                    logger.e("NativeAudioEngine: capture startup rejected because actual input route could not be verified")
                    return@withContext false
                }

                _isCapturing.value = true
                captureDesired.set(true)
                _engineState.value = AudioEngineState.RUNNING
                synchronized(captureEventLock) {
                    captureInstanceId.incrementAndGet()
                }
                startLoops()
                true
            }
        }

    private suspend fun recoverCaptureStreamIsolated() {
        audioLifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!captureDesired.get()) return@withContext
                logger.w("NativeAudioEngine: Performing targeted capture stream recovery")

                _engineState.value = AudioEngineState.RECOVERING
                _isCapturing.value = false

                synchronized(captureEventLock) {
                    captureInstanceId.incrementAndGet()
                }

                val oldJob = captureJob
                oldJob?.cancel()
                if (oldJob != null) {
                    withTimeoutOrNull(500L) { oldJob.join() }
                }
                captureJob = null

                val recovered = captureDirectMutex.withLock {
                    bridge.restartCaptureStream()
                }

                if (recovered) {
                    _isCapturing.value = true
                    _engineState.value = AudioEngineState.RUNNING
                    synchronized(captureEventLock) {
                        captureInstanceId.incrementAndGet()
                    }
                    startLoops()
                    logger.d("NativeAudioEngine: Targeted capture recovery completed successfully")
                } else {
                    logger.e("NativeAudioEngine: Targeted capture recovery failed, falling back to full route recovery")
                    applyRouteLocked(RouteTransitionRequest(router.currentProfile.value, engineGeneration.get()))
                }
            }
        }
    }

    private suspend fun recoverPlaybackStreamIsolated() {
        audioLifecycleMutex.withLock {
            withContext(Dispatchers.IO) {
                if (!playbackDesired.get()) return@withContext
                logger.w("NativeAudioEngine: Performing targeted playback stream recovery")

                _engineState.value = AudioEngineState.RECOVERING
                _isPlaying.value = false

                invalidateAndFlushPlayback("playback stream recovery")

                val recovered = captureDirectMutex.withLock {
                    bridge.restartPlaybackStream()
                }

                if (recovered) {
                    _isPlaying.value = true
                    _engineState.value = AudioEngineState.RUNNING
                    logger.d("NativeAudioEngine: Targeted playback recovery completed successfully")
                } else {
                    logger.e("NativeAudioEngine: Targeted playback recovery failed, falling back to full route recovery")
                    applyRouteLocked(RouteTransitionRequest(router.currentProfile.value, engineGeneration.get()))
                }
            }
        }
    }

    private suspend fun drainAndDispatchNativeErrors() {
        while (bridge.hasPendingError()) {
            val hasEvent = synchronized(errorMetadataBuffer) {
                bridge.pollAudioError(errorMetadataBuffer)
            }
            if (!hasEvent) break

            val direction = errorMetadataBuffer[0].toInt()
            val errorCode = errorMetadataBuffer[1].toInt()
            val faultType = errorMetadataBuffer[2].toInt()
            val timestampNs = errorMetadataBuffer[3]

            logger.w("NativeAudioEngine: Dispatching native audio error: dir=$direction, code=$errorCode, faultType=$faultType, ts=$timestampNs")

            when (faultType) {
                1 -> {
                    logger.w("NativeAudioEngine: Soft audio fault handled (XRun/timeout)")
                }
                2, 3, 4 -> {
                    if (direction == 1) {
                        recoverCaptureStreamIsolated()
                    } else if (direction == 2) {
                        recoverPlaybackStreamIsolated()
                    } else {
                        applyRouteInternal(RouteTransitionRequest(router.currentProfile.value, engineGeneration.get()))
                    }
                }
            }
        }
    }

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
        return sqrt((sumSq + 1e-9) / sampleCount).toFloat()
    }

    /**
     * Состояние аплинка (гибридный VAD + перебивание). Принадлежит только потоку захвата.
     */
    private inner class UplinkState {
        val preRoll = ArrayDeque<ByteArray>(PRE_ROLL_MAX_FRAMES + 1)
        val reference = FloatArray(REFERENCE_HISTORY_FRAMES)
        var referenceIndex = 0
        var uplinkOpen = false
        var wasRendering = false
        var renderingStartedMs = 0L
        var lastOutputActiveMs = 0L
        var echoOnlyFrames = 0
        var candidateSinceMs = 0L
        var candidateGeneration = 0L
        var candidateFrames = 0
        var candidateSpeechFrames = 0
        var candidateSilentRun = 0
        var refractoryUntilMs = 0L
        var idleSpeechFrames = 0

        fun pushReference(outRms: Float) {
            reference[referenceIndex] = outRms
            referenceIndex = (referenceIndex + 1) % reference.size
        }

        fun referenceMax(): Float {
            var m = 0f
            for (v in reference) if (v > m) m = v
            return m
        }

        fun pushPreRoll(frame: ByteArray) {
            preRoll.addLast(frame)
            while (preRoll.size > PRE_ROLL_MAX_FRAMES) {
                recycleBuffer(preRoll.removeFirst())
            }
        }

        fun recycleAll() {
            while (preRoll.isNotEmpty()) recycleBuffer(preRoll.removeFirst())
        }
    }

    /**
     * Один кадр 10 мс: VAD, оценка эха, аплинк и перебивание.
     */
    private fun processCapturedFrame(
        frame: ByteArray,
        seqNum: Long,
        captureTimestampNs: Long,
        instanceId: Long,
        u: UplinkState
    ) {
        val now = SystemClock.elapsedRealtime()
        val micRms = calculatePcm16Rms(frame, BURST_BYTES)
        val outNow = bridge.getOutRms()
        u.pushReference(outNow)
        val refMax = u.referenceMax()
        if (outNow > OUT_ACTIVE_RMS) u.lastOutputActiveMs = now

        if (u.candidateSinceMs > 0L && playbackGeneration.get() != u.candidateGeneration) {
            u.candidateSinceMs = 0L
        }

        val paused = u.candidateSinceMs > 0L
        val pendingMs = bridge.getPendingPlaybackDurationMs()
        val rendering = paused || pendingMs > 1.0f || (now - u.lastOutputActiveMs) < RENDER_TAIL_MS

        if (rendering && !u.wasRendering) {
            u.renderingStartedMs = now
            u.idleSpeechFrames = 0
            bargeInDetector.resetStreak()
        }
        if (!rendering && u.wasRendering && !u.uplinkOpen) {
            vadDetector.resetDecisionState()
        }
        u.wasRendering = rendering

        var speechEndedNow = false
        vadDetector.processSamples(
            pcm16 = frame,
            onSpeechStart = {},
            onSpeechEnd = { speechEndedNow = true }
        )
        val vadProb = vadDetector.speechProbability.value
        val speechActive = vadDetector.isSpeechDetected.value
        val noiseFloor = bridge.getMicNoiseFloorRms()

        val convergenceBoost = if (now - u.renderingStartedMs < AEC_CONVERGENCE_MS) 1.8f else 1.0f
        val gatedSpeech = bargeInDetector.isUserSpeechFrame(micRms, refMax, vadProb, noiseFloor, convergenceBoost)

        if (u.uplinkOpen) {
            sendMicDataEvent(obtainAudioEvent(frame, BURST_BYTES