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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
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
        private const val BURST_BYTES = 160 * 2 // 320 байт = 10 мс @ 16 кГц
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

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7)
    val spectrumUniforms = FloatArray(5)

    // Пул буферов (Zero-Allocation Loop)
    private val bufferPool = ArrayDeque<ByteArray>(8).apply {
        repeat(8) { add(ByteArray(BURST_BYTES)) }
    }
    private val poolLock = Any()

    // Буфер пре-ролла (Lead-In Ring Buffer) для предотвращения обрезки первых согласных
    private val leadInBuffer = ArrayDeque<ByteArray>(16)

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (_isCapturing.value) return@withContext true

        vadDetector.prepare()
        vadDetector.resetState()

        router.start { profile ->
            bridge.initAudioRoute(
                isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRate = profile.sampleRateOut
            )
            vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)
        }

        val profile = router.currentProfile.value
        bridge.initAudioRoute(
            isBluetooth = profile.path == AudioRoutePath.CMF_BUDS_WIRELESS,
            sampleRate = profile.sampleRateOut
        )
        vadDetector.setThresholds(profile.vadThresholdStart, profile.vadThresholdEnd)

        if (!bridge.startAudio()) {
            logger.e("NativeAudioEngine: Сбой запуска нативного AAudio тракта")
            return@withContext false
        }

        _isCapturing.value = true
        _isPlaying.value = true

        captureJob = engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = bridge.readCaptureDirect(captureDirectBuffer, BURST_BYTES)

                if (bytesRead > 0) {
                    val frame = obtainBuffer()
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(frame, 0, bytesRead)

                    synchronized(poolLock) {
                        leadInBuffer.addLast(frame.copyOf(bytesRead))
                        val maxPreRoll = if (router.currentProfile.value.path == AudioRoutePath.CMF_BUDS_WIRELESS) 16 else 10
                        if (leadInBuffer.size > maxPreRoll) {
                            recycleBuffer(leadInBuffer.removeFirst())
                        }
                    }

                    vadDetector.processSamples(
                        pcm16 = frame,
                        onSpeechStart = {
                            // 1. Выброс буфера пре-ролла (Lead-In) в сеть для сохранения начала слов
                            synchronized(poolLock) {
                                while (leadInBuffer.isNotEmpty()) {
                                    _micOutput.trySend(leadInBuffer.removeFirst())
                                }
                            }

                            // 2. Обработка перебивания (Barge-In)
                            if (_outLevel.value > 0.05f) {
                                bridge.flushPlayback()

                                if (router.currentProfile.value.path == AudioRoutePath.CMF_BUDS_WIRELESS) {
                                    bridge.triggerBargeInEarcon() // Звуковой клип в наушники CMF Buds 2
                                }
                                hapticManager.triggerBargeIn()     // Тактильный щелчок мотора S23 Ultra

                                _bargeInEvents.tryEmit(Unit)
                                logger.d("NativeAudioEngine: Barge-In сработал")
                            }
                        },
                        onSpeechEnd = { /* Пауза в речи */ }
                    )

                    _micOutput.trySend(frame)
                } else {
                    delay(2)
                }
            }
        }

        spectrumJob = engineScope.launch {
            while (isActive) {
                bridge.getSpectrumData(spectrumRawData)
                System.arraycopy(spectrumRawData, 0, spectrumUniforms, 0, 5)
                _micLevel.value = (spectrumRawData[5] * 3.5f).coerceIn(0f, 1f)
                _outLevel.value = (spectrumRawData[6] * 3.5f).coerceIn(0f, 1f)
                delay(8) // 120 FPS
            }
        }

        true
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
    }

    fun setVolume(volume: Float) {
        bridge.setVolume(volume.coerceIn(0f, 1f))
    }

    fun setMicGain(gain: Float) {
        bridge.setMicGain(gain.coerceIn(0.5f, 2.0f))
    }

    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty() || !_isPlaying.value) return
        val directBuf = ByteBuffer.allocateDirect(pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        directBuf.put(pcm)
        directBuf.flip()
        bridge.writePlaybackDirect(directBuf, 0, pcm.size)
    }

    fun flushPlayback() {
        bridge.flushPlayback()
        _outLevel.value = 0f
    }

    private fun obtainBuffer(): ByteArray = synchronized(poolLock) {
        if (bufferPool.isNotEmpty()) bufferPool.removeFirst() else ByteArray(BURST_BYTES)
    }

    private fun recycleBuffer(buf: ByteArray) = synchronized(poolLock) {
        if (bufferPool.size < 16) bufferPool.addLast(buf)
    }
}