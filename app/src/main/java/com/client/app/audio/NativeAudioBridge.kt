// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioBridge.kt
package com.client.app.audio

import javax.inject.Inject
import javax.inject.Singleton
import java.nio.ByteBuffer

@Singleton
class NativeAudioBridge @Inject constructor() {
    companion object {
        init {
            System.loadLibrary("client_core")
        }
    }

    /**
     * Возвращает подробную диагностическую строку о текущем состоянии аппаратного тракта Qualcomm HAL,
     * активных частотах АЦП/ЦАП и привязанных ID устройств.
     */
    external fun getHardwareCoreInfo(): String

    /**
     * Инициализация аудиомаршрутов AAudio с явным указанием ID физических портов микрофона и динамика
     * (для предотвращения захвата встроенного микрофона вместо Bluetooth-гарнитуры).
     */
    external fun initAudioRoute(
        isBluetooth: Boolean,
        sampleRate: Int,
        inputDeviceId: Int = 0,
        outputDeviceId: Int = 0
    ): Boolean

    external fun startAudio(): Boolean
    external fun stopAudio()
    external fun setVolume(volume: Float)
    external fun setMicGain(gain: Float)

    // Прямой байтовый массив для воспроизведения сетевых чанков
    external fun writePlaybackByteArray(pcmArray: ByteArray, offsetBytes: Int, lengthBytes: Int): Int

    // Прямые DirectByteBuffer-операции без копирования памяти
    external fun writePlaybackDirect(byteBuffer: ByteBuffer, offsetBytes: Int, lengthBytes: Int): Int
    external fun readCaptureDirect(byteBuffer: ByteBuffer, capacityBytes: Int): Int

    external fun flushPlayback()
    external fun triggerBargeInEarcon()
    external fun tuneNativeSocket(fd: Int)
    external fun getSpectrumData(outArray: FloatArray)

    // Вычитка нативных логов из Lock-Free C++ очереди NativeLogQueue в JVM
    external fun drainNativeLogs(): Array<String>?
}