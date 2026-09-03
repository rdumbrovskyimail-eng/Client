package com.client.app.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PronunciationPlayer @Inject constructor() {
    private var mediaPlayer: MediaPlayer? = null
    private var enhancer: LoudnessEnhancer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun play(url: String, boostDb: Int = 1200) {
        stop()
        runCatching {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setOnPreparedListener { mp ->
                    runCatching {
                        enhancer = LoudnessEnhancer(mp.audioSessionId).apply {
                            setTargetGain(boostDb.coerceIn(0, 3000))
                            enabled = true
                        }
                    }
                    _isPlaying.value = true
                    mp.start()
                }
                setOnCompletionListener { stop() }
                setOnErrorListener { _, _, _ -> stop(); true }
                setDataSource(url)
                prepareAsync()
            }
        }.onFailure { stop() }
    }

    fun stop() {
        _isPlaying.value = false
        runCatching { enhancer?.release() }; enhancer = null
        mediaPlayer?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        mediaPlayer = null
    }
}