package com.client.app.logging

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
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
    @ApplicationContext private val context: Context
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
                delay(100) // Батчинг обновлений до 10 кадров/сек для экономии CPU при высокой нагрузке
                if (updateDebounce.getAndSet(0) > 0) {
                    publishSnapshot()
                }
            }
        }
    }

    fun v(tag: String, msg: String, payload: String? = null) = log(LogLevel.VERBOSE, tag, msg, payload)
    fun d(tag: String, msg: String, payload: String? = null) = log(LogLevel.DEBUG, tag, msg, payload)
    fun i(tag: String, msg: String, payload: String? = null) = log(LogLevel.INFO, tag, msg, payload)
    fun w(tag: String, msg: String, payload: String? = null) = log(LogLevel.WARN, tag, msg, payload)
    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val fullMsg = if (tr != null) "$msg\n${tr.stackTraceToString()}" else msg
        log(LogLevel.ERROR, tag, fullMsg)
        _errorCount.update { it + 1 }
    }
    fun net(tag: String, msg: String, payload: String? = null) = log(LogLevel.NETWORK, tag, msg, payload)
    fun audio(tag: String, msg: String, payload: String? = null) = log(LogLevel.AUDIO, tag, msg, payload)
    fun vad(tag: String, msg: String, payload: String? = null) = log(LogLevel.VAD, tag, msg, payload)

    fun log(level: LogLevel, tag: String, message: String, payload: String? = null) {
        val now = System.currentTimeMillis()
        val formattedTime = synchronized(timeFormat) { timeFormat.format(Date(now)) }
        val sanitizedMsg = sanitize(message)
        val sanitizedPayload = payload?.let { sanitize(it) }

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

        updateDebounce.incrementAndGet()
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
                writer.write("Device: Samsung Galaxy S23 Ultra (SM8550-AC)\n")
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