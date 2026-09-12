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

    external fun getHardwareCoreInfo(): String
    external fun initAudioRoute(isBluetooth: Boolean, sampleRate: Int): Boolean
    external fun startAudio(): Boolean
    external fun stopAudio()
    external fun writePlaybackDirect(byteBuffer: ByteBuffer, offsetBytes: Int, lengthBytes: Int): Int
    external fun readCaptureDirect(byteBuffer: ByteBuffer, capacityBytes: Int): Int
    external fun flushPlayback()
    external fun triggerBargeInEarcon()
    external fun tuneNativeSocket(fd: Int)
    external fun getSpectrumData(outArray: FloatArray)
}