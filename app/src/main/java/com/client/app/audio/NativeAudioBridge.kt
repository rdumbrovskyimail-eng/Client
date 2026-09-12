package com.client.app.audio

import com.client.app.util.AppLogger
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
                // Логирование ошибки до инъекции логгера
                android.util.Log.e("NativeAudioBridge", "Не удалось загрузить libclient_core.so", e)
            }
        }
    }

    external fun getHardwareCoreInfo(): String
    external fun probeMmapSupport(): Boolean

    fun isNativeReady(): Boolean = isLoaded

    fun verifyInitialization(): Boolean {
        if (!isLoaded) {
            logger.e("NativeAudioBridge: Библиотека libclient_core.so не загружена в рантайме")
            return false
        }
        return try {
            val info = getHardwareCoreInfo()
            val mmap = probeMmapSupport()
            logger.d("NativeCore инициализирован: $info (MMAP Exclusive = $mmap)")
            true
        } catch (e: Throwable) {
            logger.e("Сбой вызова JNI NativeCore", e)
            false
        }
    }
}