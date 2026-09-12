// >>> FILE: app/src/main/java/com/client/app/audio/NativeAudioBridge.kt
package com.client.app.audio

import com.client.app.util.AppLogger
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NativeAudioBridge @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        private var isLoaded = false

        init {
            try {
                System.loadLibrary("client_core")
                isLoaded = true
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("NativeAudioBridge", "Не удалось загрузить libclient_core.so", e)
            }
        }
    }

    external fun getHardwareCoreInfo(): String
    external fun probeMmapSupport(): Boolean
    external fun startAudio(): Boolean
    external fun stopAudio()
    external fun writePlaybackDirect(byteBuffer: ByteBuffer, offsetBytes: Int, lengthBytes: Int): Int
    external fun readCaptureDirect(byteBuffer: ByteBuffer, capacityBytes: Int): Int
    external fun flushPlayback()
    external fun setVolume(volume: Float)
    external fun setMicGain(gain: Float)
    external fun getSpectrumData(outArray: FloatArray)

    fun isNativeReady(): Boolean = isLoaded
}