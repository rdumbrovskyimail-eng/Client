// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioEngine.kt
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
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

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
        private const val BURST_BYTES = 160 * 2 // 10 мс @ 16 кГц
        private const val PLAYBACK_GRACE_PERIOD_MS = 300L
        private const val BARGE_IN_DEBOUNCE_MS = 500L
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

    private val _speechEndEvents = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val speechEndEvents: SharedFlow<Unit> = _speechEndEvents.asSharedFlow()

    private val _focusLost = MutableSharedFlow<Boolean>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val focusLost: SharedFlow<Boolean> = _focusLost.asSharedFlow()

    private val _micOutput = Channel<ByteArray>(512, BufferOverflow.DROP_OLDEST)
    val micOutput: ReceiveChannel<ByteArray> = _micOutput

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in NativeAudioEngine", throwable)
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    private val audioLifecycleMutex = Mutex()
    private val routeTransitionChannel = Channel<RouteProfile>(Channel.CONFLATED)

    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7)
    val spectrumUniforms = AtomicReference(FloatArray(5))

    private val bufferPool = ArrayDeque<ByteArray>(32).apply {
        repeat(32) { add(ByteArray(BURST_BYTES)) }
    }
    private val poolLock = Any()
    private val leadInBuffer = ArrayDeque<ByteArray>(32)

    @Volatile private var lastPlaybackStartMs = 0L
    @Volatile private var lastBargeInMs = 0L
    
    // Блокировка долетающих из сети остатков старой фразы при перебивании
    @Volatile var isBargeInActive = false; private set

    init {
        engineScope.launch {
            for (profile in routeTransitionChannel) {
                applyRouteInternal(profile)
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

            router.start { profile ->
                routeTransitionChannel.trySend(profile)
            }

            val profile = router.currentProfile.value
            val inited = bridge.initAudioRoute(
                isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRate = profile.sampleRateOut,
                inputDeviceId = profile.inputDeviceId,
                outputDeviceId = profile.outputDeviceId
            )
            if (!inited) {
                abandonAudioFocus()
                return@withContext false
            }

            vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)

            if (!bridge.startAudio()) {
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
            var wasAiRendering = false

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = bridge.readCaptureDirect(captureDirectBuffer, BURST_BYTES)

                if (bytesRead > 0) {
                    val frame = obtainBuffer()
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(frame, 0, bytesRead)

                    val now = System.currentTimeMillis()
                    val currentOut = _outLevel.value
                    val currentMic = _micLevel.value
                    val isBluetooth = router.currentProfile.value.path == AudioRoutePath.CMF_BUDS_WIRELESS
                    
                    // Акустическое эхо существует только на спикере телефона
                    val isAiRendering = !isBluetooth && (currentOut > 0.04f)

                    // 1. Ликвидация «слепой зоны» спикера S23 Ultra
                    if (wasAiRendering && !isAiRendering) {
                        synchronized(poolLock) {
                            while (leadInBuffer.isNotEmpty()) {
                                _micOutput.trySend(leadInBuffer.removeFirst())
                            }
                        }
                    }
                    wasAiRendering = isAiRendering

                    // 2. Детекция речи и мгновенное перебивание (Barge-In)
                    vadDetector.processSamples(
                        pcm16 = frame,
                        onSpeechStart = {
                            if (isAiRendering) {
                                val echoThreshold = maxOf(0.12f, currentOut * 0.42f)
                                val canBargeIn = (now - lastPlaybackStartMs > PLAYBACK_GRACE_PERIOD_MS) &&
                                                 (now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS) &&
                                                 (currentMic > echoThreshold)

                                if (canBargeIn) {
                                    lastBargeInMs = now
                                    isBargeInActive = true // Блокируем летящие пакеты старой фразы
                                    bridge.flushPlayback()
                                    hapticManager.triggerBargeIn()
                                    _bargeInEvents.tryEmit(Unit)

                                    synchronized(poolLock) {
                                        while (leadInBuffer.isNotEmpty()) {
                                            _micOutput.trySend(leadInBuffer.removeFirst())
                                        }
                                    }
                                }
                            } else if (currentOut > 0.04f) {
                                // На наушниках CMF Buds 2 перебивание мгновенно без барьеров
                                if (now - lastPlaybackStartMs > 180L && now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS) {
                                    lastBargeInMs = now
                                    isBargeInActive = true // Блокируем летящие пакеты старой фразы
                                    bridge.flushPlayback()
                                    bridge.triggerBargeInEarcon()
                                    hapticManager.triggerBargeIn()
                                    _bargeInEvents.tryEmit(Unit)
                                }
                            }
                        },
                        onSpeechEnd = {
                            // Локальный сигнал окончания речи для мгновенного закрытия хода
                            _speechEndEvents.tryEmit(Unit)
                        }
                    )

                    // 3. МАРШРУТИЗАЦИЯ МИКРОФОНА:
                    // На наушниках Bluetooth микрофон льется ВСЕГДА (100% слышимость, ноль задержек).
                    // На спикере: если играет диффузор — копим пре-ролл, если молчит или перебит — шлем сразу.
                    if (isBluetooth || !isAiRendering || isBargeInActive) {
                        _micOutput.trySend(frame.copyOf(bytesRead))
                    } else {
                        synchronized(poolLock) {
                            leadInBuffer.addLast(frame.copyOf(bytesRead))
                            val maxPreRoll = router.currentProfile.value.leadInBufferSizeFrames / 160
                            while (leadInBuffer.size > maxPreRoll) {
                                leadInBuffer.removeFirst()
                            }
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
                // Атомарный буфер обновляется на полной скорости для 120 FPS AGSL-шейдера
                spectrumUniforms.set(updated)

                // Обновляем StateFlow для Compose с комфортной частотой 31 Гц (каждый 4-й тик)
                if (tick++ % 4 == 0) {
                    _micLevel.value = (spectrumRawData[5] * 3.5f).coerceIn(0f, 1f)
                    _outLevel.value = (spectrumRawData[6] * 3.5f).coerceIn(0f, 1f)
                }
                delay(8)
            }
        }
    }

    private suspend fun applyRouteInternal(profile: RouteProfile) = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            bridge.stopAudio()
            val success = bridge.initAudioRoute(
                isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRate = profile.sampleRateOut,
                inputDeviceId = profile.inputDeviceId,
                outputDeviceId = profile.outputDeviceId
            )
            if (success) {
                vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)
                if (_isCapturing.value) {
                    bridge.startAudio()
                }
            }
        }
    }

    fun stop() {
        if (!_isCapturing.value) return
        _isCapturing.value = false
        _isPlaying.value = false

        captureJob?.cancel()
        spectrumJob?.cancel()
        captureJob = null
        spectrumJob = null

        bridge.stopAudio()
        abandonAudioFocus()
        router.stop()
        vadDetector.resetState()

        _micLevel.value = 0f
        _outLevel.value = 0f
        isBargeInActive = false
    }

    fun setVolume(volume: Float) = bridge.setVolume(volume.coerceIn(0f, 1f))
    fun setMicGain(gain: Float) = bridge.setMicGain(gain.coerceIn(0.5f, 2.0f))

    /**
     * Воспроизведение звука с фильтрацией долетающих пакетов старой фразы при перебивании
     */
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty() || !_isPlaying.value) return
        
        // Если пользователь перебил модель — долетающие из сети остатки старой фразы выбрасываются!
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