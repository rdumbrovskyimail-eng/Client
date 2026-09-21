package com.client.app.api

import android.util.Base64
import kotlinx.coroutines.*
import com.client.app.audio.NativeAudioEngine
import com.client.app.logging.AppLogManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiProtobufLiveClient @Inject constructor(
    private val audioEngine: NativeAudioEngine,
    private val logManager: AppLogManager
) {
    companion object {
        const val WS_HOST =
            "generativelanguage.googleapis.com"

        const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Application-level watermark.
        // This is intentionally below OkHttp's own terminal queue limit.
        private const val MAX_QUEUE_BYTES =
            256L * 1024L

        // 40 ms @ 16 kHz PCM16 mono:
        // 16000 * 0.040 * 2 = 1280 bytes.
        private const val AUDIO_BATCH_THRESHOLD_BYTES =
            1280

        private const val WS_QUEUE_POLL_MS = 5L

        // Bounded command queue means network backpressure eventually
        // propagates to the capture producer instead of dropping PCM.
        private const val AUDIO_COMMAND_CHANNEL_CAPACITY = 32

        private const val MAX_INITIAL_HISTORY_TURNS = 20

        // Retained from prior P0 work:
        // protect against unbounded incoming model audio backlog.
        private const val MAX_AI_AUDIO_BACKLOG_BYTES =
            256L * 1024L

        private const val MAX_DATA_EVENTS_IN_FLIGHT = 256

        private const val MAX_EVENT_QUEUE_CAPACITY = 512
        private const val MAX_AUDIO_FRAME_QUEUE_CAPACITY = 128
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    // AUD-067:
    //
    // One ordered producer pipeline for realtime audio/activity commands.
    //
    // Crucially:
    //   - PCM uses suspend/send(), not trySend()
    //   - AudioStreamEnd uses the same queue
    //   - ActivityStart/End use the same queue
    //
    // Therefore stream markers cannot overtake PCM.
    private sealed interface AudioOutboundCommand {

        data class Pcm(
            val payload: ByteArray
        ) : AudioOutboundCommand

        data object ActivityStart :
            AudioOutboundCommand

        data object ActivityEnd :
            AudioOutboundCommand

        data object AudioStreamEnd :
            AudioOutboundCommand

        /**
         * Establishes a transport ordering barrier. The writer completes the
         * deferred only after every earlier realtime command has been
         * processed. Direct non-audio sends use this barrier so they cannot
         * jump ahead of queued PCM/activity markers.
         */
        data class Barrier(
            val completion: CompletableDeferred<Unit>
        ) : AudioOutboundCommand
    }

    private val loggingEventListener =
        object : EventListener() {

            override fun dnsStart(
                call: Call,
                domainName: String
            ) {
                logManager.net(
                    "OkHttp:DNS",
                    "Старт DNS-резолва: $domainName"
                )
            }

            override fun dnsEnd(
                call: Call,
                domainName: String,
                inetAddressList: List<InetAddress>
            ) {
                logManager.net(
                    "OkHttp:DNS",
                    "DNS успешен: $domainName -> $inetAddressList"
                )
            }

            override fun connectStart(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy
            ) {
                logManager.net(
                    "OkHttp:TCP",
                    "Подключение к $inetSocketAddress..."
                )
            }

            override fun connectEnd(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?
            ) {
                logManager.net(
                    "OkHttp:TCP",
                    "TCP соединение установлено ($protocol)"
                )
            }

            override fun secureConnectStart(
                call: Call
            ) {
                logManager.net(
                    "OkHttp:TLS",
                    "Старт TLS 1.3 хендшейка..."
                )
            }

            override fun secureConnectEnd(
                call: Call,
                handshake: Handshake?
            ) {
                val tls = handshake?.tlsVersion
                val cipher = handshake?.cipherSuite

                logManager.net(
                    "OkHttp:TLS",
                    "TLS успешен: $tls [$cipher]"
                )
            }

            override fun connectFailed(
                call: Call,
                inetSocketAddress: InetSocketAddress,
                proxy: Proxy,
                protocol: Protocol?,
                ioe: IOException
            ) {
                logManager.e(
                    "OkHttp:Connect",
                    "Сбой подключения к $inetSocketAddress: ${ioe.message}",
                    ioe
                )
            }
        }

    private val httpClient =
        OkHttpClient.Builder()
            .eventListener(loggingEventListener)
            .connectTimeout(
                10,
                TimeUnit.SECONDS
            )
            .readTimeout(
                0,
                TimeUnit.MILLISECONDS
            )
            .writeTimeout(
                0,
                TimeUnit.MILLISECONDS
            )
            .pingInterval(
                12,
                TimeUnit.SECONDS
            )
            .retryOnConnectionFailure(false)
            .build()

    private val wsMutex =
        Mutex()

    @Volatile
    private var webSocket: WebSocket? = null

    private val epochGen =
        AtomicLong(0)

    @Volatile
    var epoch: Long = 0L
        private set

    // Serializes transport identity changes with parser-side destructive
    // state transitions. A WebSocket callback can arrive concurrently with
    // reconnect/close, so epoch + socket identity must be checked atomically
    // for side effects that touch global transport state.
    private val sessionStateLock = Any()

    // AUD-005.3:
    // The playback-generation authority lives outside the transport so there
    // is exactly one generation domain shared by all invalidation sources.
    val audioGeneration: Long
        get() = audioEngine.currentPlaybackGeneration

    private val audioBudgetBySession =
        ConcurrentHashMap<DataBudgetKey, AtomicLong>()

    // AUD-006:
    // One FIFO event ingress preserves the actual server delivery order.
    // Control events are never rejected. High-frequency data events are
    // bounded by count and are dropped newest when the data budget is full.
    // This is deliberately implemented above the Channel rather than with
    // an eviction policy so an evicted event can never leak ownership
    // or reorder lifecycle events.
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

    private var protocolPhase = ProtocolPhase.IDLE

    private val cancelledToolCallIds = mutableSetOf<String>()

    private val pendingDataBySession =
        ConcurrentHashMap<DataBudgetKey, AtomicLong>()

    private val generationIdGen = AtomicLong(0L)
    private var activeServerGenerationId = 0L
    private var serverGenerationOpen = false

    private val _events =
        Channel<QueuedEvent>(
            MAX_EVENT_QUEUE_CAPACITY
        )

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
                // Old callbacks can still be physically present in the FIFO
                // after a reconnect. Filter by epoch at consumption so stale
                // events are not normally delivered to SessionManager. The
                // explicit envelope keeps the source epoch available for a
                // second validation at the consumer boundary.
                queued.epoch == epoch &&
                    queued.sessionId == sessionId
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

    // AI output audio is independently bounded by byte accounting.
    private val _audio =
        Channel<AudioFrame>(MAX_AUDIO_FRAME_QUEUE_CAPACITY)

    val audio: ReceiveChannel<AudioFrame> =
        _audio

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var activeConfig: LiveConfig? = null

    private val audioBatchBuffer =
        ByteArrayOutputStream(
            AUDIO_BATCH_THRESHOLD_BYTES * 2
        )

    private val batchLock =
        Any()

    // batchLock protects only the mutable PCM batch state. It is never held
    // across a suspending Channel.send() or a network operation.
    private var isAudioStreamEnded =
        true

    @Volatile
    private var audioWriterScope:
        CoroutineScope? = null

    @Volatile
    private var audioWriterChannel:
        Channel<AudioOutboundCommand>? = null

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
                protocolPhase = ProtocolPhase.CLOSING
                isReady = false
                val ws = webSocket
                logManager.e(
                    "GeminiLive:EventQueue",
                    "Control event queue exhausted; closing current session"
                )
                runCatching {
                    ws?.close(1013, "control event queue exhausted")
                }
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

    fun invalidateAudio(): Long =
        audioEngine.invalidateAndFlushPlayback("transport invalidate")

    fun releaseAudio(frame: AudioFrame) {
        val bytes = frame.pcm.size
        if (bytes <= 0) return
        val key = DataBudgetKey(frame.sessionId, frame.epoch)
        val counter = audioBudgetBySession[key] ?: return
        if (counter.addAndGet(-bytes.toLong()) <= 0L) {
            counter.set(0L)
            audioBudgetBySession.remove(key, counter)
        }
    }

    suspend fun connect(
        cfg: LiveConfig,
        beforeOpen: (suspend () -> Unit)? = null
    ) = wsMutex.withLock {

        // Reject an impossible compression union before touching the current
        // live session. A bad new configuration must not tear down a healthy
        // existing connection.
        validateCompressionConfig(cfg)

        // closeInternal() advances the session epoch before closing the old
        // socket. Old callbacks therefore become stale before the new socket
        // is exposed to the rest of the app. Playback generation is managed
        // by NativeAudioEngine as the single authoritative owner.
        closeInternal()

        val newSessionId = sessionIdGen.incrementAndGet()
        synchronized(sessionStateLock) {
            sessionId = newSessionId
            frameIdGen.set(0L)
            protocolPhase = ProtocolPhase.CONNECTING
            cancelledToolCallIds.clear()
            isReady = false
            activeConfig = cfg
        }

        // beforeOpen observes the new logical session identity and flushes the
        // physical audio state before the new socket is allowed to receive audio.
        beforeOpen?.invoke()

        synchronized(sessionStateLock) {
            isReady = false
            activeConfig = cfg
        }

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        val myEpoch = epoch
        val mySessionId = synchronized(sessionStateLock) { sessionId }

        val rawKey =
            cfg.apiKey.trim()
        val encodedKey =
            URLEncoder.encode(
                rawKey,
                "UTF-8"
            )

        val url =
            "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        logManager.net(
            "WebSocket",
            "Инициализация Bidi сессии Gemini 3.8 Live (epoch=$myEpoch, key=[REDACTED])"
        )

        val req =
            Request.Builder()
                .url(url)
                .header(
                    "X-Accel-Buffering",
                    "no"
                )
                .header(
                    "Cache-Control",
                    "no-cache"
                )
                .build()

        val ws =
            httpClient.newWebSocket(
                req,
                object : WebSocketListener() {

                    override fun onOpen(
                        ws: WebSocket,
                        response: Response
                    ) {

                        synchronized(sessionStateLock) {
                            if (myEpoch != epoch) {
                                ws.close(
                                    1000,
                                    "stale"
                                )
                                return
                            }

                            // Publish the socket before sending setup so a very
                            // fast setupComplete response cannot race with
                            // connect()'s return path and be mistaken for stale.
                            webSocket = ws
                            protocolPhase = ProtocolPhase.CONNECTING
                        }

                        startAudioWriter(
                            ws,
                            myEpoch
                        )

                        logManager.net(
                            "WebSocket:Open",
                            "Соединение открыто! HTTP ${response.code} ${response.message}"
                        )

                        emitControlEvent(
                            GeminiEvent.Connected,
                            myEpoch
                        )

                        val setupMsg =
                            buildSetupMessage(cfg)

                        logManager.net(
                            "WebSocket:Tx",
                            "Отправка setup сообщения (payload redacted)"
                        )

                        val setupAccepted =
                            synchronized(sessionStateLock) {
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

                    override fun onMessage(
                        ws: WebSocket,
                        text: String
                    ) {

                        if (synchronized(sessionStateLock) {
                                myEpoch == epoch &&
                                    sessionId == mySessionId &&
                                    webSocket === ws
                            }) {

                            val logSummary =
                                if (text.contains("\"audio/pcm")) {
                                    "[Аудиофрейм получен; размер=${text.length}]"
                                } else {
                                    "[WebSocket text frame; размер=${text.length}]"
                                }

                            logManager.net(
                                "WebSocket:Rx",
                                logSummary
                            )

                            parseServerJsonMessage(
                                text,
                                myEpoch,
                                ws
                            )
                        }
                    }

                    override fun onMessage(
                        ws: WebSocket,
                        bytes: ByteString
                    ) {

                        if (myEpoch == epoch) {

                            logManager.net(
                                "WebSocket:RxBinary",
                                "Получено ${bytes.size} байт"
                            )

                            parseServerJsonMessage(
                                bytes.utf8(),
                                myEpoch,
                                ws
                            )
                        }
                    }

                    override fun onClosing(
                        ws: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        logManager.w(
                            "WebSocket:Closing",
                            "Сервер инициировал закрытие: $code / '$reason'"
                        )

                        ws.close(
                            1000,
                            null
                        )
                    }

                    override fun onClosed(
                        ws: WebSocket,
                        code: Int,
                        reason: String
                    ) {

                        logManager.w(
                            "WebSocket:Closed",
                            "Соединение закрыто (code=$code, reason='$reason', epoch=$myEpoch)"
                        )

                        if (myEpoch != epoch) {
                            return
                        }

                        synchronized(sessionStateLock) {
                            if (myEpoch != epoch || webSocket !== ws) {
                                return
                            }
                            isReady = false
                        }
                        stopAudioWriter(
                            expectedWs = ws,
                            expectedEpoch = myEpoch
                        )

                        emitControlEvent(
                            GeminiEvent.Disconnected(
                                code,
                                reason,
                                mySessionId,
                                myEpoch
                            ),
                            myEpoch
                        )
                    }

                    override fun onFailure(
                        ws: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {

                        val httpCode =
                            response?.code

                        val errBody =
                            runCatching {
                                response?.body?.string()
                            }.getOrNull()

                        logManager.e(
                            "WebSocket:Failure",
                            "Сбой сокета (HTTP $httpCode): ${t.localizedMessage}. Ответ: $errBody",
                            t
                        )

                        if (myEpoch != epoch) {
                            return
                        }

                        synchronized(sessionStateLock) {
                            if (myEpoch != epoch || webSocket !== ws) {
                                return
                            }
                            isReady = false
                        }
                        stopAudioWriter(
                            expectedWs = ws,
                            expectedEpoch = myEpoch
                        )

                        val fatal =
                            httpCode == 401 ||
                            httpCode == 403

                        emitControlEvent(
                            GeminiEvent.Error(
                                "Сетевой сбой ($httpCode): ${t.localizedMessage}",
                                fatal
                            ),
                            myEpoch
                        )

                        emitControlEvent(
                            GeminiEvent.Disconnected(
                                httpCode ?: 1006,
                                t.message.orEmpty(),
                                mySessionId,
                                myEpoch
                            ),
                            myEpoch
                        )
                    }
                }
            )

        // onOpen() publishes the socket and starts its writer before setup is
        // sent. Keeping this return path free of writer replacement avoids a
        // race where a fast setupComplete callback could create the writer
        // and this path could immediately replace it.
    }

    private fun startAudioWriter(
        ws: WebSocket,
        writerEpoch: Long
    ) {

        stopAudioWriter()

        val scope =
            CoroutineScope(
                SupervisorJob() +
                    Dispatchers.IO
            )

        val channel =
            Channel<AudioOutboundCommand>(
                capacity =
                    AUDIO_COMMAND_CHANNEL_CAPACITY
            )

        synchronized(sessionStateLock) {
            if (writerEpoch != epoch || webSocket !== ws) {
                channel.close()
                scope.cancel()
                return
            }
            audioWriterScope = scope
            audioWriterChannel = channel
        }

        scope.launch {
            try {
                while (true) {
                    // Keep the bounded pre-ready queue fully bounded: do not
                    // dequeue the first command until setupComplete has been
                    // observed. With a 32-command channel and 40 ms PCM batches
                    // this is a deterministic ~1.28 s pre-ready ceiling.
                    if (!awaitWriterReady(ws, writerEpoch)) {
                        break
                    }

                    if (writerEpoch != epoch || webSocket !== ws) {
                        break
                    }

                    val command = channel.receiveCatching().getOrNull()
                        ?: break

                    if (command is AudioOutboundCommand.Barrier) {
                        command.completion.complete(Unit)
                        continue
                    }

                    if (!awaitWebSocketQueueCapacity(ws, writerEpoch)) {
                        break
                    }

                    // The ready/epoch conditions may have changed while we
                    // waited for OkHttp's queue to drain.
                    if (
                        writerEpoch != epoch ||
                        !isReady ||
                        webSocket !== ws
                    ) {
                        break
                    }

                    val jsonMessage =
                        when (command) {
                            is AudioOutboundCommand.Pcm -> {
                                val base64Data =
                                    Base64.encodeToString(
                                        command.payload,
                                        Base64.NO_WRAP
                                    )

                                buildJsonObject {
                                    putJsonObject(
                                        "realtimeInput"
                                    ) {
                                        putJsonObject(
                                            "audio"
                                        ) {
                                            put(
                                                "mimeType",
                                                "audio/pcm;rate=16000"
                                            )
                                            put(
                                                "data",
                                                base64Data
                                            )
                                        }
                                    }
                                }.toString()
                            }

                            AudioOutboundCommand.ActivityStart ->
                                buildJsonObject {
                                    putJsonObject(
                                        "realtimeInput"
                                    ) {
                                        putJsonObject(
                                            "activityStart"
                                        ) {}
                                    }
                                }.toString()

                            AudioOutboundCommand.ActivityEnd ->
                                buildJsonObject {
                                    putJsonObject(
                                        "realtimeInput"
                                    ) {
                                        putJsonObject(
                                            "activityEnd"
                                        ) {}
                                    }
                                }.toString()

                            AudioOutboundCommand.AudioStreamEnd ->
                                buildJsonObject {
                                    putJsonObject(
                                        "realtimeInput"
                                    ) {
                                        put(
                                            "audioStreamEnd",
                                            true
                                        )
                                    }
                                }.toString()
                        }

                    val sendAccepted =
                        synchronized(sessionStateLock) {
                            if (
                                writerEpoch != epoch ||
                                !isReady ||
                                webSocket !== ws
                            ) {
                                false
                            } else {
                                synchronized(outboundSendLock) {
                                    ws.send(jsonMessage)
                                }
                            }
                        }

                    if (!sendAccepted) {
                        logManager.w(
                            "WebSocket:AudioWriter",
                            "OkHttp отверг outbound audio/control command"
                        )

                        if (writerEpoch == epoch &&
                            webSocket === ws
                        ) {
                            isReady = false
                        }
                        break
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logManager.e(
                    "WebSocket:AudioWriter",
                    "Ошибка realtime audio writer",
                    t
                )
                val current = synchronized(sessionStateLock) {
                    writerEpoch == epoch && webSocket === ws
                }
                if (current) {
                    synchronized(sessionStateLock) {
                        if (writerEpoch == epoch && webSocket === ws) {
                            isReady = false
                            protocolPhase = ProtocolPhase.CLOSING
                        }
                    }
                    val failedSessionId = synchronized(sessionStateLock) { sessionId }
                    emitControlEvent(
                        GeminiEvent.Disconnected(
                            1011,
                            "realtime audio writer failed",
                            failedSessionId,
                            writerEpoch
                        ),
                        writerEpoch
                    )
                    runCatching { ws.close(1011, "realtime audio writer failed") }
                }
            } finally {
                channel.close()

                // Never clear a newer writer that may already have replaced
                // this one after reconnect.
                synchronized(sessionStateLock) {
                    if (audioWriterChannel === channel) {
                        audioWriterChannel = null
                    }
                    if (audioWriterScope === scope) {
                        audioWriterScope = null
                    }
                }
            }
        }
    }

    private suspend fun awaitWriterReady(
        ws: WebSocket,
        writerEpoch: Long
    ): Boolean {
        while (!isReady) {
            if (
                writerEpoch != epoch ||
                webSocket !== ws
            ) {
                return false
            }
            delay(WS_QUEUE_POLL_MS)
        }
        return (
            writerEpoch == epoch &&
            webSocket === ws
        )
    }

    private suspend fun awaitWebSocketQueueCapacity(
        ws: WebSocket,
        writerEpoch: Long
    ): Boolean {
        while (ws.queueSize() > MAX_QUEUE_BYTES) {
            if (
                writerEpoch != epoch ||
                webSocket !== ws
            ) {
                return false
            }
            delay(WS_QUEUE_POLL_MS)
        }
        return (
            writerEpoch == epoch &&
            isReady &&
            webSocket === ws
        )
    }

    private val outboundSendLock = Any()

    // Serializes direct-send barriers + direct network sends against one
    // another. Audio commands remain on the writer channel, but every direct
    // sender first waits for a channel barrier, giving the whole transport a
    // single logical outbound sequence.
    private val outboundDirectMutex = Mutex()

    private fun stopAudioWriter(
        expectedWs: WebSocket? = null,
        expectedEpoch: Long? = null
    ) {
        synchronized(sessionStateLock) {
            if (expectedWs != null) {
                if (expectedEpoch == null ||
                    expectedEpoch != epoch ||
                    webSocket !== expectedWs
                ) {
                    return
                }
            }

            val channel = audioWriterChannel
            val scope = audioWriterScope
            audioWriterChannel = null
            audioWriterScope = null

            channel?.close()
            scope?.cancel()
        }
    }

    // AUD-067:
    // Suspend while the application-level realtime command queue is full.
    // A lifecycle race is treated as a normal drop of a stale command, not as
    // an application exception. The capture producer is never handed a
    // synthetic "writer not active" failure during reconnect/close.
    suspend fun sendAudioPcm(
        pcm: ByteArray
    ) {
        if (pcm.isEmpty()) return

        val target = synchronized(batchLock) {
            val writerEpoch = synchronized(sessionStateLock) { epoch }
            val ws = synchronized(sessionStateLock) { webSocket } ?: return@synchronized null
            val channel = synchronized(sessionStateLock) { audioWriterChannel } ?: return@synchronized null

            isAudioStreamEnded = false
            audioBatchBuffer.write(pcm)

            val payload =
                if (audioBatchBuffer.size() >= AUDIO_BATCH_THRESHOLD_BYTES) {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                } else {
                    null
                }

            if (payload == null ||
                !isWriterCurrent(writerEpoch, ws, channel)
            ) {
                null
            } else {
                channel to payload
            }
        } ?: return

        try {
            target.first.send(AudioOutboundCommand.Pcm(target.second))
        } catch (_: ClosedSendChannelException) {
            // Normal reconnect/close race.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    // AUD-067:
    // Tail PCM is queued before AudioStreamEnd in the same ordered channel.
    suspend fun sendAudioStreamEnd() {
        val pending = synchronized(batchLock) {
            if (isAudioStreamEnded) return@synchronized null

            val writerEpoch = synchronized(sessionStateLock) { epoch }
            val ws = synchronized(sessionStateLock) { webSocket } ?: return@synchronized null
            val channel = synchronized(sessionStateLock) { audioWriterChannel } ?: return@synchronized null

            val tailPayload =
                if (audioBatchBuffer.size() == 0) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }

            isAudioStreamEnded = true

            if (!isWriterCurrent(writerEpoch, ws, channel)) {
                null
            } else {
                val commands =
                    if (tailPayload != null) {
                        listOf(
                            AudioOutboundCommand.Pcm(tailPayload),
                            AudioOutboundCommand.AudioStreamEnd
                        )
                    } else {
                        listOf(AudioOutboundCommand.AudioStreamEnd)
                    }
                channel to commands
            }
        } ?: return

        try {
            pending.second.forEach { pending.first.send(it) }
        } catch (_: ClosedSendChannelException) {
            // Normal lifecycle race.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    private fun isWriterCurrent(
        writerEpoch: Long,
        ws: WebSocket,
        channel: SendChannel<AudioOutboundCommand>
    ): Boolean =
        synchronized(sessionStateLock) {
            writerEpoch == epoch &&
                webSocket === ws &&
                audioWriterChannel === channel
        }

    private suspend fun awaitOutboundBarrier(
        expectedEpoch: Long,
        expectedWs: WebSocket
    ): Boolean {
        val channel = synchronized(sessionStateLock) {
            if (expectedEpoch != epoch || webSocket !== expectedWs || !isReady) {
                return false
            }
            audioWriterChannel
        } ?: return false

        val barrier = CompletableDeferred<Unit>()
        return try {
            channel.send(AudioOutboundCommand.Barrier(barrier))
            withTimeoutOrNull(2500L) {
                barrier.await()
                true
            } == true && synchronized(sessionStateLock) {
                expectedEpoch == epoch && webSocket === expectedWs && isReady
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: ClosedSendChannelException) {
            false
        }
    }

    suspend fun sendRealtimeText(
        text: String
    ) {

        val sendEpoch = epoch
        val ws =
            webSocket ?: return

        if (
            !isReady ||
            text.isBlank()
        ) {
            return
        }

        val cleanText =
            text.trim()

        val jsonMessage =
            buildJsonObject {

                putJsonObject(
                    "realtimeInput"
                ) {

                    put(
                        "text",
                        cleanText
                    )
                }

            }.toString()

        logManager.net(
            "WebSocket:TxText",
            "Отправка realtimeInput.text (payload redacted; length=${cleanText.length})"
        )

        outboundDirectMutex.withLock {
            if (awaitOutboundBarrier(sendEpoch, ws)) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    suspend fun sendRealtimeImage(
        jpegBytes: ByteArray
    ) {
        val sendEpoch = epoch
        val ws =
            webSocket ?: return

        if (
            !isReady ||
            jpegBytes.isEmpty()
        ) {
            return
        }

        // Video is an optional realtime producer. Unlike PCM/control, it may
        // be safely shed when the OkHttp outbound queue is congested. This keeps
        // transient image bursts from competing with the voice transport.
        if (ws.queueSize() > MAX_QUEUE_BYTES) {
            logManager.w(
                "WebSocket:TxImage",
                "Очередь WebSocket перегружена; realtime image пропущен"
            )
            return
        }

        val base64Data =
            Base64.encodeToString(
                jpegBytes,
                Base64.NO_WRAP
            )

        val jsonMessage =
            buildJsonObject {

                putJsonObject(
                    "realtimeInput"
                ) {

                    putJsonObject(
                        "video"
                    ) {

                        put(
                            "mimeType",
                            "image/jpeg"
                        )

                        put(
                            "data",
                            base64Data
                        )
                    }
                }

            }.toString()

        logManager.net(
            "WebSocket:TxImage",
            "Отправка изображения (${jpegBytes.size} байт)"
        )

        outboundDirectMutex.withLock {
            if (awaitOutboundBarrier(sendEpoch, ws)) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    // Manual VAD activity markers are serialized through the same
    // realtime audio command stream.
    suspend fun sendActivityStart() {
        val channel = synchronized(batchLock) {
            val writerEpoch = synchronized(sessionStateLock) { epoch }
            val ws = synchronized(sessionStateLock) { webSocket } ?: return@synchronized null
            val target = synchronized(sessionStateLock) { audioWriterChannel } ?: return@synchronized null

            isAudioStreamEnded = false
            audioBatchBuffer.reset()

            if (isWriterCurrent(writerEpoch, ws, target)) {
                target
            } else {
                null
            }
        } ?: return

        try {
            channel.send(AudioOutboundCommand.ActivityStart)
        } catch (_: ClosedSendChannelException) {
            // Normal lifecycle race.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun sendActivityEnd() {
        val pending = synchronized(batchLock) {
            val writerEpoch = synchronized(sessionStateLock) { epoch }
            val ws = synchronized(sessionStateLock) { webSocket } ?: return@synchronized null
            val channel = synchronized(sessionStateLock) { audioWriterChannel } ?: return@synchronized null

            val tailPayload =
                if (audioBatchBuffer.size() == 0) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }

            isAudioStreamEnded = true

            if (!isWriterCurrent(writerEpoch, ws, channel)) {
                null
            } else {
                val commands =
                    if (tailPayload != null) {
                        listOf(
                            AudioOutboundCommand.Pcm(tailPayload),
                            AudioOutboundCommand.ActivityEnd
                        )
                    } else {
                        listOf(AudioOutboundCommand.ActivityEnd)
                    }
                channel to commands
            }
        } ?: return

        try {
            pending.second.forEach { pending.first.send(it) }
        } catch (_: ClosedSendChannelException) {
            // Normal lifecycle race.
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
    }

    suspend fun sendClientContent(
        turns: List<ClientTurn>,
        turnComplete: Boolean = true
    ) {

        val sendEpoch = epoch
        val ws =
            webSocket ?: return

        if (
            !isReady ||
            turns.isEmpty()
        ) {
            return
        }

        sendClientContentInternal(
            turns = turns,
            turnComplete = turnComplete,
            targetWebSocket = ws,
            expectedEpoch = sendEpoch
        )
    }

    private fun sendClientContentInternal(
        turns: List<ClientTurn>,
        turnComplete: Boolean,
        targetWebSocket: WebSocket? = null,
        expectedEpoch: Long = epoch
    ): Boolean {

        val ws =
            targetWebSocket ?: webSocket ?: return false

        val jsonMessage =
            buildJsonObject {

                putJsonObject(
                    "clientContent"
                ) {

                    putJsonArray(
                        "turns"
                    ) {

                        turns.forEach { turn ->

                            addJsonObject {

                                put(
                                    "role",
                                    turn.role.value
                                )

                                putJsonArray(
                                    "parts"
                                ) {

                                    addJsonObject {
                                        put(
                                            "text",
                                            turn.text
                                        )
                                    }
                                }
                            }
                        }
                    }

                    put(
                        "turnComplete",
                        turnComplete
                    )
                }

            }.toString()

        logManager.net(
            "WebSocket:TxClientContent",
            "Отправка clientContent (${turns.size} ходов, turnComplete=$turnComplete) [payload redacted]"
        )

        val accepted: Boolean =
            outboundDirectMutex.withLock {
                val allowed = synchronized(sessionStateLock) {
                    expectedEpoch == epoch &&
                        webSocket === ws &&
                        (targetWebSocket != null || isReady)
                }

                if (!allowed) {
                    false
                } else if (targetWebSocket == null && !awaitOutboundBarrier(expectedEpoch, ws)) {
                    false
                } else {
                    synchronized(outboundSendLock) {
                        ws.send(jsonMessage)
                    }
                }
            }

        return accepted
    }

    fun sendToolResponses(
        responses: List<ToolResponse>
    ): Boolean {
        if (responses.isEmpty()) return false

        var debugLog = ""
        var jsonMessage = ""
        var accepted = false

        synchronized(sessionStateLock) {
            val cfg = activeConfig ?: return@synchronized
            val caps = LiveModelCapabilitiesRegistry.forModel(cfg.model)
            val ws = webSocket ?: return@synchronized
            val sendEpoch = epoch

            if (!isReady) return@synchronized

            val activeResponses =
                responses.filter { response ->
                    val id = response.id
                    !id.isNullOrBlank() &&
                        !cancelledToolCallIds.contains(id)
                }

            if (activeResponses.isEmpty()) {
                logManager.w(
                    "GeminiLive:ToolResp",
                    "Все FunctionResponse относятся к отменённым tool calls; ответ не отправляется"
                )
                return@synchronized
            }

            jsonMessage =
                buildJsonObject {
                    putJsonObject("toolResponse") {
                        putJsonArray("functionResponses") {
                            activeResponses.forEach { resp ->
                                addJsonObject {
                                    put("id", resp.id!!.trim())
                                    put("name", resp.name)
                                    put("response", resp.response)

                                    if (
                                        caps.supportsFunctionScheduling &&
                                        resp.scheduling != null
                                    ) {
                                        put(
                                            "scheduling",
                                            resp.scheduling.name
                                        )
                                    }

                                    if (resp.willContinue) {
                                        put("willContinue", true)
                                    }

                                    if (resp.parts.isNotEmpty()) {
                                        putJsonArray("parts") {
                                            resp.parts.forEach { part ->
                                                addJsonObject {
                                                    putJsonObject("inlineData") {
                                                        put(
                                                            "mimeType",
                                                            part.mimeType
                                                        )
                                                        put(
                                                            "data",
                                                            part.base64Data
                                                        )
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

            debugLog =
                activeResponses.joinToString {
                    "${it.name}(id=${it.id}, sched=${it.scheduling?.name ?: "NONE"})"
                }

            // The cancellation-membership check and the send happen under the
            // same session lock. A ToolCallCancellation event therefore cannot
            // land between "still active" and ws.send().
            accepted =
                synchronized(outboundSendLock) {
                    sendEpoch == epoch &&
                        ws === webSocket &&
                        ws.send(jsonMessage)
                }
        }

        logManager.net(
            "WebSocket:ToolResp",
            "Ответы функций accepted=$accepted: [$debugLog]"
        )

        if (!accepted) {
            logManager.w(
                "GeminiLive:ToolResp",
                "WebSocket отклонил FunctionResponse"
            )
        }

        return accepted
    }

    private fun validateCompressionConfig(
        cfg: LiveConfig
    ) {
        LiveModelCapabilitiesRegistry.forModel(cfg.model)

        val trigger = cfg.compression.triggerTokens
        val target = cfg.compression.targetTokens

        require(trigger >= 0) {
            "contextWindowCompression.triggerTokens must be >= 0"
        }
        require(target >= 0) {
            "contextWindowCompression.slidingWindow.targetTokens must be >= 0"
        }
        // The wire schema is a oneof. Legacy/local settings may contain both
        // values; that should be normalized rather than crash a healthy session.
        // The build path gives slidingWindow.targetTokens precedence because it
        // is the more specific retention target.

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
                            if (key != "functionDeclarations") add(key, value)
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
                                            if (!(
                                                key == "behavior" &&
                                                !capabilities.supportsAsyncFunctionCalling
                                            )) {
                                                add(key, value)
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

    private fun buildSetupMessage(
        cfg: LiveConfig
    ): String {
        val capabilities = LiveModelCapabilitiesRegistry.forModel(cfg.model)

        val rawModelName =
            cfg.model
                .trim()
                .removePrefix(
                    "publishers/google/models/"
                )
                .removePrefix(
                    "models/"
                )

        val cleanModel =
            "models/$rawModelName"

        val setupObj =
            buildJsonObject {

                putJsonObject(
                    "setup"
                ) {

                    put(
                        "model",
                        cleanModel
                    )

                    putJsonObject(
                        "generationConfig"
                    ) {

                        if (capabilities.supportsThinkingConfig &&
                            !cfg.thinkingLevel.isNullOrBlank()
                        ) {
                            putJsonObject("thinkingConfig") {
                                put("thinkingLevel", cfg.thinkingLevel.lowercase())
                            }
                        }

                        putJsonArray(
                            "responseModalities"
                        ) {

                            add("AUDIO")
                        }

                        put(
                            "temperature",
                            cfg.temperature
                        )

                        put(
                            "mediaResolution",
                            cfg.mediaResolution
                        )

                        putJsonObject(
                            "speechConfig"
                        ) {

                            putJsonObject(
                                "voiceConfig"
                            ) {

                                putJsonObject(
                                    "prebuiltVoiceConfig"
                                ) {

                                    put(
                                        "voiceName",
                                        cfg.voiceName
                                    )
                                }
                            }

                            if (
                                !cfg.speechLanguage
                                    .isNullOrBlank()
                            ) {

                                put(
                                    "languageCode",
                                    cfg.speechLanguage
                                )
                            }
                        }
                    }

                    if (
                        cfg.inputTranscription.enabled
                    ) {

                        putJsonObject(
                            "inputAudioTranscription"
                        ) {

                            if (
                                cfg.inputTranscription
                                    .languageCodes
                                    .isNotEmpty()
                            ) {

                                putJsonArray(
                                    "languageCodes"
                                ) {

                                    cfg.inputTranscription
                                        .languageCodes
                                        .forEach {
                                            add(it)
                                        }
                                }
                            }

                            if (
                                cfg.inputTranscription
                                    .customVocabulary
                                    .isNotEmpty()
                            ) {

                                putJsonArray(
                                    "customVocabulary"
                                ) {

                                    cfg.inputTranscription
                                        .customVocabulary
                                        .forEach {
                                            add(it)
                                        }
                                }
                            }

                            put(
                                "mode",
                                cfg.inputTranscription
                                    .mode
                            )
                        }
                    }

                    if (
                        cfg.outputTranscription.enabled
                    ) {

                        putJsonObject(
                            "outputAudioTranscription"
                        ) {

                            if (
                                cfg.outputTranscription
                                    .languageCodes
                                    .isNotEmpty()
                            ) {

                                putJsonArray(
                                    "languageCodes"
                                ) {
                                    cfg.outputTranscription
                                        .languageCodes
                                        .forEach {
                                            add(it)
                                        }
                                }
                            }

                            if (
                                cfg.outputTranscription
                                    .customVocabulary
                                    .isNotEmpty()
                            ) {

                                putJsonArray(
                                    "customVocabulary"
                                ) {

                                    cfg.outputTranscription
                                        .customVocabulary
                                        .forEach {
                                            add(it)
                                        }
                                }
                            }

                            put(
                                "mode",
                                cfg.outputTranscription
                                    .mode
                            )
                        }
                    }

                    if (cfg.compression.enabled) {
                        val trigger = cfg.compression.triggerTokens
                        val target = cfg.compression.targetTokens

                        putJsonObject("contextWindowCompression") {
                            when {
                                // Oneof normalization: never emit both union members.
                                target > 0 -> {
                                    putJsonObject("slidingWindow") {
                                        put("targetTokens", target)
                                    }
                                }
                                trigger > 0 -> {
                                    put("triggerTokens", trigger)
                                }
                                else -> {
                                    // Empty slidingWindow enables compression using
                                    // the documented server defaults.
                                    putJsonObject("slidingWindow") {}
                                }
                            }
                        }
                    }

                    putJsonObject(
                        "realtimeInputConfig"
                    ) {

                        putJsonObject(
                            "automaticActivityDetection"
                        ) {

                            put(
                                "disabled",
                                !cfg.realtimeInput
                                    .aadEnabled
                            )

                            if (
                                cfg.realtimeInput
                                    .aadEnabled
                            ) {

                                put(
                                    "startOfSpeechSensitivity",
                                    cfg.realtimeInput
                                        .startSensitivity
                                )

                                put(
                                    "endOfSpeechSensitivity",
                                    cfg.realtimeInput
                                        .endSensitivity
                                )

                                put(
                                    "prefixPaddingMs",
                                    cfg.realtimeInput
                                        .prefixPaddingMs
                                )

                                put(
                                    "silenceDurationMs",
                                    cfg.realtimeInput
                                        .silenceDurationMs
                                )
                            }
                        }

                        put(
                            "activityHandling",
                            cfg.realtimeInput
                                .activityHandling
                        )

                        put(
                            "turnCoverage",
                            cfg.realtimeInput
                                .turnCoverage
                        )
                    }

                    if (
                        cfg.systemInstruction.isNotBlank()
                    ) {

                        putJsonObject(
                            "systemInstruction"
                        ) {

                            putJsonArray(
                                "parts"
                            ) {

                                addJsonObject {

                                    put(
                                        "text",
                                        cfg.systemInstruction
                                    )
                                }
                            }
                        }
                    }

                    val allTools =
                        buildJsonArray {

                            normalizeToolsForModel(
                                cfg.toolsJson,
                                capabilities
                            )?.forEach {
                                add(it)
                            }

                            if (
                                cfg.enableGoogleSearch
                            ) {

                                addJsonObject {

                                    putJsonObject(
                                        "googleSearch"
                                    ) {}
                                }
                            }
                        }

                    if (
                        allTools.isNotEmpty()
                    ) {

                        put(
                            "tools",
                            allTools
                        )
                    }

                    if (
                        cfg.sessionResumptionEnabled
                    ) {
                        putJsonObject(
                            "sessionResumption"
                        ) {
                            cfg.resumptionHandle
                                ?.takeIf { it.isNotBlank() }
                                ?.let { handle ->
                                    put("handle", handle)
                                }
                        }
                    }

                    if (
                        cfg.initialHistory.isNotEmpty()
                    ) {

                        putJsonObject(
                            "historyConfig"
                        ) {

                            put(
                                "initialHistoryInClientContent",
                                true
                            )
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

        val decodedPcmParts = modelParts?.mapNotNull { partEl ->
            val inline = partEl.jsonObject["inlineData"]?.jsonObject ?: return@mapNotNull null
            val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val data = inline["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (!mime.startsWith("audio/pcm") || data.isEmpty()) return@mapNotNull null
            runCatching { Base64.decode(data, Base64.NO_WRAP) }.getOrNull()
        }.orEmpty()

        var frameGenerationId = synchronized(sessionStateLock) {
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

            val setupHistory =
                if (root.containsKey("setupComplete")) {
                    synchronized(sessionStateLock) {
                        if (
                            myEpoch == epoch &&
                            webSocket === sourceWebSocket &&
                            !isReady
                        ) {
                            activeConfig?.initialHistory
                                .orEmpty()
                                .takeLast(
                                    MAX_INITIAL_HISTORY_TURNS
                                )
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
                        sourceWebSocket.close(
                            1011,
                            "initial history send failed"
                        )
                        return
                    }
                }

                synchronized(sessionStateLock) {
                    if (
                        myEpoch == epoch &&
                        webSocket === sourceWebSocket &&
                        !isReady
                    ) {
                        isReady = true
                        protocolPhase = ProtocolPhase.READY
                        emitControl(
                            GeminiEvent.SetupComplete
                        )
                    } else {
                        return
                    }
                }
            }

            (root["interactionStatus"] ?: root["interaction_status"])
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

            root["sessionResumptionUpdate"]?.jsonObject?.let { sru ->
                val handle = sru["newHandle"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                val resumable = sru["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
                emitControl(GeminiEvent.ResumptionHandle(handle, resumable))
            }

            root["groundingMetadata"]?.jsonObject?.let {
                emitData(GeminiEvent.GroundingMetadata(GroundingMetadata(it)))
            }
            root["urlContextMetadata"]?.jsonObject?.let {
                emitData(GeminiEvent.UrlContextMetadata(UrlContextMetadata(it)))
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.contentOrNull?.let { raw ->
                    if (raw.endsWith("s", true)) raw.dropLast(1).toDoubleOrNull()?.times(1000.0)?.toLong()
                    else raw.toLongOrNull()
                } ?: goAway["timeLeftMs"]?.jsonPrimitive?.longOrNull
                emitControl(GeminiEvent.GoAway(timeLeft?.coerceAtLeast(0L)))
            }

            val sc = root["serverContent"]?.jsonObject
            if (sc != null) {
                val interrupted = sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true
                if (interrupted) {
                    emitControl(GeminiEvent.Interrupted)
                    serverGenerationOpen = false
                }

                extractTranscriptText(sc["interimInputTranscription"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let { emitData(GeminiEvent.InputTranscript(it, interim = true)) }
                extractTranscriptText(sc["inputTranscription"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let { emitControl(GeminiEvent.InputTranscript(it, interim = false)) }
                extractTranscriptText(sc["outputTranscription"])
                    ?.takeIf { it.isNotBlank() }
                    ?.let { emitControl(GeminiEvent.OutputTranscript(it)) }

                if (!interrupted) {
                    var audioIndex = 0
                    sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partEl ->
                        val part = partEl.jsonObject
                        val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true
                        if (!isThought) {
                            part["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let { emitData(GeminiEvent.ModelText(it)) }
                        }
                        val inline = part["inlineData"]?.jsonObject
                        val mime = inline?.get("mimeType")?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (mime.startsWith("audio/pcm")) {
                            val pcm = decodedPcmParts.getOrNull(audioIndex++) ?: return@forEach
                            val generation = audioEngine.currentPlaybackGeneration
                            val key = DataBudgetKey(mySessionId, myEpoch)
                            var accepted = false
                            val bytes = pcm.size.toLong()
                            synchronized(sessionStateLock) {
                                if (myEpoch == epoch && webSocket === sourceWebSocket) {
                                    val perSession = audioBudgetBySession.computeIfAbsent(key) { AtomicLong(0L) }
                                    val aggregate = perSession.get() + bytes
                                    if (aggregate <= MAX_AI_AUDIO_BACKLOG_BYTES) {
                                        perSession.addAndGet(bytes)
                                        accepted = _audio.trySend(
                                            AudioFrame(pcm, mySessionId, myEpoch, generation, frameId)
                                        ).isSuccess
                                        if (!accepted) {
                                            audioBudgetBySession[key]?.let { c ->
                                                if (c.addAndGet(-bytes) <= 0L) audioBudgetBySession.remove(key, c)
                                            }
                                        }
                                    }
                                }
                            }
                            if (!accepted) {
                                val backlog = audioBudgetBySession[key]?.get() ?: 0L
                                if (backlog >= MAX_AI_AUDIO_BACKLOG_BYTES) {
                                    audioEngine.invalidateAndFlushPlayback("server audio backlog overflow")
                                }
                            }
                        }
                    }
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

                sc["generationComplete"]?.jsonPrimitive?.booleanOrNull?.takeIf { it }
                    ?.let {
                        emitControl(GeminiEvent.GenerationComplete)
                        serverGenerationOpen = false
                    }

                sc["turnComplete"]?.jsonPrimitive?.booleanOrNull?.takeIf { it }
                    ?.let { emitControl(GeminiEvent.TurnComplete) }

                sc["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emitControl(GeminiEvent.ToolCallCancelled(it)) }
            }
        }
    }

    private fun extractTranscriptText(element: JsonElement?): String? {
        return when (element) {
            is JsonObject ->
                element["text"]
                    ?.jsonPrimitive
                    ?.contentOrNull
            is JsonPrimitive ->
                element.contentOrNull
            else -> null
        }
    }

    private fun closeInternal() {
        val ws: WebSocket?
        val newEpoch: Long

        synchronized(sessionStateLock) {
            // One atomic lifecycle boundary: invalidate the identity, detach
            // the socket, and drain stale queues before releasing the lock.
            ws = webSocket
            newEpoch = epochGen.incrementAndGet()
            epoch = newEpoch

            protocolPhase = ProtocolPhase.CLOSING
            webSocket = null
            isReady = false
            activeConfig = null
            cancelledToolCallIds.clear()

            while (true) {
                val frame = _audio.tryReceive().getOrNull() ?: break
                releaseAudio(frame)
            }

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

            // Do not reset either global ownership counter here. A consumer may
            // already have received an old frame/event and can execute its
            // release/decrement after this close transaction. Preserving the
            // outstanding ownership count prevents that late release from
            // decrementing capacity belonging to the next session.
        }

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        stopAudioWriter()

        runCatching {
            ws?.close(
                1000,
                "close"
            )
        }

        runCatching {
            ws?.cancel()
        }

        synchronized(sessionStateLock) {
            if (webSocket == null && epoch == newEpoch) {
                protocolPhase = ProtocolPhase.IDLE
            }
        }

        logManager.w(
            "WebSocket",
            "Старая сессия закрыта (новая эпоха=$newEpoch)"
        )
    }

    suspend fun disconnect() =
        wsMutex.withLock {
            logManager.w(
                "WebSocket",
                "Отключение сессии (эпоха будет сменена атомарно)"
            )
            closeInternal()
        }
}