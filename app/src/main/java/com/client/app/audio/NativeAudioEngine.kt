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

        private const val PLAYBACK_GRACE_PERIOD_MS =
            300L

        private const val BARGE_IN_DEBOUNCE_MS =
            500L

        // AUD-068:
        // Do not release the playback gate immediately on the same
        // event loop tick as barge-in.
        private const val BARGE_IN_MIN_HOLD_MS =
            250L

        // Hard local recovery guarantee.
        //
        // This is an application-level safety invariant,
        // not a claimed external standard value.
        private const val BARGE_IN_HARD_RECOVERY_MS =
            3000L

        private const val BARGE_IN_RECOVERY_POLL_MS =
            50L

        private const val PRE_ROLL_FRAMES_CAPACITY =
            20

        // AUD-013:
        // Bound queued capture memory without ever blocking the realtime
        // capture producer on a slow Kotlin/network consumer. At 16 kHz
        // mono PCM16 this is ~16 seconds of audio.
        private const val MAX_MIC_OUTPUT_BACKLOG_BYTES =
            512L * 1024L
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

    private val _focusLost =
        MutableSharedFlow<Boolean>(
            replay = 0,
            extraBufferCapacity = 4,
            onBufferOverflow =
                BufferOverflow.DROP_OLDEST
        )

    val focusLost:
        SharedFlow<Boolean> =
        _focusLost.asSharedFlow()

    // AUD-013:
    // Never suspend the capture producer on a bounded channel. Backlog is
    // explicitly accounted in bytes and has a hard memory ceiling.
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

    private var captureJob:
        Job? = null

    private var spectrumJob:
        Job? = null

    private val audioLifecycleMutex =
        Mutex()

    private val captureDirectMutex =
        Mutex()

    private val routeTransitionChannel =
        Channel<RouteTransitionRequest>(
            Channel.CONFLATED
        )

    // Internal route lifecycle generation.
    private val engineGeneration =
        AtomicLong(0)

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

    // AUD-068:
    // This state is owned only by NativeAudioEngine.
    @Volatile
    var isBargeInActive = false
        private set

    @Volatile
    private var bargeInTimestampMs =
        0L

    private var bargeInLeaseJob:
        Job? = null

    // IMPORTANT:
    // This is the same generation domain that comes from
    // GeminiProtobufLiveClient AudioFrame.generation.
    //
    // engineGeneration above is intentionally NOT compared
    // with this value.
    private val currentPlaybackGeneration =
        AtomicLong(0L)

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

                                _focusLost.tryEmit(
                                    true
                                )
                            }

                            AudioManager.AUDIOFOCUS_GAIN -> {

                                logger.d(
                                    "NativeAudioEngine: AudioFocus восстановлен"
                                )

                                _focusLost.tryEmit(
                                    false
                                )
                            }
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

                        _focusLost.tryEmit(
                            true
                        )

                    } else if (
                        change ==
                            AudioManager.AUDIOFOCUS_GAIN
                    ) {

                        _focusLost.tryEmit(
                            false
                        )
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

    suspend fun start():
        Boolean =
        audioLifecycleMutex.withLock {

            withContext(Dispatchers.IO) {

                if (_isCapturing.value) {
                    return@withContext true
                }

                if (!requestAudioFocus()) {

                    logger.e(
                        "NativeAudioEngine: Сбой запроса AudioFocus"
                    )

                    return@withContext false
                }

                // AUD-070:
                // Do not silently hide a broken production VAD model.
                if (!vadDetector.prepare()) {

                    logger.e(
                        "NativeAudioEngine: Silero VAD model is unavailable or invalid"
                    )

                    router.stop()
                    abandonAudioFocus()

                    return@withContext false
                }

                vadDetector.resetState()
                resetBargeInState()

                val currentGen =
                    engineGeneration.get()

                streamStopGeneration =
                    -1L

                router.start { profile ->

                    routeTransitionChannel
                        .trySend(
                            RouteTransitionRequest(
                                profile,
                                currentGen
                            )
                        )
                }

                val profile =
                    router.currentProfile.value

                val inited =
                    captureDirectMutex.withLock {

                        bridge.initAudioRoute(
                            isBluetooth =
                                profile.path ==
                                    AudioRoutePath
                                        .CMF_BUDS_WIRELESS,

                            sampleRate =
                                profile.sampleRateOut,

                            inputDeviceId =
                                profile.inputDeviceId,

                            outputDeviceId =
                                profile.outputDeviceId
                        )
                    }

                if (!inited) {

                    logger.e(
                        "NativeAudioEngine: Сбой инициализации audio route"
                    )

                    router.stop()
                    abandonAudioFocus()

                    return@withContext false
                }

                vadDetector.setThresholds(
                    profile.vadThresholdStart,
                    profile.vadThresholdEnd
                )

                val started =
                    captureDirectMutex.withLock {
                        bridge.startAudio()
                    }

                if (!started) {

                    logger.e(
                        "NativeAudioEngine: Сбой запуска AAudio"
                    )

                    router.stop()
                    abandonAudioFocus()

                    return@withContext false
                }

                _isCapturing.value =
                    true

                _isPlaying.value =
                    true

                startLoops()

                true
            }
        }

    private fun startLoops() {

        captureJob =
            engineScope.launch {

                Process.setThreadPriority(
                    Process.THREAD_PRIORITY_URGENT_AUDIO
                )

                var isSpeechActiveManual =
                    false

                while (
                    isActive &&
                    _isCapturing.value
                ) {

                    if (
                        bridge.isAudioDisconnected()
                    ) {

                        logger.w(
                            "NativeAudioEngine: Обнаружен дисконнект AAudio, восстанавливаем поток..."
                        )

                        applyRouteInternal(
                            RouteTransitionRequest(
                                router
                                    .currentProfile
                                    .value,
                                engineGeneration.get()
                            )
                        )

                        delay(50)

                        continue
                    }

                    captureDirectBuffer.clear()

                    val bytesRead =
                        captureDirectMutex.withLock {

                            if (
                                !_isCapturing.value
                            ) {
                                return@withLock 0
                            }

                            bridge.readCaptureDirect(
                                captureDirectBuffer,
                                BURST_BYTES
                            )
                        }

                    if (bytesRead > 0) {

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

                        // Monotonic clock for intervals.
                        val now =
                            SystemClock
                                .elapsedRealtime()

                        val currentOut =
                            _outLevel.value

                        val currentMic =
                            _micLevel.value

                        val isBluetooth =
                            router
                                .currentProfile
                                .value
                                .path ==
                                AudioRoutePath
                                    .CMF_BUDS_WIRELESS

                        val isAiRendering =
                            !isBluetooth &&
                            (
                                currentOut > 0.04f
                            )

                        var speechStartedOnFrame =
                            false

                        var speechEndedOnFrame =
                            false

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

                            if (
                                speechStartedOnFrame
                            ) {

                                if (isAiRendering) {

                                    val echoThreshold =
                                        maxOf(
                                            0.12f,
                                            currentOut *
                                                0.42f
                                        )

                                    val canBargeIn =
                                        (
                                            now -
                                                lastPlaybackStartMs >
                                                PLAYBACK_GRACE_PERIOD_MS
                                        ) &&
                                        (
                                            now -
                                                lastBargeInMs >
                                                BARGE_IN_DEBOUNCE_MS
                                        ) &&
                                        (
                                            currentMic >
                                                echoThreshold
                                        )

                                    if (canBargeIn) {

                                        lastBargeInMs =
                                            now

                                        activateBargeIn(
                                            now
                                        )

                                        hapticManager
                                            .triggerBargeIn()

                                        _bargeInEvents
                                            .tryEmit(Unit)

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

                                        for (
                                            pf in
                                            preRoll
                                        ) {

                                            sendMicEvent(
                                                AudioStreamEvent
                                                    .Audio(pf)
                                            )
                                        }

                                    }

                                } else if (
                                    currentOut > 0.04f
                                ) {

                                    if (
                                        now -
                                            lastPlaybackStartMs >
                                            180L &&
                                        now -
                                            lastBargeInMs >
                                            BARGE_IN_DEBOUNCE_MS
                                    ) {

                                        lastBargeInMs =
                                            now

                                        activateBargeIn(
                                            now
                                        )

                                        hapticManager
                                            .triggerBargeIn()

                                        _bargeInEvents
                                            .tryEmit(Unit)
                                    }
                                }
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
                                        )
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
                                speechEndedOnFrame
                            ) {

                                sendMicEvent(
                                    AudioStreamEvent
                                        .SpeechEnd
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
                                        .SpeechStart
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
                                        )
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
                                        )
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
                                speechEndedOnFrame
                            ) {

                                sendMicEvent(
                                    AudioStreamEvent
                                        .SpeechEnd
                                )

                                isSpeechActiveManual =
                                    false
                            }
                        }

                        if (currentAudioBytes !== frame) {
                            recycleBuffer(frame)
                        }

                    } else {

                        delay(2)
                    }
                }
            }

        spectrumJob =
            engineScope.launch {

                var tick = 0

                while (isActive) {

                    bridge.getSpectrumData(
                        spectrumRawData
                    )

                    val updated =
                        FloatArray(5)

                    System.arraycopy(
                        spectrumRawData,
                        0,
                        updated,
                        0,
                        5
                    )

                    spectrumUniforms.set(
                        updated
                    )

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

    private suspend fun applyRouteInternal(
        req: RouteTransitionRequest
    ) =
        audioLifecycleMutex.withLock {

            withContext(Dispatchers.IO) {

                val currentGeneration =
                    engineGeneration.get()

                if (
                    req.generation !=
                        currentGeneration
                ) {
                    return@withContext
                }

                if (
                    !_isPlaying.value &&
                    !_isCapturing.value
                ) {
                    return@withContext
                }

                captureDirectMutex.withLock {

                    bridge.stopAudio()

                    val success =
                        bridge.initAudioRoute(
                            isBluetooth =
                                req.profile.path ==
                                    AudioRoutePath
                                        .CMF_BUDS_WIRELESS,

                            sampleRate =
                                req.profile.sampleRateOut,

                            inputDeviceId =
                                req.profile.inputDeviceId,

                            outputDeviceId =
                                req.profile.outputDeviceId
                        )

                    if (!success) {

                        logger.e(
                            "NativeAudioEngine: route reinitialization failed"
                        )

                        return@withLock
                    }

                    vadDetector.setThresholds(
                        req.profile
                            .vadThresholdStart,

                        req.profile
                            .vadThresholdEnd
                    )

                    if (
                        _isCapturing.value
                    ) {

                        bridge.startAudio()
                    }
                }
            }
        }

    private suspend fun cancelAndJoinBounded(
        job: Job,
        timeoutMs: Long
    ): Boolean {

        if (!job.isActive) {
            return true
        }

        job.cancel()

        return withTimeoutOrNull(
            timeoutMs
        ) {

            job.join()

            true

        } ?: false
    }

    private suspend fun enqueueStreamStopOnce(
        generation: Long
    ): Boolean {

        if (
            streamStopGeneration ==
                generation
        ) {
            return true
        }

        val enqueued =
            sendMicEvent(
                AudioStreamEvent
                    .StreamStop
            )

        if (enqueued) {
            streamStopGeneration = generation
        }

        return enqueued
    }

    suspend fun stopCaptureGraceful(
        gracefulTimeoutMs: Long = 1500L
    ):
        CaptureShutdownResult =
        audioLifecycleMutex.withLock {

            withContext(Dispatchers.IO) {

                var shutdownStatus =
                    CaptureShutdownResult
                        .GRACEFUL_LOSSLESS

                _isCapturing.value =
                    false

                val job =
                    captureJob

                if (
                    job != null &&
                    job.isActive
                ) {

                    val gracefulCompleted =
                        withTimeoutOrNull(
                            gracefulTimeoutMs
                        ) {

                            job.join()

                            true

                        } ?: false

                    if (!gracefulCompleted) {

                        shutdownStatus =
                            CaptureShutdownResult
                                .FORCED_TIMEOUT

                        cancelAndJoinBounded(
                            job,
                            100L
                        )
                    }
                }

                captureJob = null

                synchronized(
                    poolLock
                ) {

                    recycleLeadInBuffersLocked()
                }

                val currentGeneration =
                    engineGeneration.get()

                if (
                    !enqueueStreamStopOnce(
                        currentGeneration
                    )
                ) {

                    shutdownStatus =
                        CaptureShutdownResult
                            .FORCED_TIMEOUT
                }

                return@withContext shutdownStatus
            }
        }

    suspend fun stop() =
        audioLifecycleMutex.withLock {

            withContext(Dispatchers.IO) {

                if (
                    !_isCapturing.value &&
                    !_isPlaying.value
                ) {

                    engineGeneration
                        .incrementAndGet()

                    streamStopGeneration =
                        -1L

                    while (
                        routeTransitionChannel
                            .tryReceive()
                            .isSuccess
                    ) {}

                    return@withContext
                }

                _isCapturing.value =
                    false

                _isPlaying.value =
                    false

                engineGeneration
                    .incrementAndGet()

                streamStopGeneration =
                    -1L

                captureJob?.let {

                    if (it.isActive) {

                        it.cancel()

                        withTimeoutOrNull(
                            500L
                        ) {
                            it.join()
                        }
                    }
                }

                captureJob = null

                spectrumJob?.let {

                    if (it.isActive) {

                        it.cancel()

                        withTimeoutOrNull(
                            500L
                        ) {
                            it.join()
                        }
                    }
                }

                spectrumJob = null

                captureDirectMutex.withLock {

                    bridge.stopAudio()
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

    // AUD-005.5 + AUD-068
    //
    // Compare only within the playback-generation domain shared with
    // GeminiLiveClient.
    //
    // engineGeneration is NOT used here.
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

        val acceptedGeneration =
            currentPlaybackGeneration
                .updateAndGet { current ->
                    maxOf(
                        current,
                        generation
                    )
                }

        if (
            generation <
                acceptedGeneration
        ) {
            return
        }

        if (
            isBargeInActive
        ) {
            return
        }

        if (
            _outLevel.value < 0.02f
        ) {

            lastPlaybackStartMs =
                SystemClock.elapsedRealtime()
        }

        var offset = 0

        val total =
            pcm.size

        while (
            offset < total &&
            _isPlaying.value
        ) {

            if (
                isBargeInActive
            ) {
                return
            }

            if (
                generation !=
                    currentPlaybackGeneration.get()
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

            if (written > 0) {

                offset += written

            } else {

                delay(4)
            }
        }
    }

    fun flushPlayback(
        generation: Long
    ) {

        currentPlaybackGeneration
            .updateAndGet { current ->
                maxOf(
                    current,
                    generation
                )
            }

        bridge.flushPlayback(
            generation
        )

        _outLevel.value =
            0f
    }

    fun triggerBargeInEarcon() {
        bridge.triggerBargeInEarcon()
    }

    // AUD-068:
    //
    // Local barge-in is a temporary gate.
    //
    // It has:
    //   1. minimum hold;
    //   2. local VAD release;
    //   3. hard local recovery deadline.
    //
    // It does NOT depend solely on a server Interrupted event.
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
        while (true) {
            val result = _micOutput.tryReceive()
            val event = result.getOrNull() ?: break
            if (event is AudioStreamEvent.Audio) {
                releaseCapturedBuffer(event.pcm)
            }
        }
        queuedMicOutputBytes.set(0L)
    }

    // AUD-013:
    // Capture-side events are enqueued without suspension. Audio buffers are
    // owned by the channel until SessionManager calls releaseCapturedBuffer().
    private fun sendMicEvent(
        event: AudioStreamEvent
    ): Boolean {

        if (event is AudioStreamEvent.Audio) {
            if (!_isCapturing.value) {
                recycleBuffer(event.pcm)
                return false
            }

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
                            .trySend(
                                AudioStreamEvent.StreamStop
                            )
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

    // Called by the single consumer after it has finished sending one PCM
    // frame downstream. This is the ownership hand-off point for pooled
    // capture buffers.
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