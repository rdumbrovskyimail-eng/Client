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

    /** Legacy all-duplex start; new lifecycle code uses explicit methods. */
    external fun startAudio(): Boolean
    external fun startPlaybackAudio(): Boolean
    external fun startCaptureAudio(): Boolean
    external fun stopCaptureAudio()
    external fun stopAudio()
    external fun isAudioDisconnected(): Boolean
    external fun getActualPlaybackSampleRate(): Int
    external fun getActualPlaybackChannels(): Int
    external fun getActualPlaybackFormat(): Int
    external fun getActualCaptureSampleRate(): Int
    external fun getActualCaptureChannels(): Int
    external fun getActiveInputDeviceId(): Int
    external fun getActiveOutputDeviceId(): Int
    external fun getPendingPlaybackFrames(): Long

    /** Мгновенный атомарный RMS ЦАП (без задержек UI-рендеринга) для Geigel DTD */
    external fun getOutRms(): Float

    /** Мгновенный атомарный RMS АЦП микрофона */
    external fun getMicRms(): Float

    external fun isMmapActive(): Boolean
    external fun isExclusiveSharingActive(): Boolean
    external fun setVolume(volume: Float)
    external fun setMicGain(gain: Float)

    // AUD-005.6: generation строго обязателен
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
    external fun tuneNativeSocket(fd: Int)
    external fun getSpectrumData(outArray: FloatArray)
    external fun drainNativeLogs(): Array<String>?
}