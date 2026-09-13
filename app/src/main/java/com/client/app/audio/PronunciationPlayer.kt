// >>> FILE: app/src/main/java/com/client/app/audio/PronunciationPlayer.kt
package com.client.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import com.client.app.forvo.ForvoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

private enum class PlayerState { IDLE, PREPARING, PLAYING, RELEASED }

@Singleton
class PronunciationPlayer @Inject constructor(
    private val forvoRepo: ForvoRepository
) {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentState = PlayerState.IDLE

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    suspend fun play(url: String): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                releasePlayerInternal()
                _isPlaying.value = true
                currentState = PlayerState.PREPARING
            }

            val mp = MediaPlayer()
            synchronized(lock) { mediaPlayer = mp }

            var isResumed = false
            fun finish(success: Boolean) {
                synchronized(lock) {
                    if (mediaPlayer == mp) releasePlayerInternal()
                }
                if (!isResumed && cont.isActive) {
                    isResumed = true
                    cont.resume(success)
                }
            }

            cont.invokeOnCancellation {
                synchronized(lock) {
                    if (mediaPlayer == mp) {
                        mp.setOnPreparedListener(null)
                        mp.setOnCompletionListener(null)
                        mp.setOnErrorListener(null)
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
                        if (mediaPlayer != player || currentState == PlayerState.RELEASED) {
                            runCatching { player.release() }
                            return@setOnPreparedListener
                        }
                        currentState = PlayerState.PLAYING

                        // Регистрация воспроизведения
                        forvoRepo.registerSuccessfulPlayback()

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            runCatching {
                                val config = DynamicsProcessing.Config.Builder(
                                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                                    1, false, 0, false, 0, false, 0, true
                                ).build()
                                dynamicsProcessing = DynamicsProcessing(0, player.audioSessionId, config).apply {
                                    val limiter = DynamicsProcessing.Limiter(
                                        true, true, 0, 1.0f, 50.0f, 10.0f, -0.5f, 0.0f
                                    )
                                    setLimiterAllChannelsTo(limiter)
                                    enabled = true
                                }
                            }
                        }
                        player.start()
                    }
                }

                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }

                mp.setDataSource(url)
                mp.prepareAsync()
            } catch (_: Exception) {
                finish(false)
            }
        }
    }

    fun stop() {
        synchronized(lock) { releasePlayerInternal() }
    }

    private fun releasePlayerInternal() {
        _isPlaying.value = false
        currentState = PlayerState.RELEASED
        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null
        mediaPlayer?.let { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
                mp.reset()
                mp.release()
            }
        }
        mediaPlayer = null
    }
}