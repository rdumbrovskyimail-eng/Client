// >>> FILE: app/src/main/java/com/client/app/audio/PronunciationPlayer.kt
package com.client.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import com.client.app.forvo.ForvoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

@Singleton
class PronunciationPlayer @Inject constructor(
    private val forvoRepo: ForvoRepository
) {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /**
     * Приостанавливает корутину строго на время сетевой буферизации и воспроизведения.
     * Возвращает true при успешном завершении или false при ошибке сети/формата.
     * 
     * @param boostMb Усиление в миллибелах: 600 mB = +6 дБ (комфортный подъем без клиппинга).
     */
    suspend fun play(url: String, boostMb: Int = 600): Boolean = suspendCancellableCoroutine { cont ->
        synchronized(lock) {
            releasePlayerInternal()
            _isPlaying.value = true
        }

        // Честный учет воспроизведения по лицензии Forvo
        forvoRepo.registerPlayback()

        val mp = MediaPlayer()
        synchronized(lock) {
            mediaPlayer = mp
        }

        var isResumed = false
        fun finish(success: Boolean) {
            synchronized(lock) {
                if (mediaPlayer == mp) {
                    releasePlayerInternal()
                }
            }
            if (!isResumed && cont.isActive) {
                isResumed = true
                cont.resume(success)
            }
        }

        cont.invokeOnCancellation {
            synchronized(lock) {
                if (mediaPlayer == mp) {
                    releasePlayerInternal()
                }
            }
        }

        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnPreparedListener { player ->
                synchronized(lock) {
                    if (mediaPlayer != player) {
                        runCatching { player.release() }
                        return@setOnPreparedListener
                    }
                    runCatching {
                        enhancer = LoudnessEnhancer(player.audioSessionId).apply {
                            setTargetGain(boostMb.coerceIn(0, 1500))
                            enabled = true
                        }
                    }
                    player.start()
                }
            }
            mp.setOnCompletionListener {
                finish(true)
            }
            mp.setOnErrorListener { _, _, _ ->
                finish(false)
                true
            }
            mp.setDataSource(url)
            mp.prepareAsync()
        } catch (_: Exception) {
            finish(false)
        }
    }

    /**
     * Принудительная остановка воспроизведения и мгновенный сброс ресурсов
     */
    fun stop() {
        synchronized(lock) {
            releasePlayerInternal()
        }
    }

    private fun releasePlayerInternal() {
        _isPlaying.value = false
        runCatching { enhancer?.release() }
        enhancer = null
        mediaPlayer?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
    }
}