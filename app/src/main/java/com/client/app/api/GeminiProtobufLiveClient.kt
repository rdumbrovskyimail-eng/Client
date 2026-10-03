package com.client.app.api

import android.os.SystemClock
import android.util.Base64
import kotlinx.coroutines.*
import com.client.app.audio.NativeAudioBridge
import com.client.app.audio.NativeAudioEngine
import com.client.app.logging.AppLogManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URLEncoder
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

private const val GEMINI_INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"

/**
 * Структурированная телеметрия качества и потерь аудиоданных сетевого транспорта.
 */
data class TransportAudioStats(
    val serverAudioFramesReceived: Long,
    val serverAudioBytesReceived: Long,
    val transportAudioDroppedBytes: Long,
    val transportAudioDroppedFrames: Long,
    val transportBacklogDropEvents: Long,
    val outboundMicFramesDelivered: Long,
    val outboundMicBytesDelivered: Long
)

@Singleton
class GeminiProtobufLiveClient @Inject constructor(
    private val audioEngine: NativeAudioEngine,
    private val logManager: AppLogManager,
    private val nativeBridge: NativeAudioBridge
) {
    companion object {
        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val MAX_QUEUE_BYTES = 512L * 1024L

        private const val DEFAULT_AUDIO_BATCH_THRESHOLD_BYTES = 640 // 20 мс @ 16 кГц
        private const val MIN_AUDIO_BATCH_THRESHOLD_BYTES = 640     // 20 мс — нижняя граница рекомендаций Live API
        private const val MAX_AUDIO_BATCH_THRESHOLD_BYTES = 1280    // 40 мс @ 16 кГц (высокий RTT)
        private const val DEFAULT_BATCH_DEADLINE_MS = 20L
        
        // Порог жизни кадра 5000 мс гарантирует сохранение всех предложений ответа модели
        private const val AUDIO_FRAME_DEFAULT_TTL_MS = Long.MAX_VALUE / 4 // кадры ответа не протухают

        private const val AUDIO_PCM_CHANNEL_CAPACITY = 32
        private const val MAX_OUTBOUND_AUDIO_BACKLOG_BYTES = 320L * 1024L // ~10 с @ 16 кГц при сетевом провале
        private const val CONTROL_COMMAND_CHANNEL_CAPACITY = 32
        private const val MAX_INITIAL_HISTORY_TURNS = 20

        private const val MAX_AI_AUDIO_BACKLOG_BYTES = 16L * 1024L * 1024L // ~5.8 мин @ 24 кГц
        private const val MAX_DATA_EVENTS_IN_FLIGHT = 512
        private val EMPTY_PCM = ByteArray(0)
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private sealed interface ControlOutboundCommand {
        data object ActivityStart : ControlOutboundCommand
        data object ActivityEnd : ControlOutboundCommand
        data object AudioStreamEnd : ControlOutboundCommand
        data class DirectJson(val jsonMessage: String, val tag: String = "ControlJson") : ControlOutboundCommand
    }

    // Единая исходящая очередь: аудио и управляющие сообщения уходят строго в порядке вызовов
    private sealed interface OutboundItem {
        class Audio(val pcm: ByteArray) : OutboundItem
        class Command(val command: ControlOutboundCommand) : OutboundItem
    }

    @Volatile
    private var outboundChannel: Channel<OutboundItem>? = null
    private val queuedOutboundAudioBytes = AtomicLong(0L)

    // Генерация сервера, прерванная локально: её хвост не воспроизводится (guarded by sessionStateLock)
    private var suppressedServerGenerationId = -1L

    @Volatile private var dynamicBatchThresholdBytes: Int = DEFAULT_AUDIO_BATCH_THRESHOLD_BYTES
    @Volatile private var dynamicBatchDeadlineMs: Long = DEFAULT_BATCH_DEADLINE_MS
    @Volatile private var smoothedRttMs: Float = 40.0f
    @Volatile private var audioBatchFirstWriteMs: Long = 0L
    @Volatile private var lastOutboundAudioSendMs: Long = 0L

    @Volatile private var writerReadySignal: CompletableDeferred<Unit>? = null

    private val totalServerAudioFramesReceived = AtomicLong(0L)
    private val totalServerAudioBytesReceived = AtomicLong(0L)
    private val transportAudioDroppedBytes = AtomicLong(0L)
    private val transportAudioDroppedFrames = AtomicLong(0L)
    private val transportBacklogDropEvents = AtomicLong(0L)

    private val totalOutboundMicFramesDelivered = AtomicLong(0L)
    private val totalOutboundMicBytesDelivered = AtomicLong(0L)

    private val audioFramePool = ArrayDeque<AudioFrame>(64)
    private val audioFramePoolLock = Any()

    init {
        // Подтверждённое локальное перебивание: сначала отсекаем хвост генерации, затем движок сбрасывает вывод
        audioEngine.bargeInCommitListener = { suppressActiveServerGeneration() }
    }

    /** Остаток текущей генерации сервера отбрасывается до её закрытия (interrupted/turnComplete). */
    fun suppressActiveServerGeneration() {
        synchronized(sessionStateLock) {
            if (serverGenerationOpen) {
                suppressedServerGenerationId = activeServerGenerationId
            }
        }
        purgeAudioQueue(-1L)
    }

    fun purgeAudioQueue(targetGeneration: Long = -1L): Int {
        var purgedCount = 0
        synchronized(sessionStateLock) {
            while (true) {
                val frame = _audio.tryReceive().getOrNull() ?: break
                // Все вызовы передают -1L (полная очистка). Частичная очистка канала без push-back
                // невозможна, параметр оставлен только ради совместимости API.
                releaseAudio(frame)
                purgedCount++
            }
        }
        return purgedCount
    }

    fun invalidateAudio(): Long {
        purgeAudioQueue(-1L)
        return audioEngine.invalidateAndFlushPlayback("transport invalidate")
    }

    fun releaseAudio(frame: AudioFrame) {
        val bytes = frame.pcm.size
        if (bytes > 0) {
            val key = DataBudgetKey(frame.sessionId, frame.epoch)
            val counter = audioBudgetBySession[key]
            if (counter != null) {
                if (counter.addAndGet(-bytes.toLong()) <= 0L) {
                    counter.set(0L)
                    audioBudgetBySession.remove(key, counter)
                }
            }
        }
        frame.pcm = EMPTY_PCM // пул не должен удерживать PCM-массивы
        synchronized(audioFramePoolLock) {
            if (audioFramePool.size < 64) {
                audioFramePool.addLast(frame)
            }
        }
    }

    private fun obtainAudioFrame(
        pcm: ByteArray,
        sessionId: Long,
        epoch: Long,
        generation: Long,
        frameId: Long,
        timestampMs: Long,
        sequenceNumber: Long,
        timestampNs: Long,
        ttlMs: Long
    ): AudioFrame = synchronized(audioFramePoolLock) {
        if (audioFramePool.isNotEmpty()) {
            val f = audioFramePool.removeFirst()
            f.pcm = pcm
            f.sessionId = sessionId
            f.epoch = epoch
            f.generation = generation
            f.frameId = frameId
            f.timestampMs = timestampMs
            f.sequenceNumber = sequenceNumber
            f.timestampNs = timestampNs
            f.ttlMs = ttlMs
            f
        } else {
            AudioFrame(pcm, sessionId, epoch, generation, frameId, timestampMs, sequenceNumber, timestampNs, ttlMs)
        }
    }

    fun getTransportAudioStats(): TransportAudioStats = TransportAudioStats(
        serverAudioFramesReceived = totalServerAudioFramesReceived.get(),
        serverAudioBytesReceived = totalServerAudioBytesReceived.get(),
        transportAudioDroppedBytes = transportAudioDroppedBytes.get(),
        transportAudioDroppedFrames = transportAudioDroppedFrames.get(),
        transportBacklogDropEvents = transportBacklogDropEvents.get(),
        outboundMicFramesDelivered = totalOutboundMicFramesDelivered.get(),
        outboundMicBytesDelivered = totalOutboundMicBytesDelivered.get()
    )

    fun updateNetworkRtt(rttMs: Long) {
        if (rttMs <= 0L || rttMs > 2000L) return
        val current = smoothedRttMs
        val next = current * 0.85f + rttMs.toFloat() * 0.15f
        smoothedRttMs = next

        val (threshold, deadline) = when {
            next < 35.0f -> MIN_AUDIO_BATCH_THRESHOLD_BYTES to 15L
            next < 90.0f -> DEFAULT_AUDIO_BATCH_THRESHOLD_BYTES to 20L
            else -> MAX_AUDIO_BATCH_THRESHOLD_BYTES to 35L
        }
        dynamicBatchThresholdBytes = threshold
        dynamicBatchDeadlineMs = deadline
    }

    private val loggingEventListener = object : EventListener() {
        private var tcpConnectStartNs = 0L
        private var tlsConnectStartNs = 0L

        override fun dnsStart(call: Call, domainName: String) {
            logManager.net("OkHttp:DNS", "Старт DNS-резолва: $domainName")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            logManager.net("OkHttp:DNS", "DNS успешен: $domainName -> $inetAddressList")
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            tcpConnectStartNs = SystemClock.elapsedRealtimeNanos()
            logManager.net("OkHttp:TCP", "Подключение к $inetSocketAddress...")
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            val tcpRttMs = (SystemClock.elapsedRealtimeNanos() - tcpConnectStartNs) / 1_000_000L
            updateNetworkRtt(tcpRttMs)
            logManager.net("OkHttp:TCP", "TCP соединение установлено ($protocol, RTT=${tcpRttMs}ms)")
        }

        override fun secureConnectStart(call: Call) {
            tlsConnectStartNs = SystemClock.elapsedRealtimeNanos()
            logManager.net("OkHttp:TLS", "Старт TLS 1.3 хендшейка...")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            val tlsRttMs = (SystemClock.elapsedRealtimeNanos() - tlsConnectStartNs) / 1_000_000L
            updateNetworkRtt(tlsRttMs)
            val tls = handshake?.tlsVersion
            val cipher = handshake?.cipherSuite
            logManager.net("OkHttp:TLS", "TLS успешен: $tls [$cipher] (RTT=${tlsRttMs}ms)")
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            logManager.e("OkHttp:Connect", "Сбой подключения к $inetSocketAddress: ${ioe.message}", ioe)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(
            TunedSocketFactory(
                delegate = SocketFactory.getDefault(),
                nativeBridge = nativeBridge,
                logManager = logManager
            )
        )
        .eventListener(loggingEventListener)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(12, TimeUnit.SECONDS)
        // true: недоступный IP хоста (например, сломанный IPv6) не валит подключение — OkHttp пробует
        // следующий адрес. Касается только установления соединения, открытая сессия не повторяется.
        .retryOnConnectionFailure(true)
        .build()

    private val wsMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    private val epochGen = AtomicLong(0)

    @Volatile
    var epoch: Long = 0L
        private set

    private val sessionStateLock = Any()

    val audioGeneration: Long
        get() = audioEngine.currentPlaybackGeneration

    private val audioBudgetBySession = ConcurrentHashMap<DataBudgetKey, AtomicLong>()

    private data class DataBudgetKey(
        val sessionId: Long,
        val epoch: Long
    )

    private data class QueuedEvent(
        val sessionId: Long,
        val epoch: Long,
        val frameId: Long,
        val generationId: Long,
        val event: GeminiEvent,
        val isDataPlane: Boolean,
        val budgetKey: DataBudgetKey? = null
    )

    private enum class ProtocolPhase {
        IDLE,
        CONNECTING,
        READY,
        ACTIVE,
        AWAITING_INTERACTION_IDLE,
        CLOSING
    }

    private val sessionIdGen = AtomicLong(0L)

    @Volatile
    var sessionId: Long = 0L
        private set

    private val frameIdGen = AtomicLong(0L)
    private val audioFrameSeqGen = AtomicLong(0L)

    private var protocolPhase = ProtocolPhase.IDLE
    private val cancelledToolCallIds = mutableSetOf<String>()
    private val pendingDataBySession = ConcurrentHashMap<DataBudgetKey, AtomicLong>()
    private val dataEventsDroppedCounter = AtomicLong(0L)
    private val generationIdGen = AtomicLong(0L)
    private var activeServerGenerationId = 0L
    private var serverGenerationOpen = false

    private val _events = Channel<QueuedEvent>(Channel.UNLIMITED)

    val events: Flow<GeminiEventEnvelope> =
        _events
            .receiveAsFlow()
            .onEach { queued ->
                if (queued.isDataPlane) {
                    queued.budgetKey?.let { key ->
                        pendingDataBySession[key]?.let { counter ->
                            if (counter.decrementAndGet() <= 0L) {
                                pendingDataBySession.remove(key, counter)
                            }
                        }
                    }
                }
            }
            .filter { queued ->
                queued.epoch == epoch && queued.sessionId == sessionId
            }
            .map {
                GeminiEventEnvelope(
                    sessionId = it.sessionId,
                    epoch = it.epoch,
                    frameId = it.frameId,
                    generationId = it.generationId,
                    event = it.event
                )
            }

    private val _audio = Channel<AudioFrame>(
        capacity = Channel.UNLIMITED,
        onUndeliveredElement = { frame -> releaseAudio(frame) }
    )

    val audio: ReceiveChannel<AudioFrame> = _audio

    val hasPendingAudioFrames: Boolean
        get() = !_audio.isEmpty

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var activeConfig: LiveConfig? = null

    private val audioBatchBuffer = ByteArrayOutputStream(MAX_AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()
    private var isAudioStreamEnded = true

    @Volatile
    private var outboundWorkersScope: CoroutineScope? = null

    private val outboundSendLock = Any()
    private val outboundCommandMutex = Mutex()

    private fun emitControlEvent(
        event: GeminiEvent,
        eventEpoch: Long,
        frameId: Long = 0L,
        generationId: Long = activeServerGenerationId
    ) {
        synchronized(sessionStateLock) {
            if (eventEpoch != epoch) return

            val accepted = _events.trySend(
                QueuedEvent(
                    sessionId = sessionId,
                    epoch = eventEpoch,
                    frameId = frameId,
                    generationId = generationId,
                    event = event,
                    isDataPlane = false
                )
            ).isSuccess

            if (!accepted) {
                logManager.w(
                    "GeminiLive:EventQueue",
                    "Control event rejected because the event channel is closed"
                )
            }
        }
    }

    private fun emitDataEvent(
        event: GeminiEvent,
        eventEpoch: Long,
        frameId: Long = 0L,
        generationId: Long = activeServerGenerationId
    ) {
        synchronized(sessionStateLock) {
            if (eventEpoch != epoch) return

            val key = DataBudgetKey(sessionId, eventEpoch)
            val perSession = pendingDataBySession.computeIfAbsent(key) { AtomicLong(0L) }
            while (true) {
                val current = perSession.get()
                if (current >= MAX_DATA_EVENTS_IN_FLIGHT) {
                    if (perSession.get() == 0L) pendingDataBySession.remove(key, perSession)
                    val droppedTotal = dataEventsDroppedCounter.incrementAndGet()
                    if (droppedTotal == 1L || droppedTotal % 50L == 0L) {
                        logManager.w(
                            "GeminiLive:DataQueue",
                            "Data plane telemetry dropped due to in-flight queue saturation (current in-flight: $current, total dropped: $droppedTotal, event: ${event::class.simpleName})"
                        )
                    }
                    return
                }
                if (perSession.compareAndSet(current, current + 1L)) break
            }

            val result = _events.trySend(
                QueuedEvent(
                    sessionId = sessionId,
                    epoch = eventEpoch,
                    frameId = frameId,
                    generationId = generationId,
                    event = event,
                    isDataPlane = true,
                    budgetKey = key
                )
            )

            if (result.isFailure) {
                if (perSession.decrementAndGet() <= 0L) {
                    pendingDataBySession.remove(key, perSession)
                }
            }
        }
    }

    suspend fun connect(
        cfg: LiveConfig,
        beforeOpen: (suspend () -> Unit)? = null
    ) = wsMutex.withLock {

        validateCompressionConfig(cfg)
        closeInternal()

        val newSessionId = sessionIdGen.incrementAndGet()
        synchronized(sessionStateLock) {
            sessionId = newSessionId
            frameIdGen.set(0L)
            audioFrameSeqGen.set(0L)
            protocolPhase = ProtocolPhase.CONNECTING
            cancelledToolCallIds.clear()
            serverGenerationOpen = false
            suppressedServerGenerationId = -1L
            isReady = false
            activeConfig = cfg
        }

        beforeOpen?.invoke()

        synchronized(sessionStateLock) {
            isReady = false
            activeConfig = cfg
        }

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
            audioBatchFirstWriteMs = 0L
        }

        val myEpoch = epoch
        val mySessionId = synchronized(sessionStateLock) { sessionId }

        val rawKey = cfg.apiKey.trim()
        val encodedKey = URLEncoder.encode(rawKey, "UTF-8")
        val url = "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        logManager.net(
            "WebSocket",
            "Инициализация Bidi сессии ${cfg.model} (epoch=$myEpoch, key=[REDACTED])"
        )

        val req = Request.Builder()
            .url(url)
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .build()

        val ws = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    synchronized(sessionStateLock) {
                        if (myEpoch != epoch) {
                            ws.close(1000, "stale")
                            return
                        }
                        webSocket = ws
                        protocolPhase = ProtocolPhase.CONNECTING
                    }

                    startOutboundWorkers(ws, myEpoch)
                    logManager.net(
                        "WebSocket:Open",
                        "Соединение открыто! HTTP ${response.code} ${response.message}"
                    )

                    emitControlEvent(GeminiEvent.Connected, myEpoch)
                    val setupMsg = buildSetupMessage(cfg)

                    logManager.net("WebSocket:Tx", "Отправка setup сообщения (payload redacted)")

                    val setupAccepted = synchronized(sessionStateLock) {
                        if (myEpoch != epoch || webSocket !== ws) {
                            false
                        } else {
                            synchronized(outboundSendLock) {
                                ws.send(setupMsg)
                            }
                        }
                    }

                    if (!setupAccepted) {
                        logManager.e(
                            "WebSocket:Setup",
                            "OkHttp отверг setup-сообщение; закрываем сессию"
                        )
                        ws.cancel()
                    }
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (synchronized(sessionStateLock) {
                            myEpoch == epoch &&
                                sessionId == mySessionId &&
                                webSocket === ws
                        }) {
                        val logSummary = if (text.contains("\"audio/pcm")) {
                            "[Аудиофрейм получен; размер=${text.length}]"
                        } else {
                            "[WebSocket text frame; размер=${text.length}]"
                        }

                        logManager.net("WebSocket:Rx", logSummary)
                        parseServerJsonMessage(text, myEpoch, ws)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (myEpoch == epoch) {
                        val text = bytes.utf8()
                        // Крупные кадры — это аудио модели: повторный полный разбор JSON ради лога
                        // удваивал работу потока чтения сокета на каждом чанке
                        logManager.net(
                            "WebSocket:RxBinary",
                            if (bytes.size <= 8_192) describeServerFrame(text, bytes.size)
                            else "Rx ${bytes.size} Б: аудио модели"
                        )
                        parseServerJsonMessage(text, myEpoch, ws)
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    logManager.w("WebSocket:Closing", "Сервер инициировал закрытие: $code / '$reason'")
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    logManager.w("WebSocket:Closed", "Соединение закрыто (code=$code, reason='$reason', epoch=$myEpoch)")
                    if (myEpoch != epoch) return

                    synchronized(sessionStateLock) {
                        if (myEpoch != epoch || webSocket !== ws) return
                        isReady = false
                    }
                    stopOutboundWorkers(expectedWs = ws, expectedEpoch = myEpoch)

                    if (isQuotaClose(code, reason)) {
                        emitControlEvent(
                            GeminiEvent.Error(
                                "Gemini отклонил сессию: исчерпана квота (модели или Google Search) " +
                                    "либо проблема с оплатой проекта (код $code). Выключите Google Search " +
                                    "в настройках, проверьте лимиты и биллинг в Google AI Studio " +
                                    "или выберите другую Live-модель.",
                                fatal = true
                            ),
                            myEpoch
                        )
                    }

                    emitControlEvent(
                        GeminiEvent.Disconnected(code, reason, mySessionId, myEpoch),
                        myEpoch
                    )
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val httpCode = response?.code
                    val errBody = runCatching { response?.body?.string() }.getOrNull()

                    if (myEpoch != epoch) {
                        // Наш собственный close()/cancel() старой сессии — не ошибка
                        logManager.d("WebSocket:Failure", "Старый сокет закрыт: ${t.localizedMessage}")
                        return
                    }

                    logManager.e(
                        "WebSocket:Failure",
                        "Сбой сокета (HTTP $httpCode): ${t.localizedMessage}. Ответ: $errBody",
                        t
                    )

                    synchronized(sessionStateLock) {
                        if (myEpoch != epoch || webSocket !== ws) return
                        isReady = false
                    }
                    stopOutboundWorkers(expectedWs = ws, expectedEpoch = myEpoch)

                    val fatal = httpCode == 401 || httpCode == 403 || httpCode == 429

                    emitControlEvent(
                        GeminiEvent.Error("Сетевой сбой ($httpCode): ${t.localizedMessage}", fatal),
                        myEpoch
                    )

                    emitControlEvent(
                        GeminiEvent.Disconnected(httpCode ?: 1006, t.message.orEmpty(), mySessionId, myEpoch),
                        myEpoch
                    )
                }
            }
        )
    }

    private fun startOutboundWorkers(ws: WebSocket, writerEpoch: Long) {
        stopOutboundWorkers()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val outChannel = Channel<OutboundItem>(capacity = Channel.UNLIMITED)
        val readySignal = CompletableDeferred<Unit>()

        synchronized(sessionStateLock) {
            if (writerEpoch != epoch || webSocket !== ws) {
                outChannel.close()
                scope.cancel()
                return
            }
            outboundWorkersScope = scope
            outboundChannel = outChannel
            writerReadySignal = readySignal
            queuedOutboundAudioBytes.set(0L)
        }

        scope.launch {
            while (isActive) {
                delay(10L)
                if (writerEpoch != epoch || webSocket !== ws) break
                if (!isReady) continue

                outboundCommandMutex.withLock {
                    val pendingPayload = synchronized(batchLock) {
                        if (isAudioStreamEnded || audioBatchBuffer.size() == 0) return@synchronized null
                        val now = SystemClock.elapsedRealtime()
                        if (audioBatchFirstWriteMs > 0L && (now - audioBatchFirstWriteMs) >= dynamicBatchDeadlineMs) {
                            audioBatchFirstWriteMs = 0L
                            audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                        } else {
                            null
                        }
                    }
                    if (pendingPayload != null && isWriterCurrent(writerEpoch, ws, outChannel)) {
                        enqueueAudio(outChannel, pendingPayload)
                    }
                }
            }
        }

        scope.launch {
            try {
                while (true) {
                    if (!awaitWriterReady(ws, writerEpoch)) break
                    if (writerEpoch != epoch || webSocket !== ws) break

                    val item = outChannel.receiveCatching().getOrNull() ?: break
                    if (writerEpoch != epoch || !isReady || webSocket !== ws) break

                    var audioBytes = 0
                    val jsonMessage: String = when (item) {
                        is OutboundItem.Audio -> {
                            audioBytes = item.pcm.size
                            queuedOutboundAudioBytes.addAndGet(-audioBytes.toLong())
                            if (!awaitWebSocketQueueCapacity(ws, writerEpoch)) break
                            lastOutboundAudioSendMs = SystemClock.elapsedRealtime()
                            val base64Data = Base64.encodeToString(item.pcm, Base64.NO_WRAP)
                            buildJsonObject {
                                putJsonObject("realtimeInput") {
                                    putJsonObject("audio") {
                                        put("mimeType", GEMINI_INPUT_AUDIO_MIME_TYPE)
                                        put("data", base64Data)
                                    }
                                }
                            }.toString()
                        }

                        is OutboundItem.Command -> buildControlJson(item.command)
                    }

                    val sendAccepted = synchronized(sessionStateLock) {
                        if (writerEpoch != epoch || !isReady || webSocket !== ws) {
                            false
                        } else {
                            synchronized(outboundSendLock) {
                                ws.send(jsonMessage)
                            }
                        }
                    }

                    if (sendAccepted) {
                        if (audioBytes > 0) {
                            totalOutboundMicBytesDelivered.addAndGet(audioBytes.toLong())
                            totalOutboundMicFramesDelivered.addAndGet(audioBytes / 2L)
                        }
                    } else {
                        logManager.w("WebSocket:Writer", "OkHttp отверг исходящее сообщение")
                        if (writerEpoch == epoch && webSocket === ws) {
                            isReady = false
                        }
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logManager.e("WebSocket:Writer", "Сбой воркера отправки", t)
            }
        }
    }

    private fun buildControlJson(command: ControlOutboundCommand): String = when (command) {
        ControlOutboundCommand.ActivityStart ->
            buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonObject("activityStart") {}
                }
            }.toString()

        ControlOutboundCommand.ActivityEnd ->
            buildJsonObject {
                putJsonObject("realtimeInput") {
                    putJsonObject("activityEnd") {}
                }
            }.toString()

        ControlOutboundCommand.AudioStreamEnd ->
            buildJsonObject {
                putJsonObject("realtimeInput") {
                    put("audioStreamEnd", true)
                }
            }.toString()

        is ControlOutboundCommand.DirectJson -> command.jsonMessage
    }

    private fun enqueueAudio(channel: SendChannel<OutboundItem>, pcm: ByteArray): Boolean {
        val bytes = pcm.size.toLong()
        if (queuedOutboundAudioBytes.addAndGet(bytes) > MAX_OUTBOUND_AUDIO_BACKLOG_BYTES) {
            queuedOutboundAudioBytes.addAndGet(-bytes)
            val events = transportBacklogDropEvents.incrementAndGet()
            if (events == 1L || events % 50L == 0L) {
                logManager.w("WebSocket:Writer", "Исходящий аудиобэклог переполнен (сеть не успевает), событий=$events")
            }
            return false
        }
        val ok = channel.trySend(OutboundItem.Audio(pcm)).isSuccess
        if (!ok) queuedOutboundAudioBytes.addAndGet(-bytes)
        return ok
    }

    private suspend fun awaitWriterReady(ws: WebSocket, writerEpoch: Long): Boolean {
        if (isReady && writerEpoch == epoch && webSocket === ws) return true
        val signal = synchronized(sessionStateLock) { writerReadySignal } ?: return false
        signal.await()
        return writerEpoch == epoch && webSocket === ws
    }

    private suspend fun awaitWebSocketQueueCapacity(ws: WebSocket, writerEpoch: Long): Boolean {
        var backoffMs = 2L
        while (ws.queueSize() > MAX_QUEUE_BYTES) {
            if (writerEpoch != epoch || webSocket !== ws) return false
            delay(backoffMs)
            backoffMs = minOf(backoffMs * 2, 16L)
        }
        return (writerEpoch == epoch && isReady && webSocket === ws)
    }

    private fun stopOutboundWorkers(
        expectedWs: WebSocket? = null,
        expectedEpoch: Long? = null
    ) {
        synchronized(sessionStateLock) {
            if (expectedWs != null) {
                if (expectedEpoch == null || expectedEpoch != epoch || webSocket !== expectedWs) {
                    return
                }
            }

            val outCh = outboundChannel
            val scope = outboundWorkersScope

            outboundChannel = null
            outboundWorkersScope = null
            writerReadySignal = null

            outCh?.close()
            scope?.cancel()
            queuedOutboundAudioBytes.set(0L)
        }
    }

    suspend fun sendAudioPcm(pcm: ByteArray, length: Int = pcm.size) {
        if (pcm.isEmpty() || length <= 0) return
        val safeLen = minOf(length, pcm.size)

        outboundCommandMutex.withLock {
            val target = synchronized(batchLock) {
                val (writerEpoch, ws, channel) = currentWriter() ?: return@synchronized null

                isAudioStreamEnded = false
                if (audioBatchBuffer.size() == 0) {
                    audioBatchFirstWriteMs = SystemClock.elapsedRealtime()
                }
                audioBatchBuffer.write(pcm, 0, safeLen)

                val payload = if (audioBatchBuffer.size() >= dynamicBatchThresholdBytes) {
                    audioBatchFirstWriteMs = 0L
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                } else {
                    null
                }

                if (payload == null || !isWriterCurrent(writerEpoch, ws, channel)) null else channel to payload
            } ?: return@withLock

            enqueueAudio(target.first, target.second)
        }
    }

    suspend fun flushAudio() {
        outboundCommandMutex.withLock {
            val pending = synchronized(batchLock) {
                val (writerEpoch, ws, channel) = currentWriter() ?: return@synchronized null
                if (audioBatchBuffer.size() == 0) return@synchronized null

                audioBatchFirstWriteMs = 0L
                val payload = audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                if (!isWriterCurrent(writerEpoch, ws, channel)) null else channel to payload
            } ?: return@withLock

            enqueueAudio(pending.first, pending.second)
        }
    }

    suspend fun sendAudioStreamEnd() {
        outboundCommandMutex.withLock {
            val pending = synchronized(batchLock) {
                if (isAudioStreamEnded) return@synchronized null
                val (writerEpoch, ws, channel) = currentWriter() ?: return@synchronized null

                audioBatchFirstWriteMs = 0L
                val tailPayload = if (audioBatchBuffer.size() == 0) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }
                isAudioStreamEnded = true

                if (!isWriterCurrent(writerEpoch, ws, channel)) null else channel to tailPayload
            } ?: return@withLock

            pending.second?.let { enqueueAudio(pending.first, it) }
            pending.first.trySend(OutboundItem.Command(ControlOutboundCommand.AudioStreamEnd))
        }
    }

    private fun currentWriter(): Triple<Long, WebSocket, Channel<OutboundItem>>? =
        synchronized(sessionStateLock) {
            val ws = webSocket ?: return@synchronized null
            val channel = outboundChannel ?: return@synchronized null
            Triple(epoch, ws, channel)
        }

    private fun isWriterCurrent(
        writerEpoch: Long,
        ws: WebSocket,
        channel: SendChannel<OutboundItem>
    ): Boolean = synchronized(sessionStateLock) {
        writerEpoch == epoch && webSocket === ws && outboundChannel === channel
    }

    private suspend fun sendOrderedControlJson(
        jsonMessage: String,
        tag: String = "ControlJson"
    ): Boolean = outboundCommandMutex.withLock {
        val target = synchronized(sessionStateLock) {
            if (!isReady) return@synchronized null
            val ws = webSocket ?: return@synchronized null
            val channel = outboundChannel ?: return@synchronized null
            Triple(epoch, ws, channel)
        } ?: return@withLock false

        if (!isWriterCurrent(target.first, target.second, target.third)) return@withLock false

        val tail = synchronized(batchLock) {
            if (audioBatchBuffer.size() == 0) {
                null
            } else {
                audioBatchFirstWriteMs = 0L
                audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
            }
        }
        tail?.let { enqueueAudio(target.third, it) }
        target.third.trySend(OutboundItem.Command(ControlOutboundCommand.DirectJson(jsonMessage, tag))).isSuccess
    }

    suspend fun sendRealtimeText(text: String) {
        if (text.isBlank()) return
        val cleanText = text.trim()
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("text", cleanText)
            }
        }.toString()

        logManager.net("WebSocket:TxText", "Отправка realtimeInput.text (length=${cleanText.length})")
        sendOrderedControlJson(jsonMessage, "RealtimeText")
    }

    suspend fun sendRealtimeImage(jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) return

        val base64Data = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("video") {
                    put("mimeType", "image/jpeg")
                    put("data", base64Data)
                }
            }
        }.toString()

        logManager.net("WebSocket:TxImage", "Отправка изображения (${jpegBytes.size} байт) в Control Plane")
        sendOrderedControlJson(jsonMessage, "RealtimeImage")
    }

    suspend fun sendActivityStart() {
        outboundCommandMutex.withLock {
            val pending = synchronized(batchLock) {
                val (writerEpoch, ws, channel) = currentWriter() ?: return@synchronized null

                audioBatchFirstWriteMs = 0L
                val preRoll = if (audioBatchBuffer.size() == 0) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }
                isAudioStreamEnded = false

                if (!isWriterCurrent(writerEpoch, ws, channel)) null else channel to preRoll
            } ?: return@withLock

            pending.first.trySend(OutboundItem.Command(ControlOutboundCommand.ActivityStart))
            pending.second?.let { enqueueAudio(pending.first, it) }
        }
    }

    suspend fun sendActivityEnd() {
        outboundCommandMutex.withLock {
            val pending = synchronized(batchLock) {
                val (writerEpoch, ws, channel) = currentWriter() ?: return@synchronized null

                audioBatchFirstWriteMs = 0L
                val tailPayload = if (audioBatchBuffer.size() == 0) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }
                isAudioStreamEnded = true

                if (!isWriterCurrent(writerEpoch, ws, channel)) null else channel to tailPayload
            } ?: return@withLock

            pending.second?.let { enqueueAudio(pending.first, it) }
            pending.first.trySend(OutboundItem.Command(ControlOutboundCommand.ActivityEnd))
        }
    }

    suspend fun sendClientContent(
        turns: List<ClientTurn>,
        turnComplete: Boolean = true
    ) {
        if (turns.isEmpty()) return

        val jsonMessage = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    turns.forEach { turn ->
                        addJsonObject {
                            put("role", turn.role.value)
                            putJsonArray("parts") {
                                addJsonObject { put("text", turn.text) }
                            }
                        }
                    }
                }
                put("turnComplete", turnComplete)
            }
        }.toString()

        logManager.net("WebSocket:TxClientContent", "Отправка clientContent (${turns.size} ходов, turnComplete=$turnComplete)")
        sendOrderedControlJson(jsonMessage, "ClientContent")
    }

    private fun sendClientContentInternal(
        turns: List<ClientTurn>,
        turnComplete: Boolean,
        targetWebSocket: WebSocket? = null,
        expectedEpoch: Long = epoch
    ): Boolean {
        val ws = targetWebSocket ?: webSocket ?: return false

        val jsonMessage = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    turns.forEach { turn ->
                        addJsonObject {
                            put("role", turn.role.value)
                            putJsonArray("parts") {
                                addJsonObject { put("text", turn.text) }
                            }
                        }
                    }
                }
                put("turnComplete", turnComplete)
            }
        }.toString()

        logManager.net("WebSocket:TxClientContent", "Отправка clientContent (${turns.size} ходов, turnComplete=$turnComplete)")

        return synchronized(sessionStateLock) {
            val allowed = expectedEpoch == epoch &&
                webSocket === ws &&
                (targetWebSocket != null || isReady)

            if (!allowed) {
                false
            } else {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    suspend fun sendToolResponses(
        responses: List<ToolResponse>
    ): Boolean {
        if (responses.isEmpty()) return false

        val targetData = synchronized(sessionStateLock) {
            val cfg = activeConfig ?: return@synchronized null
            val caps = LiveModelCapabilitiesRegistry.forModel(cfg.model)

            if (!isReady) return@synchronized null

            val activeResponses = responses.filter { response ->
                val id = response.id
                !id.isNullOrBlank() && !cancelledToolCallIds.contains(id)
            }

            if (activeResponses.isEmpty()) {
                logManager.w("GeminiLive:ToolResp", "Все FunctionResponse относятся к отменённым tool calls")
                return@synchronized null
            }

            val jsonMsg = buildJsonObject {
                putJsonObject("toolResponse") {
                    putJsonArray("functionResponses") {
                        activeResponses.forEach { resp ->
                            addJsonObject {
                                put("id", resp.id!!.trim())
                                put("name", resp.name)
                                put("response", resp.response)

                                if (caps.supportsFunctionScheduling && resp.scheduling != null) {
                                    put("scheduling", resp.scheduling.name)
                                }

                                if (resp.willContinue) {
                                    put("willContinue", true)
                                }

                                if (resp.parts.isNotEmpty()) {
                                    putJsonArray("parts") {
                                        resp.parts.forEach { part ->
                                            addJsonObject {
                                                putJsonObject("inlineData") {
                                                    put("mimeType", part.mimeType)
                                                    put("data", part.base64Data)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.toString()

            val dbgLog = activeResponses.joinToString { "${it.name}(id=${it.id}, sched=${it.scheduling?.name ?: "NONE"})" }
            jsonMsg to dbgLog
        } ?: return false

        val (jsonMessage, debugLog) = targetData
        val accepted = sendOrderedControlJson(jsonMessage, "ToolResponse")
        logManager.net("WebSocket:ToolResp", "Ответы функций enqueued=$accepted: [$debugLog]")
        return accepted
    }

    private fun validateCompressionConfig(cfg: LiveConfig) {
        LiveModelCapabilitiesRegistry.forModel(cfg.model)
        val trigger = cfg.compression.triggerTokens
        val target = cfg.compression.targetTokens

        require(trigger >= 0) { "contextWindowCompression.triggerTokens must be >= 0" }
        require(target >= 0) { "contextWindowCompression.slidingWindow.targetTokens must be >= 0" }
    }

    private fun normalizeToolsForModel(
        tools: JsonArray?,
        capabilities: LiveModelCapabilities
    ): JsonArray? {
        if (tools.isNullOrEmpty()) return null

        return buildJsonArray {
            tools.forEach { tool ->
                val toolObj = tool as? JsonObject
                val declarations = toolObj?.get("functionDeclarations") as? JsonArray

                if (toolObj == null || declarations == null) {
                    add(tool)
                    return@forEach
                }

                add(
                    buildJsonObject {
                        toolObj.forEach { (key, value) ->
                            if (key != "functionDeclarations") put(key, value)
                        }

                        putJsonArray("functionDeclarations") {
                            declarations.forEach { declarationElement ->
                                val declaration = declarationElement as? JsonObject
                                    ?: run {
                                        add(declarationElement)
                                        return@forEach
                                    }

                                add(
                                    buildJsonObject {
                                        declaration.forEach { (key, value) ->
                                            if (!(key == "behavior" && !capabilities.supportsAsyncFunctionCalling)) {
                                                put(key, value)
                                            }
                                        }
                                        if (capabilities.requiresNonBlockingTools) {
                                            put("behavior", "NON_BLOCKING")
                                        }
                                    }
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    private fun buildSetupMessage(cfg: LiveConfig): String {
        val capabilities = LiveModelCapabilitiesRegistry.forModel(cfg.model)
        val rawModelName = cfg.model.trim().removePrefix("publishers/google/models/").removePrefix("models/")
        val cleanModel = "models/$rawModelName"

        val setupObj = buildJsonObject {
            putJsonObject("setup") {
                put("model", cleanModel)
                putJsonObject("generationConfig") {
                    if (capabilities.supportsThinkingConfig && !cfg.thinkingLevel.isNullOrBlank()) {
                        putJsonObject("thinkingConfig") {
                            put("thinkingLevel", cfg.thinkingLevel.lowercase())
                        }
                    }

                    putJsonArray("responseModalities") {
                        add("AUDIO")
                    }

                    put("temperature", cfg.temperature)
                    put("mediaResolution", cfg.mediaResolution)

                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", cfg.voiceName)
                            }
                        }
                    }
                }

                if (cfg.inputTranscription.enabled) {
                    putJsonObject("inputAudioTranscription") {
                        val inputLangs = if (cfg.inputTranscription.languageCodes.isNotEmpty()) {
                            cfg.inputTranscription.languageCodes
                        } else {
                            listOf(cfg.speechLanguage?.ifBlank { "ru-RU" } ?: "ru-RU")
                        }

                        putJsonArray("languageCodes") {
                            inputLangs.forEach { add(it) }
                        }

                        if (cfg.inputTranscription.customVocabulary.isNotEmpty()) {
                            putJsonArray("customVocabulary") {
                                cfg.inputTranscription.customVocabulary.forEach { add(it) }
                            }
                        }
                        put("mode", cfg.inputTranscription.mode)
                    }
                }

                if (cfg.outputTranscription.enabled) {
                    putJsonObject("outputAudioTranscription") {
                        val outputLangs = if (cfg.outputTranscription.languageCodes.isNotEmpty()) {
                            cfg.outputTranscription.languageCodes
                        } else {
                            listOf(cfg.speechLanguage?.ifBlank { "ru-RU" } ?: "ru-RU")
                        }

                        putJsonArray("languageCodes") {
                            outputLangs.forEach { add(it) }
                        }

                        if (cfg.outputTranscription.customVocabulary.isNotEmpty()) {
                            putJsonArray("customVocabulary") {
                                cfg.outputTranscription.customVocabulary.forEach { add(it) }
                            }
                        }
                        put("mode", cfg.outputTranscription.mode)
                    }
                }

                if (cfg.compression.enabled) {
                    val trigger = cfg.compression.triggerTokens
                    val target = cfg.compression.targetTokens

                    putJsonObject("contextWindowCompression") {
                        if (trigger > 0) put("triggerTokens", trigger)
                        putJsonObject("slidingWindow") {
                            if (target > 0) put("targetTokens", target)
                        }
                    }
                }

                putJsonObject("realtimeInputConfig") {
                    putJsonObject("automaticActivityDetection") {
                        put("disabled", !cfg.realtimeInput.aadEnabled)

                        if (cfg.realtimeInput.aadEnabled) {
                            put("startOfSpeechSensitivity", cfg.realtimeInput.startSensitivity)
                            put("endOfSpeechSensitivity", cfg.realtimeInput.endSensitivity)
                            put("prefixPaddingMs", cfg.realtimeInput.prefixPaddingMs)
                            put("silenceDurationMs", cfg.realtimeInput.silenceDurationMs)
                        }
                    }
                    put("activityHandling", cfg.realtimeInput.activityHandling)
                    put("turnCoverage", cfg.realtimeInput.turnCoverage)
                }

                if (cfg.systemInstruction.isNotBlank()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            addJsonObject {
                                put("text", cfg.systemInstruction)
                            }
                        }
                    }
                }

                val allTools = buildJsonArray {
                    normalizeToolsForModel(cfg.toolsJson, capabilities)?.forEach { add(it) }
                    if (cfg.enableGoogleSearch && capabilities.supportsSearchGrounding) {
                        addJsonObject {
                            putJsonObject("googleSearch") {}
                        }
                    }
                }

                if (allTools.isNotEmpty()) {
                    put("tools", allTools)
                }

                if (cfg.sessionResumptionEnabled) {
                    putJsonObject("sessionResumption") {
                        cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let { handle ->
                            put("handle", handle)
                        }
                    }
                }

                if (cfg.initialHistory.isNotEmpty()) {
                    putJsonObject("historyConfig") {
                        put("initialHistoryInClientContent", true)
                    }
                }
            }
        }

        return setupObj.toString()
    }

    private fun parseServerJsonMessage(
        text: String,
        myEpoch: Long,
        sourceWebSocket: WebSocket
    ) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }
            .getOrElse {
                logManager.w("GeminiLive:Parse", "Некорректный JSON server frame отброшен")
                return
            }

        val frameId = frameIdGen.incrementAndGet()
        val mySessionId = synchronized(sessionStateLock) {
            if (myEpoch != epoch || webSocket !== sourceWebSocket) return
            sessionId
        }

        val modelParts = root["serverContent"]
            ?.jsonObject
            ?.get("modelTurn")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray

        val decodedPcmParts = modelParts?.map { partEl ->
            val inline = partEl.jsonObject["inlineData"]?.jsonObject ?: return@map null
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val data = inline["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!mime.startsWith("audio/pcm") || data.isEmpty()) return@map null
            runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull()
        }.orEmpty()

        val frameGenerationId = synchronized(sessionStateLock) {
            if (!serverGenerationOpen && modelParts?.isNotEmpty() == true) {
                activeServerGenerationId = generationIdGen.incrementAndGet()
                serverGenerationOpen = true
            }
            activeServerGenerationId
        }

        fun emitControl(event: GeminiEvent) =
            emitControlEvent(event, myEpoch, frameId, frameGenerationId)
        fun emitData(event: GeminiEvent) =
            emitDataEvent(event, myEpoch, frameId, frameGenerationId)

        synchronized(sessionStateLock) {
            if (myEpoch != epoch || webSocket !== sourceWebSocket) return

            root["usageMetadata"]?.jsonObject?.get("totalTokenCount")
                ?.jsonPrimitive?.intOrNull?.let { emitData(GeminiEvent.Usage(it)) }

            val setupHistory = if (root.containsKey("setupComplete")) {
                synchronized(sessionStateLock) {
                    if (myEpoch == epoch && webSocket === sourceWebSocket && !isReady) {
                        activeConfig?.initialHistory.orEmpty().takeLast(MAX_INITIAL_HISTORY_TURNS)
                    } else {
                        null
                    }
                }
            } else {
                null
            }

            if (setupHistory != null) {
                if (setupHistory.isNotEmpty()) {
                    val accepted = sendClientContentInternal(
                        turns = setupHistory,
                        turnComplete = true,
                        targetWebSocket = sourceWebSocket,
                        expectedEpoch = myEpoch
                    )
                    if (!accepted) {
                        sourceWebSocket.close(1011, "initial history send failed")
                        return
                    }
                }

                synchronized(sessionStateLock) {
                    if (myEpoch == epoch && webSocket === sourceWebSocket && !isReady) {
                        isReady = true
                        protocolPhase = ProtocolPhase.READY
                        writerReadySignal?.complete(Unit)
                        emitControl(GeminiEvent.SetupComplete)
                    } else {
                        return
                    }
                }
            }

            root["sessionResumptionUpdate"]?.jsonObject?.let { sru ->
                val handle = sru["newHandle"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val resumable = sru["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
                emitControl(GeminiEvent.ResumptionHandle(handle, resumable))
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    if (raw.endsWith("s", true)) raw.dropLast(1).toDoubleOrNull()?.times(1000.0)?.toLong()
                    else raw.toLongOrNull()
                } ?: goAway["timeLeftMs"]?.jsonPrimitive?.longOrNull
                emitControl(GeminiEvent.GoAway(timeLeft?.coerceAtLeast(0L)))
            }

            root["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let { ids ->
                    cancelledToolCallIds.addAll(ids)
                    emitControl(GeminiEvent.ToolCallCancelled(ids))
                }

            val sc = root["serverContent"]?.jsonObject
            val interactionStatusElement = sc?.get("interactionStatus")
                ?: sc?.get("interaction_status")
                ?: root["interactionStatus"]
                ?: root["interaction_status"]

            interactionStatusElement
                ?.jsonPrimitive?.contentOrNull
                ?.uppercase()
                ?.takeIf { it == "IN_PROGRESS" || it == "IDLE" }
                ?.let { status ->
                    protocolPhase = if (status == "IN_PROGRESS") {
                        ProtocolPhase.AWAITING_INTERACTION_IDLE
                    } else {
                        ProtocolPhase.READY
                    }
                    emitControl(GeminiEvent.InteractionStatus(status))
                }

            root["toolCall"]?.jsonObject?.get("functionCalls")?.jsonArray
                ?.mapNotNull { fcEl ->
                    val fc = fcEl.jsonObject
                    val name = fc["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val id = fc["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val args = fc["args"]?.jsonObject ?: buildJsonObject {}
                    FunctionCall(name, id, args)
                }
                ?.takeIf { it.isNotEmpty() }
                ?.let { emitControl(GeminiEvent.ToolCall(it)) }

            if (sc != null) {
                val interrupted = sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true
                if (interrupted) {
                    purgeAudioQueue(-1L)
                    audioEngine.invalidateAndFlushPlayback("server interrupted")
                    serverGenerationOpen = false
                    suppressedServerGenerationId = -1L
                    emitControl(GeminiEvent.Interrupted)
                }

                extractTranscriptText(sc["interimInputTranscription"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        if (lastOutboundAudioSendMs > 0L) {
                            val rttSample = SystemClock.elapsedRealtime() - lastOutboundAudioSendMs
                            updateNetworkRtt(rttSample)
                        }
                        emitData(GeminiEvent.InputTranscript(it, interim = true))
                    }
                extractTranscriptText(sc["inputTranscription"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let { emitControl(GeminiEvent.InputTranscript(it, interim = false)) }
                extractTranscriptText(sc["outputTranscription"])
                    ?.takeIf { it.isNotBlank() && frameGenerationId != suppressedServerGenerationId }
                    ?.let { emitControl(GeminiEvent.OutputTranscript(it)) }

                if (!interrupted) {
                    sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEachIndexed { partIndex, partEl ->
                        val part = partEl.jsonObject
                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        if (!isThought) {
                            part["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let {
                                    emitControl(GeminiEvent.ModelText(it))
                                }
                        }
                        val inline = part["inlineData"]?.jsonObject
                        val mime = inline?.get("mimeType")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (mime.startsWith("audio/pcm")) {
                            val pcm = decodedPcmParts.getOrNull(partIndex) ?: return@forEachIndexed
                            val bytes = pcm.size.toLong()

                            totalServerAudioBytesReceived.addAndGet(bytes)
                            totalServerAudioFramesReceived.addAndGet(bytes / 2L)

                            if (frameGenerationId == suppressedServerGenerationId) return@forEachIndexed

                            val generation = audioEngine.currentPlaybackGeneration
                            val key = DataBudgetKey(mySessionId, myEpoch)
                            var accepted = false
                            synchronized(sessionStateLock) {
                                if (myEpoch == epoch && webSocket === sourceWebSocket) {
                                    val perSession = audioBudgetBySession.computeIfAbsent(key) { AtomicLong(0L) }
                                    val aggregate = perSession.get() + bytes
                                    if (aggregate <= MAX_AI_AUDIO_BACKLOG_BYTES) {
                                        perSession.addAndGet(bytes)
                                        val frameSeq = audioFrameSeqGen.incrementAndGet()
                                        val frameNanoTime = SystemClock.elapsedRealtimeNanos()
                                        val audioFrame = obtainAudioFrame(
                                            pcm = pcm,
                                            sessionId = mySessionId,
                                            epoch = myEpoch,
                                            generation = generation,
                                            frameId = frameId,
                                            timestampMs = SystemClock.elapsedRealtime(),
                                            sequenceNumber = frameSeq,
                                            timestampNs = frameNanoTime,
                                            ttlMs = AUDIO_FRAME_DEFAULT_TTL_MS
                                        )

                                        if (!audioFrame.isExpired()) {
                                            accepted = _audio.trySend(audioFrame).isSuccess
                                        }

                                        if (!accepted) {
                                            releaseAudio(audioFrame)
                                        }
                                    }
                                }
                            }

                            if (!accepted) {
                                transportAudioDroppedBytes.addAndGet(bytes)
                                transportAudioDroppedFrames.addAndGet(bytes / 2L)
                                val dropEvents = transportBacklogDropEvents.incrementAndGet()

                                val backlog = audioBudgetBySession[key]?.get() ?: 0L
                                logManager.w(
                                    "GeminiLive:AudioBacklog",
                                    "Входящий аудиочанк отклонён (backlog=${backlog / 1024} КБ, лимит=${MAX_AI_AUDIO_BACKLOG_BYTES / 1024} КБ, droppedBytes=${transportAudioDroppedBytes.get()}, events=$dropEvents)"
                                )
                            }
                        }
                    }
                }

                sc["groundingMetadata"]?.jsonObject?.let {
                    emitData(GeminiEvent.GroundingMetadata(GroundingMetadata(it)))
                }
                sc["urlContextMetadata"]?.jsonObject?.let {
                    emitData(GeminiEvent.UrlContextMetadata(UrlContextMetadata(it)))
                }

                sc["generationComplete"]?.jsonPrimitive?.booleanOrNull?.takeIf { it }
                    ?.let {
                        emitControl(GeminiEvent.GenerationComplete)
                        serverGenerationOpen = false
                        suppressedServerGenerationId = -1L
                    }

                sc["turnComplete"]?.jsonPrimitive?.booleanOrNull?.takeIf { it }
                    ?.let {
                        serverGenerationOpen = false
                        suppressedServerGenerationId = -1L
                        emitControl(GeminiEvent.TurnComplete)
                    }
            }
        }
    }

    private fun describeServerFrame(text: String, size: Int): String {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return "Rx $size Б: не JSON"
        if (root.isEmpty()) return "Rx $size Б: пустой кадр"
        val parts = mutableListOf<String>()
        root.keys.filter { it != "serverContent" }.forEach { parts += it }
        (root["serverContent"] as? JsonObject)?.forEach { (k, v) ->
            parts += when (k) {
                "modelTurn" -> "modelTurn[${((v as? JsonObject)?.get("parts") as? JsonArray)?.size ?: 0}]"
                "inputTranscription", "interimInputTranscription", "outputTranscription" ->
                    "$k='${extractTranscriptText(v).orEmpty().take(80)}'"
                else -> k
            }
        }
        return "Rx $size Б: ${parts.joinToString()}"
    }

    private fun isQuotaClose(code: Int, reason: String): Boolean {
        if (code != 1011 && code != 1013) return false
        val r = reason.lowercase()
        return "quota" in r || "resource_exhausted" in r || "billing" in r || "rate limit" in r
    }

    private fun extractTranscriptText(element: JsonElement?): String? {
        return when (element) {
            is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> element.contentOrNull
            else -> null
        }
    }

    private fun closeInternal() {
        val ws: WebSocket?
        val newEpoch: Long

        synchronized(sessionStateLock) {
            ws = webSocket
            newEpoch = epochGen.incrementAndGet()
            epoch = newEpoch

            protocolPhase = ProtocolPhase.CLOSING
            webSocket = null
            isReady = false
            activeConfig = null
            cancelledToolCallIds.clear()
            dataEventsDroppedCounter.set(0L)
            audioFrameSeqGen.set(0L)

            purgeAudioQueue(-1L)

            while (true) {
                val queued = _events.tryReceive().getOrNull() ?: break
                if (queued.isDataPlane) {
                    queued.budgetKey?.let { key ->
                        pendingDataBySession[key]?.let { counter ->
                            if (counter.decrementAndGet() <= 0L) {
                                pendingDataBySession.remove(key, counter)
                            }
                        }
                    }
                }
            }
        }

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
            audioBatchFirstWriteMs = 0L
        }

        stopOutboundWorkers()

        runCatching { ws?.close(1000, "close") }
        runCatching { ws?.cancel() }

        synchronized(sessionStateLock) {
            if (webSocket == null && epoch == newEpoch) {
                protocolPhase = ProtocolPhase.IDLE
            }
        }

        logManager.w("WebSocket", "Старая сессия закрыта (новая эпоха=$newEpoch)")
    }

    suspend fun disconnect() = wsMutex.withLock {
        logManager.w("WebSocket", "Отключение сессии (эпоха будет сменена атомарно)")
        closeInternal()
    }
}