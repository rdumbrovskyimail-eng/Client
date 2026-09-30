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

    external fun getHardwareCoreInfo(): String

    external fun initAudioRoute(
        isBluetooth: Boolean,
        sampleRate: Int,
        inputDeviceId: Int = 0,
        outputDeviceId: Int = 0
    ): Boolean

    external fun startAudio(): Boolean
    external fun startPlaybackAudio(): Boolean
    external fun startCaptureAudio(): Boolean
    external fun activateCaptureDspAudio(): Boolean
    external fun commitCaptureAdmission(): Boolean

    // Изолированный перезапуск стримов
    external fun restartCaptureStream(): Boolean
    external fun restartPlaybackStream(): Boolean

    external fun stopCaptureAudio()
    external fun stopAudio()
    external fun isAudioDisconnected(): Boolean

    // Конечный автомат и реактивная очередь ошибок
    external fun getEngineState(): Int
    external fun pollAudioError(outData: LongArray): Boolean
    external fun hasPendingError(): Boolean

    // Аппаратные метки времени и sequence number
    external fun getCaptureSequenceNumber(): Long
    external fun getCaptureTimestampNs(): Long
    external fun getPlaybackSequenceNumber(): Long
    external fun getPlaybackPresentationTimestampNs(): Long

    external fun getActualPlaybackSampleRate(): Int
    external fun getActualPlaybackChannels(): Int
    external fun getActualPlaybackFormat(): Int
    external fun getActualPlaybackBurst(): Int
    external fun getActualCaptureSampleRate(): Int
    external fun getActualCaptureChannels(): Int
    external fun getActiveInputDeviceId(): Int
    external fun getActiveOutputDeviceId(): Int

    // Раздельный опрос очередей и точный расчет задержек
    external fun getPendingPlaybackInputFrames(): Long
    external fun getPendingPlaybackOutputFrames(): Long
    external fun getPendingPlaybackFrames(): Long
    external fun getPendingPlaybackDurationMs(): Float
    external fun getTotalEstimatedPlaybackLatencyMs(): Float

    external fun getOutRms(): Float
    external fun getMicRms(): Float
    external fun getMicNoiseFloorRms(): Float
    external fun setPlaybackActiveState(isActive: Boolean)

    external fun isMmapActive(): Boolean
    external fun isExclusiveSharingActive(): Boolean
    external fun setVolume(volume: Float)
    external fun setMicGain(gain: Float)

    external fun writePlaybackByteArray(
        pcmArray: ByteArray,
        offsetBytes: Int,
        lengthBytes: Int,
        generation: Long
    ): Int

    external fun writePlaybackDirect(
        byteBuffer: ByteBuffer,
        offsetBytes: Int,
        lengthBytes: Int,
        generation: Long
    ): Int

    external fun readCaptureDirect(byteBuffer: ByteBuffer, capacityBytes: Int): Int

    external fun flushPlayback(generation: Long)
    external fun triggerBargeInEarcon()

    // Мягкая пауза вывода без потери данных (перебивание с подтверждением)
    external fun setPlaybackPaused(paused: Boolean)
    external fun isPlaybackPaused(): Boolean

    // Тонкомпенсация: 0 = динамик, 1 = гарнитура HFP, 2 = наушники (A2DP/проводные/USB/LE)
    external fun setOutputEqProfile(profile: Int)

    // Блокирующее ожидание целого кадра захвата; true — кадр готов
    external fun waitForCaptureFrames(frames: Int, timeoutMs: Int): Boolean

    // Аудиосессия захвата для явного включения AEC/NS
    external fun getCaptureSessionId(): Int

    // Hi-Fi Bluetooth: вывод как медиа (A2DP) вместо канала связи (HFP)
    external fun setMediaPlaybackUsage(enabled: Boolean)

    external fun tuneNativeSocket(fd: Int)
    external fun getSpectrumData(outArray: FloatArray)
    external fun drainNativeLogs(): Array<String>?

    // --- УСТРАНЕНИЕ ДЕФЕКТОВ 116–120: Метрики телеметрии и баланса фреймов ---

    external fun getCaptureDroppedFrames(): Long
    external fun getPlaybackUnderrunFrames(): Long
    external fun getPlaybackUnderrunCount(): Long
    external fun getPlaybackDroppedFrames(): Long
    external fun getStreamDisconnectCount(): Int
    external fun getLastXRunCount(): Int

    /**
     * Заполняет массив [outArray] (минимум 11 элементов) сквозными показателями E2E Accounting:
     * [0] - totalHardwareCapturedFrames
     * [1] - totalDspProcessedFrames
     * [2] - captureDroppedFrames
     * [3] - totalHardwarePlaybackFrames
     * [4] - playbackUnderrunFrames
     * [5] - playbackUnderrunCount
     * [6] - playbackDroppedFrames
     * [7] - streamDisconnectCount
     * [8] - captureErrorCount
     * [9] - playbackErrorCount
     * [10] - lastXRunCount
     */
    external fun getAudioPipelineDiagnostics(outArray: LongArray): Boolean

    /**
     * Заполняет гистограмму распределения кодов ошибок AAudio (16 корзин по RFC 7004).
     */
    external fun getErrorHistogram(outArray: IntArray): Boolean

    /**
     * Возвращает фактическую активную конфигурацию стрима из Qualcomm ADSP / AudioFlinger:
     * [0] - sampleRate, [1] - channels, [2] - format, [3] - burstFrames,
     * [4] - deviceId, [5] - isMmap (0/1), [6] - isExclusive (0/1).
     */
    external fun getHardwareActiveConfig(outArray: IntArray): Boolean
}