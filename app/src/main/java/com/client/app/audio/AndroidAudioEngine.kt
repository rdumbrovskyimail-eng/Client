// >>> FILE: app/src/main/java/com/client/app/audio/AndroidAudioEngine.kt
package com.client.app.audio

import android.content.Context
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class AndroidAudioEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    companion object {
        const val SAMPLE_RATE_IN = 16000
        const val SAMPLE_RATE_OUT = 24000

        /** 320 сэмплов = ровно 20 мс при 16 кГц — ультранизкая задержка */
        const val CHUNK_SIZE_SAMPLES = 320
        private const val CHUNK_BYTES = CHUNK_SIZE_SAMPLES * 2

        /** Пре-буфер воспроизведения для сглаживания сетевого джиттера */
        private const val JITTER_PRE_BUFFER = 2

        /* ── Пороги локального VAD (10/10 Reference-Based) ── */
        private const val VAD_FLOOR_MIN = 0.012f
        private const val VAD_RATIO_IDLE = 3.0f
        private const val VAD_RATIO_DUCK = 4.5f
        /** Коэффициент просачивания звука из динамиков в микрофон (AEC Leakage) */
        private const val ECHO_LEAKAGE_FACTOR = 0.28f
        private const val VAD_HANGOVER_CHUNKS = 2
        private const val BARGE_IN_DEBOUNCE_MS = 600L
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var isCapturing: Boolean = false; private set
    @Volatile var isPlaying: Boolean = false; private set

    /** Линейное усиление без гармонических искажений формы волны */
    @Volatile var micGain: Float = 1.0f

    @Volatile var playbackVolume: Float = 1.0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            field = clamped
            synchronized(trackLock) { runCatching { audioTrack?.setVolume(clamped) } }
        }

    @Volatile var forceMaxCallVolume: Boolean = false
    private var savedVoiceCallVolume: Int = -1

    /* ── Каналы и потоки ── */
    private val _micOutput = Channel<ByteArray>(128, BufferOverflow.DROP_OLDEST)
    val micOutput: ReceiveChannel<ByteArray> = _micOutput

    /** Событие локального перебивания (срабатывает за <40 мс) */
    private val _bargeIn = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val bargeIn: SharedFlow<Unit> = _bargeIn.asSharedFlow()

    private val _focusLost = MutableSharedFlow<Boolean>(
        replay = 0, extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val focusLost: SharedFlow<Boolean> = _focusLost.asSharedFlow()

    private val _micLevel = MutableStateFlow(0f)
    val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val _outLevel = MutableStateFlow(0f)
    val outLevel: StateFlow<Float> = _outLevel.asStateFlow()

    private var engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var agc: AutomaticGainControl? = null

    private val captureMutex = Mutex()
    private val trackLock = Any()
    private var playbackChannel = Channel<ByteArray>(Channel.UNLIMITED)

    /* ── Метрики воспроизведения и VAD ── */
    @Volatile private var framesWritten: Long = 0L
    @Volatile private var headOffset: Long = 0L
    @Volatile private var noiseFloor: Float = VAD_FLOOR_MIN
    @Volatile private var referencePlaybackRms: Float = 0f
    @Volatile private var speechRun: Int = 0
    @Volatile private var lastBargeInMs: Long = 0L

    private var focusRequest: AudioFocusRequest? = null

    /**
     * Потокобезопасный опрос позиции головки воспроизведения без synchronized-блока.
     */
    fun pendingPlaybackFrames(): Long {
        val t = audioTrack ?: return 0L
        // Маска 0xFFFFFFFFL защищает от знакопеременного инвертирования 32-битного счетчика в Long
        val rawHead = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
        // Корректный учет асинхронного сброса HAL: если rawHead < headOffset, HAL обнулил регистр
        val relativeHead = if (rawHead >= headOffset) rawHead - headOffset else rawHead
        return (framesWritten - relativeHead).coerceAtLeast(0L)
    }

    fun isRenderingAudio(): Boolean = pendingPlaybackFrames() > 0

    fun pendingPlaybackMs(): Long = pendingPlaybackFrames() * 1000L / SAMPLE_RATE_OUT

    /* ═════════════════════════ AUDIO FOCUS ═════════════════════════ */

    private fun requestFocus(): Boolean {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener { change ->
                    when (change) {
                        AudioManager.AUDIOFOCUS_LOSS,
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->
                            _focusLost.tryEmit(true)
                        AudioManager.AUDIOFOCUS_GAIN ->
                            _focusLost.tryEmit(false)
                    }
                }
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        }
    }

    /* ═════════════════════════ МАРШРУТИЗАЦИЯ ═════════════════════════ */

    fun configureSpeakerRouting(enable: Boolean) {
        runCatching {
            if (enable) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val preferred = devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    } ?: devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (preferred != null) {
                        audioManager.setCommunicationDevice(preferred)
                        logger.d("Audio route → ${preferred.type}")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                }

                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
                if (savedVoiceCallVolume == -1) savedVoiceCallVolume = cur
                val target = when {
                    forceMaxCallVolume -> max
                    cur < max * 0.65f -> (max * 0.75f).toInt()
                    else -> cur
                }
                if (target != cur) {
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                }
            } else {
                if (savedVoiceCallVolume != -1) {
                    audioManager.setStreamVolume(
                        AudioManager.STREAM_VOICE_CALL, savedVoiceCallVolume, 0
                    )
                    savedVoiceCallVolume = -1
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

    /* ═════════════════════════ ЗАХВАТ ЗВУКА ═════════════════════════ */

    @Suppress("MissingPermission")
    suspend fun startCapture(): Boolean = captureMutex.withLock {
        if (isCapturing) return@withLock true
        if (!engineScope.isActive) {
            engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }

        configureSpeakerRouting(true)

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            logger.e("getMinBufferSize failed: $minBuf")
            return@withLock false
        }

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
                .setBufferSizeInBytes(maxOf(minBuf, CHUNK_BYTES * 8))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setPrivacySensitive(true)
                    }
                }
                .build()
        } catch (e: Exception) {
            logger.e("AudioRecord creation failed", e)
            return@withLock false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return@withLock false
        }

        // Честный опрос аппаратного Beamforming на S23 Ultra
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                val dirOk = record.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER)
                val dimOk = record.setPreferredMicrophoneFieldDimension(0.75f)
                logger.d("AudioEngine: Beamforming direction=$dirOk, dimension=$dimOk")
            }
        }

        val sid = record.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { echoCanceler = AcousticEchoCanceler.create(sid)?.apply { enabled = true } }
        }
        if (NoiseSuppressor.isAvailable()) {
            runCatching { noiseSuppressor = NoiseSuppressor.create(sid)?.apply { enabled = true } }
        }
        if (AutomaticGainControl.isAvailable()) {
            runCatching { agc = AutomaticGainControl.create(sid)?.apply { enabled = true } }
        }

        try {
            record.startRecording()
        } catch (e: Exception) {
            logger.e("startRecording failed", e)
            releaseEffects()
            record.release()
            return@withLock false
        }

        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            releaseEffects()
            record.release()
            return@withLock false
        }

        audioRecord = record
        isCapturing = true
        noiseFloor = VAD_FLOOR_MIN
        speechRun = 0

        captureJob = engineScope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)

            val chunk = ShortArray(CHUNK_SIZE_SAMPLES)
            val byteBuf = ByteBuffer.allocate(CHUNK_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val shortView = byteBuf.asShortBuffer()

            try {
                while (isActive && isCapturing) {
                    var off = 0
                    while (off < CHUNK_SIZE_SAMPLES && isActive && isCapturing) {
                        val n = runInterruptible {
                            record.read(chunk, off, CHUNK_SIZE_SAMPLES - off)
                        }
                        if (n <= 0) break
                        off += n
                    }
                    if (off < CHUNK_SIZE_SAMPLES) {
                        if (!isActive || !isCapturing) break
                        yield(); continue
                    }

                    var sumSq = 0.0
                    for (i in 0 until CHUNK_SIZE_SAMPLES) {
                        var v = chunk[i] * micGain
                        if (v > 32767f) v = 32767f
                        if (v < -32768f) v = -32768f
                        chunk[i] = v.toInt().toShort()
                        val n = v / 32768f
                        sumSq += (n * n).toDouble()
                    }
                    val rms = sqrt(sumSq / CHUNK_SIZE_SAMPLES).toFloat()

                    shortView.clear()
                    shortView.put(chunk, 0, CHUNK_SIZE_SAMPLES)
                    _micOutput.trySend(byteBuf.array().copyOf())

                    evaluateLocalVad(rms)

                    if (!isRenderingAudio()) {
                        _micLevel.value = (rms * 4f).coerceIn(0f, 1f)
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                logger.e("AudioRecord read loop error", e)
            }
        }
        return@withLock true
    }

    /**
     * Масштабируемый Reference-Based VAD с защитой от самоперебивания
     */
    private fun evaluateLocalVad(micRms: Float) {
        val rendering = isRenderingAudio()

        val updatedFloor = if (micRms < noiseFloor) {
            noiseFloor * 0.90f + micRms * 0.10f
        } else {
            noiseFloor * 0.995f + micRms * 0.005f
        }
        noiseFloor = updatedFloor.coerceAtLeast(VAD_FLOOR_MIN * 0.5f)

        // Опорный сигнал строго масштабируется на системную громкость трека
        val echoLeakage = if (rendering) {
            referencePlaybackRms * playbackVolume * ECHO_LEAKAGE_FACTOR
        } else 0f

        val ratio = if (rendering) VAD_RATIO_DUCK else VAD_RATIO_IDLE
        val dynamicThreshold = maxOf(VAD_FLOOR_MIN, noiseFloor * ratio, echoLeakage)

        if (micRms > dynamicThreshold) {
            speechRun++
        } else {
            speechRun = 0
        }

        if (rendering && speechRun >= VAD_HANGOVER_CHUNKS) {
            val now = System.currentTimeMillis()
            if (now - lastBargeInMs > BARGE_IN_DEBOUNCE_MS) {
                lastBargeInMs = now
                speechRun = 0
                flushPlayback()
                // Вызов Binder IPC вынесен в отдельную корутину, не блокируя аудиопоток
                engineScope.launch { triggerBargeInHaptic() }
                _bargeIn.tryEmit(Unit)
            }
        }
    }

    private fun triggerBargeInHaptic() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                v?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            }
        }
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }; echoCanceler = null
        runCatching { noiseSuppressor?.release() }; noiseSuppressor = null
        runCatching { agc?.release() }; agc = null
    }

    suspend fun stopCapture() = captureMutex.withLock {
        if (!isCapturing && audioRecord == null) return@withLock
        isCapturing = false

        val rec = audioRecord
        runCatching { rec?.stop() }
        runCatching { withTimeoutOrNull(400L) { captureJob?.cancelAndJoin() } }
        captureJob = null

        withContext(Dispatchers.IO) {
            releaseEffects()
            runCatching { rec?.release() }
            audioRecord = null
        }
        _micLevel.value = 0f
    }

    /* ═════════════════════════ ВОСПРОИЗВЕДЕНИЕ ═════════════════════════ */

    suspend fun initPlayback(): Boolean {
        if (isPlaying) return true
        if (!engineScope.isActive) {
            engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        if (playbackChannel.isClosedForSend) playbackChannel = Channel(Channel.UNLIMITED)

        requestFocus()

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false

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
                .setBufferSizeInBytes(minBuf * 2)
                // Аппаратный чистый FastTrack на Snapdragon 8 Gen 2
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
        } catch (e: Exception) {
            logger.e("AudioTrack creation failed", e)
            return false
        }

        audioTrack = track
        framesWritten = 0L
        track.setVolume(playbackVolume)
        track.play()
        isPlaying = true

        playbackJob = engineScope.launch {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            val jitter = ArrayDeque<ByteArray>()

            try {
                while (isActive) {
                    jitter.addLast(playbackChannel.receive())
                    while (jitter.size < JITTER_PRE_BUFFER) {
                        val more = playbackChannel.tryReceive().getOrNull() ?: break
                        jitter.addLast(more)
                    }

                    while (jitter.isNotEmpty() && isActive) {
                        val buf = jitter.removeFirst()
                        val chunkRms = rmsOf(buf)
                        // Envelope follower с плавным спадом (~100 мс), компенсирующий задержку звука до динамика
                        referencePlaybackRms = maxOf(chunkRms, referencePlaybackRms * 0.88f)
                        _outLevel.value = (chunkRms * 4f).coerceIn(0f, 1f)

                        var offset = 0
                        while (offset < buf.size && isActive) {
                            val n = synchronized(trackLock) {
                                val t = audioTrack
                                if (t == null || t.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    -1
                                } else {
                                    t.write(
                                        buf, offset, buf.size - offset,
                                        AudioTrack.WRITE_NON_BLOCKING
                                    )
                                }
                            }
                            if (n < 0) break
                            if (n == 0) { delay(4); continue }
                            offset += n
                            framesWritten += n / 2L
                        }
                    }
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                logger.e("Playback loop error", e)
            }
        }
        return true
    }

    fun enqueuePlayback(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        playbackChannel.trySend(pcm)
    }

    fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { /* drain */ }
        referencePlaybackRms = 0f
        synchronized(trackLock) {
            audioTrack?.let { t ->
                runCatching {
                    if (t.state == AudioTrack.STATE_INITIALIZED) {
                        t.pause()
                        t.flush()
                        val rawHead = runCatching { t.playbackHeadPosition.toLong() and 0xFFFFFFFFL }.getOrDefault(0L)
                        headOffset = rawHead
                        framesWritten = 0L
                        t.play()
                    }
                }
            }
        }
        _outLevel.value = 0f
    }

    fun resetClock() {
        synchronized(trackLock) {
            val rawHead = runCatching { audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFFFFFFL) }.getOrNull() ?: 0L
            headOffset = rawHead
            framesWritten = 0L
        }
    }

    suspend fun releaseAll() {
        stopCapture()
        isPlaying = false

        runCatching { playbackChannel.close() }
        val pJob = playbackJob
        playbackJob = null
        runCatching { withTimeoutOrNull(400L) { pJob?.cancelAndJoin() } }

        synchronized(trackLock) {
            audioTrack?.let { t ->
                runCatching { t.pause(); t.flush(); t.stop(); t.release() }
            }
            audioTrack = null
            framesWritten = 0L
        }

        abandonFocus()
        configureSpeakerRouting(false)
        referencePlaybackRms = 0f
        _outLevel.value = 0f
        _micLevel.value = 0f

        runCatching {
            withTimeoutOrNull(400L) { engineScope.coroutineContext[Job]?.cancelAndJoin() }
        }
    }

    private fun rmsOf(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        var i = 0
        val count = pcm.size / 2
        while (i < pcm.size - 1) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            sum += (s.toDouble() * s.toDouble())
            i += 2
        }
        return (sqrt(sum / count) / 32768.0).toFloat()
    }
}