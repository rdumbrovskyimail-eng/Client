package com.client.app.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    companion object {
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000
        // 40 мс при 16 кГц = 640 сэмплов (1280 байт) — минимальный квант задержки для S23 Ultra
        const val CHUNK_SIZE_SAMPLES = 640
        private const val JITTER_PRE_BUFFER_COUNT = 2
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var isCapturing: Boolean = false; private set
    @Volatile var isPlaying: Boolean = false; private set

    @Volatile var micGain: Float = 1.25f
    @Volatile var playbackVolume: Float = 1.0f
    private var originalVoiceCallVolume: Int = -1

    private val _micOutput = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val micOutput: Flow<ByteArray> = _micOutput.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null

    private val captureMutex = Mutex()
    private val trackLock = Any()
    private var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    @Volatile var audibleUntilMs: Long = 0L; private set

    fun configureSpeakerRouting(forceSpeaker: Boolean) {
        runCatching {
            if (forceSpeaker) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) {
                        audioManager.setCommunicationDevice(speaker)
                        logger.d("AudioEngine: Bound to S23 Ultra Built-in Stereo Speaker")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }
                // Принудительно устанавливаем громкость звонкового тракта на максимум
                if (originalVoiceCallVolume == -1) {
                    originalVoiceCallVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                }
                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxVol, 0)
            } else {
                if (originalVoiceCallVolume != -1) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, originalVoiceCallVolume, 0)
                    originalVoiceCallVolume = -1
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                }
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure { logger.e("Routing failure: ${it.message}") }
    }

    @Suppress("MissingPermission")
    suspend fun startCapture() = captureMutex.withLock {
        if (isCapturing) return@withLock
        if (!engineScope.isActive) engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        configureSpeakerRouting(true)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_IN)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf * 2, CHUNK_SIZE_SAMPLES * 4))
                .build()
        } catch (e: Exception) {
            logger.e("AudioRecord creation failed", e)
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
            }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            logger.e("startRecording failed", e)
            record.release()
            return
        }

        audioRecord = record
        isCapturing = true

        captureJob = engineScope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val inBuf = ShortArray(minBuf)
            val chunkBuf = ShortArray(CHUNK_SIZE_SAMPLES)
            var chunkPos = 0

            val byteBuf = ByteBuffer.allocate(CHUNK_SIZE_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
            val outBytes = byteBuf.array()
            val shortBuf = byteBuf.asShortBuffer()

            try {
                while (isActive && isCapturing) {
                    val read = kotlinx.coroutines.runInterruptible { record.read(inBuf, 0, inBuf.size) }
                    if (read > 0) {
                        for (i in 0 until read) {
                            // Лимитер с кубическим насыщением: быстрый DSP-фильтр без вызовов трансцендентных функций
                            val scaled = inBuf[i] * micGain
                            val norm = scaled / 32768.0f
                            val sat = when {
                                norm > 1.0f -> 1.0f
                                norm < -1.0f -> -1.0f
                                else -> norm * (1.5f - 0.5f * norm * norm)
                            }
                            chunkBuf[chunkPos++] = (sat * 32767.0f).toInt().toShort()

                            if (chunkPos == CHUNK_SIZE_SAMPLES) {
                                shortBuf.clear()
                                shortBuf.put(chunkBuf, 0, CHUNK_SIZE_SAMPLES)
                                _micOutput.tryEmit(outBytes.copyOf())
                                chunkPos = 0
                            }
                        }
                    } else if (read == 0) {
                        yield()
                    } else break
                }
            } catch (e: Exception) {
                logger.e("AudioRecord read loop error", e)
            }
        }
    }

    suspend fun stopCapture() = captureMutex.withLock {
        if (!isCapturing && audioRecord == null) return@withLock
        isCapturing = false

        val rec = audioRecord
        val aec = echoCanceler
        val ns = noiseSuppressor

        runCatching { rec?.stop() }
        runCatching { withTimeoutOrNull(400L) { captureJob?.cancelAndJoin() } }
        captureJob = null

        withContext(Dispatchers.IO) {
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            runCatching { rec?.release() }
            echoCanceler = null
            noiseSuppressor = null
            audioRecord = null
        }
    }

    suspend fun initPlayback() {
        if (isPlaying) return
        if (!engineScope.isActive) engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        if (playbackChannel.isClosedForSend) playbackChannel = Channel(Channel.UNLIMITED)

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_OUT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 3)
                .build()
        } catch (e: Exception) {
            logger.e("AudioTrack creation failed", e)
            return
        }

        audioTrack = track
        track.setVolume(playbackVolume)
        track.play()
        isPlaying = true

        playbackJob = engineScope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val jitterBuf = ArrayDeque<ByteArray>()

            try {
                while (isActive) {
                    val chunk = playbackChannel.receive()
                    jitterBuf.addLast(chunk)

                    if (jitterBuf.size < JITTER_PRE_BUFFER_COUNT && !playbackChannel.isEmpty) {
                        continue
                    }

                    while (jitterBuf.isNotEmpty() && isActive) {
                        val toPlay = jitterBuf.removeFirst()
                        _playbackSync.tryEmit(toPlay)
                        synchronized(trackLock) {
                            if (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                audioTrack?.write(toPlay, 0, toPlay.size)
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val durMs = pcm.size / 48L
        val now = System.currentTimeMillis()
        audibleUntilMs = maxOf(audibleUntilMs, now) + durMs
        playbackChannel.trySend(pcm)
    }

    fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) {}
        audibleUntilMs = 0L
        synchronized(trackLock) {
            audioTrack?.apply {
                runCatching {
                    if (state == AudioTrack.STATE_INITIALIZED) {
                        pause(); flush(); play()
                    }
                }
            }
        }
    }

    fun resetClock() {
        audibleUntilMs = 0L
    }

    suspend fun releaseAll() {
        stopCapture()
        isPlaying = false
        audibleUntilMs = 0L
        configureSpeakerRouting(false)

        runCatching { playbackChannel.close() }
        val pJob = playbackJob
        playbackJob = null
        runCatching { withTimeoutOrNull(400L) { pJob?.cancelAndJoin() } }

        synchronized(trackLock) {
            audioTrack?.let {
                runCatching { it.pause(); it.flush(); it.stop(); it.release() }
            }
            audioTrack = null
        }
        runCatching { withTimeoutOrNull(400L) { engineScope.coroutineContext[Job]?.cancelAndJoin() } }
    }
}