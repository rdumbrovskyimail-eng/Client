package com.client.app.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * УСТРАНЕНИЕ ДЕФЕКТОВ 169 и 170:
 * Высокопроизводительный плеер произношений Forvo, интегрированный в единый тракт AAudio.
 *
 * Особенности:
 * 1. Полная ликвидация android.media.MediaPlayer и AudioTrack.
 * 2. Декодирование MP3 в линейный PCM через платформенный MediaCodec.
 * 3. Конвертация в моно и ресемплинг в нативную частоту Gemini Live (24 кГц).
 * 4. Прямой вывод через NativeAudioEngine::enqueuePlayback() в тот же аппаратный ЦАП WCD9385.
 * 5. Исключение конфликтов системного AudioFocus, переконфигурации AudioPolicy и мигания микрофона.
 * 6. Звук Forvo отображается на AGSL-визуализаторе и проходит через общий регулятор громкости.
 */
@Singleton
class PronunciationPlayer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val audioEngine: NativeAudioEngine,
    private val logger: AppLogger
) {
    private val playerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val lock = Any()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    suspend fun play(url: String): Boolean = withContext(Dispatchers.IO) {
        val job = Job(coroutineContext[Job])
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = job
        }

        _isPlaying.value = true
        var tempFile: File? = null

        try {
            // 1. Скачивание аудиофайла MP3 из Forvo во временный кэш
            val request = Request.Builder().url(url).build()
            val mp3Bytes = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.w("PronunciationPlayer: сбой загрузки аудио Forvo (HTTP ${response.code})")
                    return@withContext false
                }
                response.body?.bytes() ?: return@withContext false
            }

            coroutineContext.ensureActive()

            tempFile = File.createTempFile("forvo_clip_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            // 2. Декодирование MP3 в PCM через MediaCodec
            val decodedPcm = decodeMp3ToPcm(tempFile) ?: run {
                logger.e("PronunciationPlayer: не удалось декодировать аудио Forvo")
                return@withContext false
            }

            coroutineContext.ensureActive()

            // 3. Ресемплинг в нативный формат Gemini (24 кГц 16-бит моно)
            val pcm24k = convertToMono24k(
                pcm = decodedPcm.data,
                inSampleRate = decodedPcm.sampleRate,
                inChannels = decodedPcm.channels
            )

            coroutineContext.ensureActive()

            // 4. Подача в общий тракт AAudio
            val generation = audioEngine.invalidateAndFlushPlayback("forvo_pronunciation")
            audioEngine.enqueuePlayback(pcm24k, generation)

            // Ожидание физического завершения вывода через динамик
            audioEngine.awaitPlaybackDrained(generation, stallTimeoutMs = 1200L)
            return@withContext true
        } catch (e: CancellationException) {
            audioEngine.invalidateAndFlushPlayback("forvo_cancel")
            throw e
        } catch (t: Throwable) {
            logger.e("PronunciationPlayer: ошибка воспроизведения произношения", t)
            audioEngine.invalidateAndFlushPlayback("forvo_error")
            return@withContext false
        } finally {
            runCatching { tempFile?.delete() }
            synchronized(lock) {
                if (activeJob === job) {
                    activeJob = null
                    _isPlaying.value = false
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            activeJob?.cancel()
            activeJob = null
        }
        audioEngine.invalidateAndFlushPlayback("forvo_stop")
        _isPlaying.value = false
    }

    private data class DecodedAudio(
        val data: ByteArray,
        val sampleRate: Int,
        val channels: Int
    )

    private fun decodeMp3ToPcm(file: File): DecodedAudio? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        try {
            extractor.setDataSource(file.absolutePath)
            val numTracks = extractor.trackCount
            var audioTrackIndex = -1
            var format: MediaFormat? = null

            for (i in 0 until numTracks) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    format = trackFormat
                    break
                }
            }

            if (audioTrackIndex < 0 || format == null) return null

            extractor.selectTrack(audioTrackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream(64 * 1024)
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val timeoutUs = 5000L

            val decodeDeadlineMs = android.os.SystemClock.elapsedRealtime() + 10_000L
            while (!sawOutputEos) {
                if (android.os.SystemClock.elapsedRealtime() > decodeDeadlineMs) {
                    logger.w("PronunciationPlayer: декодер не выдал EOS за 10 с — прерываем")
                    return null
                }
                if (!sawInputEos) {
                    val inputBufIndex = codec.dequeueInputBuffer(timeoutUs)
                    if (inputBufIndex >= 0) {
                        val inputBuf = codec.getInputBuffer(inputBufIndex)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inputBufIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                sawInputEos = true
                            } else {
                                val presentationTimeUs = extractor.sampleTime
                                codec.queueInputBuffer(
                                    inputBufIndex, 0, sampleSize, presentationTimeUs, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputBufIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
                if (outputBufIndex >= 0) {
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true
                    }

                    val outputBuf = codec.getOutputBuffer(outputBufIndex)
                    if (outputBuf != null && bufferInfo.size > 0) {
                        outputBuf.position(bufferInfo.offset)
                        outputBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuf.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outputBufIndex, false)
                } else if (outputBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }

            return DecodedAudio(
                data = pcmOut.toByteArray(),
                sampleRate = sampleRate,
                channels = channels
            )
        } catch (t: Throwable) {
            logger.e("PronunciationPlayer: ошибка декодера MediaCodec", t)
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Преобразование PCM-буфера в 16-бит моно 24 кГц с линейной интерполяцией.
     */
    private fun convertToMono24k(pcm: ByteArray, inSampleRate: Int, inChannels: Int): ByteArray {
        val bytesPerSample = 2
        val inFrameSize = bytesPerSample * inChannels
        val totalInFrames = pcm.size / inFrameSize
        if (totalInFrames == 0) return ByteArray(0)

        // 1. Сведение в моно
        val monoSamples = ShortArray(totalInFrames)
        var byteIdx = 0

        if (inChannels == 1) {
            for (i in 0 until totalInFrames) {
                val b0 = pcm[byteIdx++].toInt() and 0xFF
                val b1 = pcm[byteIdx++].toInt()
                monoSamples[i] = ((b1 shl 8) or b0).toShort()
            }
        } else {
            for (i in 0 until totalInFrames) {
                var sum = 0
                for (ch in 0 until inChannels) {
                    val b0 = pcm[byteIdx++].toInt() and 0xFF
                    val b1 = pcm[byteIdx++].toInt()
                    val s = (b1 shl 8) or b0
                    sum += s
                }
                monoSamples[i] = (sum / inChannels).coerceIn(-32768, 32767).toShort()
            }
        }

        if (inSampleRate == 24000) {
            val outBytes = ByteArray(totalInFrames * 2)
            var oIdx = 0
            for (s in monoSamples) {
                val v = s.toInt()
                outBytes[oIdx++] = (v and 0xFF).toByte()
                outBytes[oIdx++] = ((v shr 8) and 0xFF).toByte()
            }
            return outBytes
        }

        // 2. Линейный ресемплинг из inSampleRate в 24000 Гц
        val ratio = inSampleRate.toDouble() / 24000.0
        val targetFrames = (totalInFrames / ratio).roundToInt().coerceAtLeast(1)
        val outBytes = ByteArray(targetFrames * 2)
        var outByteIdx = 0

        for (i in 0 until targetFrames) {
            val inPos = i * ratio
            val index0 = inPos.toInt()
            val index1 = (index0 + 1).coerceAtMost(totalInFrames - 1)
            val frac = inPos - index0

            val s0 = monoSamples[index0].toDouble()
            val s1 = monoSamples[index1].toDouble()
            val interpolated = (s0 + (s1 - s0) * frac).roundToInt().coerceIn(-32768, 32767)

            outBytes[outByteIdx++] = (interpolated and 0xFF).toByte()
            outBytes[outByteIdx++] = ((interpolated shr 8) and 0xFF).toByte()
        }

        return outBytes
    }
}