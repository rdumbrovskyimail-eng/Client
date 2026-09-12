// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioEngine.kt
package com.client.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: NativeAudioBridge,
    private val vadDetector: SileroVadDetector,
    private val hapticManager: HapticBargeInManager,
    private val logger: AppLogger
) {
    companion object {
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
        private const val CAPTURE_BURST_BYTES = 160 * 2 // 320 байт = 10 мс
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _outLevel = MutableStateFlow(0f)
    val outLevel: StateFlow<Float> = _outLevel.asStateFlow()

    // Поток событий локального перебивания (Barge-In)
    private val _bargeInEvents = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bargeInEvents: SharedFlow<Unit> = _bargeInEvents.asSharedFlow()

    val vadProbability: StateFlow<Float> = vadDetector.speechProbability
    val isUserSpeaking: StateFlow<Boolean> = vadDetector.isSpeechDetected

    private val _micOutput = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)
    val micOutput: ReceiveChannel<ByteArray> = _micOutput

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(CAPTURE_BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7)
    val spectrumUniforms = FloatArray(5)

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (_isCapturing.value) return@withContext true

        configureCommunicationRouting(true)
        vadDetector.resetState()

        if (!bridge.startAudio()) {
            logger.e("NativeAudioEngine: Не удалось запустить нативный аудиотракт AAudio")
            return@withContext false
        }

        _isCapturing.value = true
        _isPlaying.value = true

        // Поток откачки и непрерывного нейросетевого VAD анализа
        captureJob = engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ByteArray(CAPTURE_BURST_BYTES)

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = bridge.readCaptureDirect(captureDirectBuffer, CAPTURE_BURST_BYTES)

                if (bytesRead > 0) {
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(chunk, 0, bytesRead)
                    val frameCopy = chunk.copyOf(bytesRead)

                    // Нейросетевой VAD анализ на NPU/CPU
                    vadDetector.processSamples(
                        pcm16 = frameCopy,
                        onSpeechStart = {
                            // Если пользователь заговорил во время воспроизведения звука модели
                            if (_outLevel.value > 0.05f) {
                                // 1. Мгновенный сброс аппаратного буфера динамика (0 мс)
                                flushPlayback()
                                // 2. Тактильный щелчок линейного мотора LRA (<3 мс)
                                hapticManager.triggerBargeIn()
                                // 3. Уведомление сессионного уровня о перебивании
                                _bargeInEvents.tryEmit(Unit)
                                logger.d("NativeAudioEngine: Локальный нейросетевой Barge-In сработал")
                            }
                        },
                        onSpeechEnd = {
                            // Пользователь закончил фразу
                        }
                    )

                    _micOutput.trySend(frameCopy)
                } else {
                    delay(2)
                }
            }
        }

        // Поток обновления спектра для 120 Гц визуализатора
        spectrumJob = engineScope.launch {
            while (isActive) {
                bridge.getSpectrumData(spectrumRawData)
                System.arraycopy(spectrumRawData, 0, spectrumUniforms, 0, 5)
                _micLevel.value = (spectrumRawData[5] * 3.5f).coerceIn(0f, 1f)
                _outLevel.value = (spectrumRawData[6] * 3.5f).coerceIn(0f, 1f)
                delay(8) // ~120 FPS
            }
        }

        logger.d("NativeAudioEngine: Запущен нативный тракт AAudio + Silero VAD v5")
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
        vadDetector.resetState()
        configureCommunicationRouting(false)

        _micLevel.value = 0f
        _outLevel.value = 0f
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

    fun setVolume(volume: Float) {
        bridge.setVolume(volume)
    }

    fun setMicGain(gain: Float) {
        bridge.setMicGain(gain)
    }

    private fun configureCommunicationRouting(enable: Boolean) {
        runCatching {
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) audioManager.setCommunicationDevice(speaker)
                }
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }
    }
}