// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioEngine.kt
package com.client.app.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Process
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: NativeAudioBridge,
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

    private val _micOutput = Channel<ByteArray>(256, BufferOverflow.DROP_OLDEST)
    val micOutput: ReceiveChannel<ByteArray> = _micOutput

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var spectrumJob: Job? = null

    // Direct буферы памяти для Zero-Copy передачи в C++
    private val captureDirectBuffer: ByteBuffer = ByteBuffer.allocateDirect(CAPTURE_BURST_BYTES * 4)
        .order(ByteOrder.LITTLE_ENDIAN)

    private val spectrumRawData = FloatArray(7) // [sub, bass, mid, pres, air, micRms, outRms]
    val spectrumUniforms = FloatArray(5)

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (_isCapturing.value) return@withContext true

        configureCommunicationRouting(true)

        if (!bridge.startAudio()) {
            logger.e("NativeAudioEngine: Не удалось запустить нативный аудиотракт AAudio")
            return@withContext false
        }

        _isCapturing.value = true
        _isPlaying.value = true

        // Поток откачки микрофонных фреймов из нативного кольцевого буфера
        captureJob = engineScope.launch {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val chunk = ByteArray(CAPTURE_BURST_BYTES)

            while (isActive && _isCapturing.value) {
                captureDirectBuffer.clear()
                val bytesRead = bridge.readCaptureDirect(captureDirectBuffer, CAPTURE_BURST_BYTES)

                if (bytesRead > 0) {
                    captureDirectBuffer.position(0)
                    captureDirectBuffer.get(chunk, 0, bytesRead)
                    _micOutput.trySend(chunk.copyOf(bytesRead))
                } else {
                    delay(2) // Защита от холостого цикла ожидания буфера
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
                delay(8) // ~120 раз в секунду
            }
        }

        logger.d("NativeAudioEngine: Запущен нативный тракт AAudio MMAP Exclusive (4.2 мс)")
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