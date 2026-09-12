// >>> FILE: app/src/main/java/com/client/app/audio/AndroidAudioEngine.kt
package com.client.app.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Устаревший класс: полностью вытеснен аппаратным C++ движком NativeAudioEngine.
 * Сохранен как легковесный фасад для исключения конфликтов компиляции DI.
 */
@Deprecated("Используйте NativeAudioEngine", ReplaceWith("NativeAudioEngine"))
@Singleton
class AndroidAudioEngine @Inject constructor(
    private val nativeEngine: NativeAudioEngine
) {
    val micLevel = nativeEngine.micLevel
    val outLevel = nativeEngine.outLevel
    val micOutput = nativeEngine.micOutput
    val isCapturing get() = nativeEngine.isCapturing.value
    val isPlaying get() = nativeEngine.isPlaying.value

    suspend fun startCapture(): Boolean = nativeEngine.start()
    suspend fun stopCapture() = nativeEngine.stop()
    suspend fun initPlayback(): Boolean = true
    fun enqueuePlayback(pcm: ByteArray) = nativeEngine.enqueuePlayback(pcm)
    fun flushPlayback() = nativeEngine.flushPlayback()
    suspend fun releaseAll() = nativeEngine.stop()
}