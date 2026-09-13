// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioEngine.kt
package com.client.app.audio

import android.os.Process
import com.client.app.haptics.HapticBargeInManager
import com.client.app.util.AppLogger
import com.client.app.vad.SileroVadDetector
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
    private val bridge: NativeAudioBridge,
    private val vadDetector: SileroVadDetector,
    private val hapticManager: HapticBargeInManager,
    private val router: AudioDeviceRouter,
    private val logger: AppLogger
) {
    companion object {
        private const val BURST_BYTES = 160 * 2
    }

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

    private val _micOutput = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)
    val micOutput: ReceiveChannel<ByteArray> = _micOutput

    // Изоляция исключений аудиотракта от системного краша JVM
    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in NativeAudioEngine", throwable)
    }
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + coroutineExceptionHandler)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    // Ошибка №9 [CONCURRENCY]: Мьютекс взаимного исключения для предотвращения гонок инициализации C++ ядра
    private val audioLifecycleMutex = Mutex()

    // Conflated-канал для защиты от гонок при множественных событиях Bluetooth
    private val routeTransitionChannel = Channel<RouteProfile>(Channel.CONFLATED)

    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7)
    // Атомарная ссылка исключает Torn Reads между фоновым DSP и 120 FPS RenderThread
    val spectrumUniforms = AtomicReference(FloatArray(5))

    private val bufferPool = ArrayDeque<ByteArray>(16).apply {
        repeat(16) { add(ByteArray(BURST_BYTES)) }
    }
    private val poolLock = Any()
    private val leadInBuffer = ArrayDeque<ByteArray>(32)

    private var isSpeechActive = false

    init {
        engineScope.launch {
            for (profile in routeTransitionChannel) {
                applyRouteInternal(profile)
            }
        }
    }

    // Ошибка №9 [CONCURRENCY]: Сериализация старта через audioLifecycleMutex
    suspend fun start(): Boolean = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            if (_isCapturing.value) return@withContext true

            vadDetector.prepare()
            vadDetector.resetState()

            router.start { profile ->
                routeTransitionChannel.trySend(profile)
            }

            val profile = router.currentProfile.value
            val inited = bridge.initAudioRoute(
                isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRate = profile.sampleRateOut
            )
            if (!inited) return@withContext false

            vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)

            if (!bridge.startAudio()) {
                logger.e("NativeAudioEngine: Сбой запуска AAudio")
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

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = bridge.readCaptureDirect(captureDirectBuffer, BURST_BYTES)

                if (bytesRead > 0) {
                    val frame = obtainBuffer()
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(frame, 0, bytesRead)

                    // Ошибка №10 [PERF]: Устранение GC Thrashing — использование пула вместо copyOf()
                    val maxPreRoll = router.currentProfile.value.leadInBufferSizeFrames / 160
                    synchronized(poolLock) {
                        val preRollBuf = obtainBuffer()
                        System.arraycopy(frame, 0, preRollBuf, 0, bytesRead)
                        leadInBuffer.addLast(preRollBuf)
                        while (leadInBuffer.size > maxPreRoll) {
                            recycleBuffer(leadInBuffer.removeFirst())
                        }
                    }

                    var speechTriggeredThisBurst = false
                    vadDetector.processSamples(
                        pcm16 = frame,
                        onSpeechStart = {
                            speechTriggeredThisBurst = true
                            isSpeechActive = true
                            synchronized(poolLock) {
                                while (leadInBuffer.isNotEmpty()) {
                                    _micOutput.trySend(leadInBuffer.removeFirst())
                                }
                            }
                            if (_outLevel.value > 0.05f) {
                                bridge.flushPlayback()
                                if (router.currentProfile.value.path == AudioRoutePath.CMF_BUDS_WIRELESS) {
                                    bridge.triggerBargeInEarcon()
                                }
                                hapticManager.triggerBargeIn()
                                _bargeInEvents.tryEmit(Unit)
                            }
                        },
                        onSpeechEnd = {
                            isSpeechActive = false
                        }
                    )

                    // Ошибка №11 [DEFECT]: Исключение утечки пула bufferPool при фиксации речи
                    if (isSpeechActive) {
                        _micOutput.trySend(frame)
                    } else {
                        recycleBuffer(frame)
                    }
                } else {
                    delay(2)
                }
            }
        }

        spectrumJob = engineScope.launch {
            while (isActive) {
                bridge.getSpectrumData(spectrumRawData)
                val updated = FloatArray(5)
                System.arraycopy(spectrumRawData, 0, updated, 0, 5)
                spectrumUniforms.set(updated)
                _micLevel.value = (spectrumRawData[5] * 3.5f).coerceIn(0f, 1f)
                _outLevel.value = (spectrumRawData[6] * 3.5f).coerceIn(0f, 1f)
                delay(8)
            }
        }
    }

    // Ошибка №9 [CONCURRENCY]: Сериализация смены аудиомаршрута через audioLifecycleMutex
    private suspend fun applyRouteInternal(profile: RouteProfile) = audioLifecycleMutex.withLock {
        withContext(Dispatchers.IO) {
            bridge.stopAudio()
            val success = bridge.initAudioRoute(
                isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRate = profile.sampleRateOut
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
        router.stop()
        vadDetector.resetState()

        _micLevel.value = 0f
        _outLevel.value = 0f
        isSpeechActive = false
    }

    fun setVolume(volume: Float) = bridge.setVolume(volume.coerceIn(0f, 1f))
    fun setMicGain(gain: Float) = bridge.setMicGain(gain.coerceIn(0.5f, 2.0f))

    // Ошибка №12 [DEFECT]: Гарантированная досылка сэмплов речи при переполнении буфера вывода
    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty() || !_isPlaying.value) return
        var offset = 0
        var remaining = pcm.size
        var attempts = 0
        while (remaining > 0 && attempts < 4) {
            val written = bridge.writePlaybackByteArray(pcm, offset, remaining)
            if (written >= remaining) break
            if (written > 0) {
                offset += written
                remaining -= written
            }
            attempts++
            Thread.sleep(2)
        }
    }

    fun flushPlayback() {
        bridge.flushPlayback()
        _outLevel.value = 0f
    }

    private fun obtainBuffer(): ByteArray = synchronized(poolLock) {
        if (bufferPool.isNotEmpty()) bufferPool.removeFirst() else ByteArray(BURST_BYTES)
    }

    private fun recycleBuffer(buf: ByteArray) = synchronized(poolLock) {
        if (bufferPool.size < 32) bufferPool.addLast(buf)
    }
}