// >>> FILE: app/src/main/java/com/client/app/audio/PronunciationPlayer.kt
package com.client.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.DynamicsProcessing
import android.os.Build
import kotlinx.coroutines.CancellableContinuation
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
class PronunciationPlayer @Inject constructor() {
    private val lock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentState = PlayerState.IDLE
    private var activeContinuation: CancellableContinuation<Boolean>? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    suspend fun play(url: String): Boolean = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val previousContinuation = synchronized(lock) {
                val previous = activeContinuation
                activeContinuation = null
                releasePlayerInternalLocked()
                _isPlaying.value = true
                currentState = PlayerState.PREPARING
                activeContinuation = cont
                previous
            }
            previousContinuation?.let { previous ->
                if (previous.isActive) previous.resume(false)
            }

            val mp = MediaPlayer()
            synchronized(lock) {
                if (activeContinuation !== cont) {
                    runCatching { mp.release() }
                    return@suspendCancellableCoroutine
                }
                mediaPlayer = mp
            }

            fun finish(success: Boolean) {
                val continuationToResume = synchronized(lock) {
                    if (activeContinuation !== cont) {
                        return@synchronized null
                    }
                    activeContinuation = null
                    releasePlayerInternalLocked()
                    cont
                }
                continuationToResume?.let { continuation ->
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }
            }

            cont.invokeOnCancellation {
                synchronized(lock) {
                    if (activeContinuation === cont) {
                        activeContinuation = null
                        if (mediaPlayer === mp) {
                            releasePlayerInternalLocked()
                        }
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
                    val valid = synchronized(lock) {
                        if (mediaPlayer !== player || activeContinuation !== cont || currentState == PlayerState.RELEASED) {
                            false
                        } else {
                            currentState = PlayerState.PLAYING

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
                            true
                        }
                    }

                    if (!valid) {
                        runCatching { player.release() }
                        return@setOnPreparedListener
                    }

                    try {
                        player.start()
                    } catch (_: Exception) {
                        // Do not leave the continuation suspended when start() fails.
                        finish(false)
                    }
                }

                mp.setOnCompletionListener { finish(true) }
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
    }

    fun stop() {
        val continuationToResume = synchronized(lock) {
            val previous = activeContinuation
            activeContinuation = null
            releasePlayerInternalLocked()
            previous
        }
        continuationToResume?.let { continuation ->
            if (continuation.isActive) continuation.resume(false)
        }
    }

    private fun releasePlayerInternalLocked() {
        _isPlaying.value = false
        currentState = PlayerState.RELEASED

        runCatching { dynamicsProcessing?.release() }
        dynamicsProcessing = null

        mediaPlayer?.let { mp ->
            runCatching { mp.setOnPreparedListener(null) }
            runCatching { mp.setOnCompletionListener(null) }
            runCatching { mp.setOnErrorListener(null) }
            runCatching {
                if (mp.isPlaying) mp.stop()
            }
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        mediaPlayer = null
    }
}
