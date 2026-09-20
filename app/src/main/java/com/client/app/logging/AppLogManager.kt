package com.client.app.logging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.client.app.audio.NativeAudioBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.withLock

enum class LogLevel(val priority: Int, val tag: String) {
    VERBOSE(2, "V"),
    DEBUG(3, "D"),
    INFO(4, "I"),
    WARN(5, "W"),
    ERROR(6, "E"),
    NETWORK(7, "NET"),
    AUDIO(8, "AUD"),
    VAD(9, "VAD")
}

data class LogEntry(
    val id: Long,
    val timestampMs: Long,
    val timeFormatted: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val threadName: String,
    val payload: String? = null
)

@Singleton
class AppLogManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nativeBridgeProvider: Provider<NativeAudioBridge>
) {
    companion object {
        const val RING_BUFFER_CAPACITY = 5000
        private val API_KEY_REGEX = Regex("(key=AIzaSy[a-zA-Z0-9_-]{33}|AIzaSy[a-zA-Z0-9_-]{33})")
        private val FORVO_KEY_REGEX = Regex("([a-f0-9]{32})")
    }

    private val sequence = AtomicLong(0)
    private val buffer = arrayOfNulls<LogEntry>(RING_BUFFER_CAPACITY)
    private val bufferLock = ReentrantLock()

    private val _logsFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logsFlow: StateFlow<List<LogEntry>> = _logsFlow.asStateFlow()

    private val _errorCount = MutableStateFlow(0)
    val errorCount: StateFlow<Int> = _errorCount.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).apply {
        timeZone = TimeZone.getDefault()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val updateDebounce = AtomicInteger(0)

    init {
        scope.launch {
            while (isActive) {
                delay(100) // Keep UI/log snapshot churn bounded at <=10 Hz
                drainNativeLogsSafely()
                if (updateDebounce.getAndSet(0) > 0) {
                    publishSnapshot()
                }
            }
        }
    }

    /**
     * Выкачивает накопившиеся логи из Lock-Free очереди C++ ядра (NativeLogQueue) в память JVM
     */
    private fun drainNativeLogsSafely() {
        val bridge = runCatching { nativeBridgeProvider.get() }.getOrNull() ?: return
        val rawTriplets = runCatching { bridge.drainNativeLogs() }.getOrNull() ?: return
        if (rawTriplets.isEmpty()) return

        var i = 0
        while (i < rawTriplets.size - 2) {
            val levelCode = rawTriplets[i].toIntOrNull() ?: 4
            val tag = rawTriplets[i + 1]
            val msg = rawTriplets[i + 2]

            val level = when (levelCode) {
                2 -> LogLevel.VERBOSE
                3 -> LogLevel.DEBUG
                4 -> LogLevel.INFO
                5 -> LogLevel.WARN
                6 -> LogLevel.ERROR
                8 -> LogLevel.AUDIO
                9 -> LogLevel.VAD
                else -> LogLevel.INFO
            }

            if (level == LogLevel.ERROR) {
                _errorCount.update { it + 1 }
            }

            internalLog(level, tag, msg, payload = null)
            i += 3
        }
        updateDebounce.incrementAndGet()
    }

    fun v(tag: String, msg: String, payload: String? = null) = log(LogLevel.VERBOSE, tag, msg, payload)
    fun d(tag: String, msg: String, payload: String? = null) = log(LogLevel.DEBUG, tag, msg, payload)
    fun i(tag: String, msg: String, payload: String? = null) = log(LogLevel.INFO, tag, msg, payload)
    fun w(tag: String, msg: String, payload: String? = null) = log(LogLevel.WARN, tag, msg, payload)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) {
            val stack = tr.stackTraceToString().take(12_000)
            "$msg\n$stack"
        } else msg
        log(LogLevel.ERROR, tag, fullMsg)
        _errorCount.update { it + 1 }
    }
    fun net(tag: String, msg: String, payload: String? = null) = log(LogLevel.NETWORK, tag, msg, payload)
    fun audio(tag: String, msg: String, payload: String? = null) = log(LogLevel.AUDIO, tag, msg, payload)
    fun vad(tag: String, msg: String, payload: String? = null) = log(LogLevel.VAD, tag, msg, payload)

    fun log(level: LogLevel, tag: String, message: String, payload: String? = null) {
        internalLog(level, tag, message, payload)
        updateDebounce.incrementAndGet()
    }

    private fun internalLog(level: LogLevel, tag: String, message: String, payload: String?) {
        val now = System.currentTimeMillis()
        val formattedTime = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        val sanitizedMsg = sanitize(message).take(12_000)
        val sanitizedPayload = payload?.let { sanitize(it).take(16_000) }

        val id = sequence.incrementAndGet()
        val entry = LogEntry(
            id = id,
            timestampMs = now,
            timeFormatted = formattedTime,
            level = level,
            tag = tag,
            message = sanitizedMsg,
            threadName = Thread.currentThread().name,
            payload = sanitizedPayload
        )

        bufferLock.withLock {
            val idx = ((id - 1) % RING_BUFFER_CAPACITY).toInt()
            buffer[idx] = entry
        }
    }

    private fun sanitize(input: String): String {
        return input
            .replace(API_KEY_REGEX, "key=AIzaSy***[MASKED]")
            .replace(FORVO_KEY_REGEX) { match ->
                val v = match.value
                if (v.length == 32) "${v.take(4)}***[MASKED]***" else v
            }
    }

    private fun publishSnapshot() {
        val list = mutableListOf<LogEntry>()
        bufferLock.withLock {
            val total = sequence.get()
            val count = minOf(total, RING_BUFFER_CAPACITY.toLong()).toInt()
            val startIdx = if (total > RING_BUFFER_CAPACITY) (total % RING_BUFFER_CAPACITY).toInt() else 0

            for (i in 0 until count) {
                val idx = (startIdx + i) % RING_BUFFER_CAPACITY
                buffer[idx]?.let { list.add(it) }
            }
        }
        _logsFlow.value = list
    }

    fun clear() {
        bufferLock.withLock {
            buffer.fill(null)
            sequence.set(0)
        }
        _errorCount.value = 0
        _logsFlow.value = emptyList()
    }

    suspend fun exportLogsToFile(): Uri? = withContext(Dispatchers.IO) {
        val logs = _logsFlow.value
        if (logs.isEmpty()) return@withContext null

        val logsDir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(logsDir, "gemini_live_log_${System.currentTimeMillis()}.txt")

        runCatching {
            FileOutputStream(file).bufferedWriter().use { writer ->
                writer.write("=== GEMINI LIVE ULTRA SYSTEM LOG DUMP ===\n")
                writer.write("Device: Android runtime\n")
                writer.write("Total Entries: ${logs.size}\n\n")

                logs.forEach { entry ->
                    writer.write("[${entry.timeFormatted}] [${entry.level.tag}] [${entry.threadName}] [${entry.tag}] ${entry.message}\n")
                    entry.payload?.let { writer.write("PAYLOAD: $it\n") }
                }
            }
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull()
    }
}
