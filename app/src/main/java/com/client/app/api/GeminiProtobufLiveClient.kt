package com.client.app.api

import android.util.Base64
import kotlinx.coroutines.*
import com.client.app.audio.NativeAudioBridge
import com.client.app.audio.NativeAudioEngine
import com.client.app.logging.AppLogManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ReceiveChannel
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

@Singleton
class GeminiProtobufLiveClient @Inject constructor(
    private val nativeBridge: NativeAudioBridge,
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
            4L * 1024L * 1024L

        private const val MAX_DATA_EVENTS_IN_FLIGHT = 256
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
            .socketFactory(
                TunedSocketFactory(
                    SocketFactory.getDefault(),
                    nativeBridge,
                    logManager
                )
            )
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
            .retryOnConnectionFailure(true)
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

    private val queuedAudioBytes =
        AtomicLong(0L)

    // AUD-006:
    // One FIFO event ingress preserves the actual server delivery order.
    // Control events are never rejected. High-frequency data events are
    // bounded by count and are dropped newest when the data budget is full.
    // This is deliberately implemented above the Channel rather than with
    // an eviction policy so an evicted event can never leak ownership
    // or reorder lifecycle events.
    private data class QueuedEvent(
        val epoch: Long,
        val event: GeminiEvent,
        val isDataPlane: Boolean
    )

    private val pendingDataEvents =
        AtomicLong(0L)

    private val _events =
        Channel<QueuedEvent>(
            Channel.UNLIMITED
        )

    val events: Flow<GeminiEventEnvelope> =
        _events
            .receiveAsFlow()
            .onEach { queued ->
                if (queued.isDataPlane) {
                    pendingDataEvents.updateAndGet {
                        (it - 1L).coerceAtLeast(0L)
                    }
                }
            }
            .filter { queued ->
                // Old callbacks can still be physically present in the FIFO
                // after a reconnect. Filter by epoch at consumption so stale
                // events are not normally delivered to SessionManager. The
                // explicit envelope keeps the source epoch available for a
                // second validation at the consumer boundary.
                queued.epoch == epoch
            }
            .map {
                GeminiEventEnvelope(
                    epoch = it.epoch,
                    event = it.event
                )
            }

    // AI output audio is independently bounded by byte accounting.
    private val _audio =
        Channel<AudioFrame>(Channel.UNLIMITED)

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

    // Serializes realtime audio/activity batching and queueing.
    // We never hold batchLock while suspending.
    private val audioOutboundMutex =
        Mutex()

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
        eventEpoch: Long
    ) {
        if (eventEpoch != epoch) return

        _events.trySend(
            QueuedEvent(
                epoch = eventEpoch,
                event = event,
                isDataPlane = false
            )
        )
    }

    private fun emitDataEvent(
        event: GeminiEvent,
        eventEpoch: Long
    ) {
        if (eventEpoch != epoch) return
        while (true) {
            val current = pendingDataEvents.get()
            if (current >= MAX_DATA_EVENTS_IN_FLIGHT) {
                return
            }
            if (pendingDataEvents.compareAndSet(current, current + 1L)) {
                break
            }
        }

        val result = _events.trySend(
            QueuedEvent(
                epoch = eventEpoch,
                event = event,
                isDataPlane = true
            )
        )

        if (result.isFailure) {
            pendingDataEvents.updateAndGet {
                (it - 1L).coerceAtLeast(0L)
            }
        }
    }

    fun invalidateAudio(): Long =
        audioEngine.invalidateAndFlushPlayback("transport invalidate")

    fun releaseAudio(bytes: Int) {
        if (bytes <= 0) {
            return
        }

        while (true) {
            val current =
                queuedAudioBytes.get()

            val next =
                (
                    current -
                    bytes.toLong()
                ).coerceAtLeast(0L)

            if (
                queuedAudioBytes.compareAndSet(
                    current,
                    next
                )
            ) {
                return
            }
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

        // The old socket is fully detached at this point. The lifecycle owner
        // advances and physically flushes playback before the new socket can
        // receive server audio.
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
                            "Отправка 3.8 setup сообщения",
                            setupMsg
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

                        if (myEpoch == epoch) {

                            val logSummary =
                                if (
                                    text.contains(
                                        "\"audio/pcm"
                                    )
                                ) {
                                    "[Аудиочанк ~${text.length} байт]"
                                } else {
                                    text
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
            } catch (_: CancellationException) {
                // Normal lifecycle shutdown.
            } catch (t: Throwable) {
                logManager.e(
                    "WebSocket:AudioWriter",
                    "Ошибка realtime audio writer",
                    t
                )
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
        if (pcm.isEmpty()) {
            return
        }

        audioOutboundMutex.withLock {

            val writerEpoch = epoch
            val ws = webSocket
                ?: return
            val channel = audioWriterChannel
                ?: return

            val payload =
                synchronized(batchLock) {
                    isAudioStreamEnded = false
                    audioBatchBuffer.write(pcm)

                    if (
                        audioBatchBuffer.size() <
                        AUDIO_BATCH_THRESHOLD_BYTES
                    ) {
                        null
                    } else {
                        audioBatchBuffer
                            .toByteArray()
                            .also {
                                audioBatchBuffer.reset()
                            }
                    }
                } ?: return

            if (
                writerEpoch != epoch ||
                webSocket !== ws ||
                audioWriterChannel !== channel
            ) {
                return
            }

            try {
                channel.send(
                    AudioOutboundCommand.Pcm(payload)
                )
            } catch (_: ClosedSendChannelException) {
                // Normal reconnect/close race: stale PCM must not become a
                // session-fatal exception.
            } catch (_: CancellationException) {
                // Caller is stopping the capture coroutine.
            }
        }
    }

    // AUD-067:
    // Tail PCM is queued before AudioStreamEnd in the same ordered channel.
    suspend fun sendAudioStreamEnd() {
        audioOutboundMutex.withLock {
            if (isAudioStreamEnded) {
                return
            }

            val writerEpoch = epoch
            val ws = webSocket
                ?: return
            val channel = audioWriterChannel
                ?: return

            val tailPayload =
                synchronized(batchLock) {
                    if (audioBatchBuffer.size() == 0) {
                        null
                    } else {
                        audioBatchBuffer
                            .toByteArray()
                            .also {
                                audioBatchBuffer.reset()
                            }
                    }
                }

            isAudioStreamEnded = true

            if (
                writerEpoch != epoch ||
                webSocket !== ws ||
                audioWriterChannel !== channel
            ) {
                return
            }

            try {
                if (tailPayload != null) {
                    channel.send(
                        AudioOutboundCommand.Pcm(
                            tailPayload
                        )
                    )
                }

                channel.send(
                    AudioOutboundCommand.AudioStreamEnd
                )
            } catch (_: ClosedSendChannelException) {
                // Normal lifecycle race.
            } catch (_: CancellationException) {
                // Normal coroutine cancellation.
            }
        }
    }

    fun sendRealtimeText(
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
            "Отправка realtimeInput.text: '$cleanText'",
            jsonMessage
        )

        synchronized(sessionStateLock) {
            if (
                sendEpoch == epoch &&
                isReady &&
                webSocket === ws
            ) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    fun sendRealtimeImage(
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

        synchronized(sessionStateLock) {
            if (
                sendEpoch == epoch &&
                isReady &&
                webSocket === ws
            ) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    // Manual VAD activity markers are serialized through the same
    // realtime audio command stream.
    suspend fun sendActivityStart() {

        audioOutboundMutex.withLock {

            val writerEpoch = epoch
            val ws = webSocket
                ?: return
            val channel = audioWriterChannel
                ?: return

            // A new manually-delimited activity starts a new logical input
            // segment. Any sub-threshold bytes left from a previous segment
            // must never leak into this one.
            synchronized(batchLock) {
                isAudioStreamEnded = false
                audioBatchBuffer.reset()
            }

            if (
                writerEpoch != epoch ||
                webSocket !== ws ||
                audioWriterChannel !== channel
            ) {
                return
            }

            try {
                channel.send(
                    AudioOutboundCommand.ActivityStart
                )
            } catch (_: ClosedSendChannelException) {
                // Normal lifecycle race.
            } catch (_: CancellationException) {
                // Normal cancellation.
            }
        }
    }

    suspend fun sendActivityEnd() {

        audioOutboundMutex.withLock {

            val writerEpoch = epoch
            val ws = webSocket
                ?: return
            val channel = audioWriterChannel
                ?: return

            val tailPayload =
                synchronized(batchLock) {
                    isAudioStreamEnded = true
                    if (audioBatchBuffer.size() == 0) {
                        null
                    } else {
                        audioBatchBuffer
                            .toByteArray()
                            .also {
                                audioBatchBuffer.reset()
                            }
                    }
                }

            if (
                writerEpoch != epoch ||
                webSocket !== ws ||
                audioWriterChannel !== channel
            ) {
                return
            }

            try {
                if (tailPayload != null) {
                    channel.send(
                        AudioOutboundCommand.Pcm(tailPayload)
                    )
                }

                channel.send(
                    AudioOutboundCommand.ActivityEnd
                )
            } catch (_: ClosedSendChannelException) {
                // Normal lifecycle race.
            } catch (_: CancellationException) {
                // Normal cancellation.
            }
        }
    }

    fun sendClientContent(
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
            "Отправка clientContent (${turns.size} ходов, turnComplete=$turnComplete)",
            jsonMessage
        )

        return synchronized(sessionStateLock) {
            if (
                expectedEpoch == epoch &&
                webSocket === ws &&
                (targetWebSocket != null || isReady)
            ) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            } else {
                false
            }
        }
    }

    fun sendToolResponses(
        responses: List<ToolResponse>
    ) {

        val sendEpoch = epoch
        val ws =
            webSocket ?: return

        if (
            !isReady ||
            responses.isEmpty()
        ) {
            return
        }

        val jsonMessage =
            buildJsonObject {
                putJsonObject(
                    "toolResponse"
                ) {

                    putJsonArray(
                        "functionResponses"
                    ) {

                        responses.forEach { resp ->

                            addJsonObject {

                                if (
                                    !resp.id.isNullOrBlank()
                                ) {

                                    put(
                                        "id",
                                        resp.id
                                    )
                                }

                                put(
                                    "name",
                                    resp.name
                                )

                                put(
                                    "response",
                                    resp.response
                                )

                                put(
                                    "scheduling",
                                    resp.scheduling.name
                                )

                                if (
                                    resp.willContinue
                                ) {

                                    put(
                                        "willContinue",
                                        true
                                    )
                                }

                                if (
                                    resp.parts.isNotEmpty()
                                ) {

                                    putJsonArray(
                                        "parts"
                                    ) {

                                        resp.parts.forEach { part ->

                                            addJsonObject {

                                                putJsonObject(
                                                    "inlineData"
                                                ) {
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
        val debugLog =
            responses.joinToString {
                "${it.name}(id=${it.id}, sched=${it.scheduling.name})"
            }

        logManager.net(
            "WebSocket:ToolResp",
            "Ответы функций: [$debugLog]",
            jsonMessage
        )

        synchronized(sessionStateLock) {
            if (
                sendEpoch == epoch &&
                isReady &&
                webSocket === ws
            ) {
                synchronized(outboundSendLock) {
                    ws.send(jsonMessage)
                }
            }
        }
    }

    private fun validateCompressionConfig(
        cfg: LiveConfig
    ) {
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

    private fun buildSetupMessage(
        cfg: LiveConfig
    ): String {

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

                            if (
                                cfg.toolsJson != null &&
                                cfg.toolsJson.isNotEmpty()
                            ) {

                                cfg.toolsJson.forEach {
                                    add(it)
                                }
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
        rawJson: String,
        myEpoch: Long,
        sourceWebSocket: WebSocket
    ) {

        try {

            val root =
                json.parseToJsonElement(
                    rawJson
                ).jsonObject

            if (
                root.containsKey(
                    "setupComplete"
                )
            ) {

                synchronized(sessionStateLock) {
                    if (
                        myEpoch != epoch ||
                        webSocket !== sourceWebSocket ||
                        isReady
                    ) {
                        return
                    }

                    logManager.i(
                        "GeminiLive",
                        "Сессия 3.8 готова: setupComplete получен"
                    )

                    val history =
                        activeConfig?.initialHistory

                    if (!history.isNullOrEmpty()) {
                        val bounded =
                            history.takeLast(
                                MAX_INITIAL_HISTORY_TURNS
                            )

                        // This send is deliberately performed before isReady
                        // becomes visible to the realtime writer. The server
                        // explicitly waits for initial clientContent to finish
                        // before realtimeInput is accepted as the live turn.
                        val historyAccepted =
                            sendClientContentInternal(
                                turns = bounded,
                                turnComplete = true,
                                targetWebSocket = sourceWebSocket,
                                expectedEpoch = myEpoch
                            )

                        if (!historyAccepted) {
                            logManager.e(
                                "GeminiLive",
                                "Не удалось отправить initial history; сессия не переводится в READY"
                            )
                            sourceWebSocket.close(1011, "initial history send failed")
                            return
                        }

                        logManager.net(
                            "WebSocket:History",
                            "Первоначальная история (${bounded.size} ходов) внедрена после setupComplete"
                        )
                    }

                    isReady = true

                    emitControlEvent(
                        GeminiEvent.SetupComplete,
                        myEpoch
                    )
                }
            }

            root["usageMetadata"]
                ?.jsonObject
                ?.get("totalTokenCount")
                ?.jsonPrimitive
                ?.intOrNull
                ?.let {

                    emitDataEvent(
                        GeminiEvent.Usage(it),
                        myEpoch
                    )
                }

            root["goAway"]
                ?.jsonObject
                ?.let { goAway ->

                    val timeLeftMs =
                        goAway["timeLeft"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            ?.let { str ->

                                if (
                                    str.endsWith("s")
                                ) {

                                    str.removeSuffix(
                                        "s"
                                    )
                                    .toDoubleOrNull()
                                    ?.let {
                                        (
                                            it * 1000
                                        ).toLong()
                                    }

                                } else {

                                    str.toLongOrNull()
                                }
                            }
                            ?: goAway["timeLeftMs"]
                                ?.jsonPrimitive
                                ?.longOrNull
                            ?: 10000L

                    logManager.w(
                        "GeminiLive",
                        "Получен сигнал GoAway: осталось $timeLeftMs мс"
                    )

                    emitControlEvent(
                        GeminiEvent.GoAway(timeLeftMs),
                        myEpoch
                    )
                }

            root["sessionResumptionUpdate"]
                ?.jsonObject
                ?.let { sru ->

                    val handle =
                        sru["newHandle"]
                            ?.jsonPrimitive
                            ?.contentOrNull
                            .orEmpty()

                    val resumable =
                        sru["resumable"]
                            ?.jsonPrimitive
                            ?.booleanOrNull
                            ?: true

                    emitControlEvent(
                        GeminiEvent.ResumptionHandle(
                            handle = handle.takeIf { it.isNotBlank() },
                            resumable = resumable
                        ),
                        myEpoch
                    )
                }

            root["toolCall"]
                ?.jsonObject
                ?.let { tc ->
                    val calls =
                        tc["functionCalls"]
                            ?.jsonArray
                            ?.mapNotNull { fcEl ->

                                val fc =
                                    fcEl.jsonObject

                                val name =
                                    fc["name"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull
                                        ?: return@mapNotNull null

                                val id =
                                    fc["id"]
                                        ?.jsonPrimitive
                                        ?.contentOrNull

                                val argsObj =
                                    fc["args"]
                                        ?.jsonObject
                                        ?: buildJsonObject {}

                                FunctionCall(
                                    name,
                                    id,
                                    argsObj
                                )
                            }
                            ?: emptyList()

                    if (
                        calls.isNotEmpty()
                    ) {

                        emitControlEvent(
                            GeminiEvent.ToolCall(
                                calls
                            ),
                            myEpoch
                        )
                    }
                }

            root["toolCallCancellation"]
                ?.jsonObject
                ?.get("ids")
                ?.jsonArray
                ?.let { idsArr ->

                    val ids =
                        idsArr.mapNotNull {
                            it.jsonPrimitive
                                .contentOrNull
                        }

                    if (
                        ids.isNotEmpty()
                    ) {

                        emitControlEvent(
                            GeminiEvent.ToolCallCancelled(
                                ids
                            ),
                            myEpoch
                        )
                    }
                }

            root["serverContent"]
                ?.jsonObject
                ?.let { sc ->

                    val interrupted =
                        sc["interrupted"]
                            ?.jsonPrimitive
                            ?.booleanOrNull == true

                    if (interrupted) {

                        emitControlEvent(
                            GeminiEvent.Interrupted,
                            myEpoch
                        )
                    }

                    if (
                        sc["generationComplete"]
                            ?.jsonPrimitive
                            ?.booleanOrNull == true
                    ) {

                        emitControlEvent(
                            GeminiEvent.GenerationComplete,
                            myEpoch
                        )
                    }

                    if (
                        sc["turnComplete"]
                            ?.jsonPrimitive
                            ?.booleanOrNull == true
                    ) {

                        emitControlEvent(
                            GeminiEvent.TurnComplete,
                            myEpoch
                        )
                    }

                    extractTranscriptText(sc["interimInputTranscription"])
                        ?.let {

                            if (
                                it.isNotBlank()
                            ) {

                                emitDataEvent(
                                    GeminiEvent.InputTranscript(
                                        it,
                                        interim = true
                                    ),
                                    myEpoch
                                )
                            }
                        }

                    extractTranscriptText(sc["inputTranscription"])
                        ?.let {

                            if (
                                it.isNotBlank()
                            ) {

                                emitControlEvent(
                                    GeminiEvent.InputTranscript(
                                        it,
                                        interim = false
                                    ),
                                    myEpoch
                                )
                            }
                        }

                    extractTranscriptText(sc["outputTranscription"])
                        ?.let {

                            if (
                                it.isNotBlank()
                            ) {

                                emitControlEvent(
                                    GeminiEvent.OutputTranscript(
                                        it
                                    ),
                                    myEpoch
                                )
                            }
                        }

                    // Google documents `interrupted=true` as the signal to
                    // stop and flush the current playback queue. The same
                    // server event may still contain the tail of the cancelled
                    // model turn; never enqueue that stale audio.
                    if (!interrupted) {
                        sc["modelTurn"]
                            ?.jsonObject
                            ?.get("parts")
                            ?.jsonArray
                            ?.forEach { partEl ->

                            val part =
                                partEl.jsonObject

                            val isThought =
                                part["thought"]
                                    ?.jsonPrimitive
                                    ?.booleanOrNull == true
                            if (!isThought) {

                                part["text"]
                                    ?.jsonPrimitive
                                    ?.contentOrNull
                                    ?.let { text ->

                                        if (
                                            text.isNotBlank()
                                        ) {

                                            emitDataEvent(
                                                GeminiEvent.ModelText(
                                                    text
                                                ),
                                                myEpoch
                                            )
                                        }
                                    }
                            }
                            part["inlineData"]
                                ?.jsonObject
                                ?.let { inline ->

                                    val mime =
                                        inline["mimeType"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                            .orEmpty()

                                    val dataB64 =
                                        inline["data"]
                                            ?.jsonPrimitive
                                            ?.contentOrNull
                                            .orEmpty()

                                    if (
                                        mime.startsWith(
                                            "audio/pcm"
                                        ) &&
                                        dataB64.isNotEmpty()
                                    ) {

                                        val pcmBytes =
                                            Base64.decode(
                                                dataB64,
                                                Base64.NO_WRAP
                                            )

                                        // Playback generation is independent of
                                        // transport epoch and can change during
                                        // the lifetime of one WebSocket (for
                                        // example after local barge-in). Sample
                                        // it for each accepted server audio part.
                                        val currentGen =
                                            audioEngine.currentPlaybackGeneration

                                        var backlogOverflow = false
                                        var sendResult: ChannelResult<Unit>? = null

                                        synchronized(sessionStateLock) {
                                            if (
                                                myEpoch == epoch &&
                                                webSocket === sourceWebSocket
                                            ) {
                                                val newBacklog =
                                                    queuedAudioBytes
                                                        .addAndGet(
                                                            pcmBytes.size
                                                                .toLong()
                                                        )

                                                if (
                                                    newBacklog >
                                                    MAX_AI_AUDIO_BACKLOG_BYTES
                                                ) {
                                                    queuedAudioBytes
                                                        .addAndGet(
                                                            -pcmBytes.size
                                                                .toLong()
                                                        )
                                                    backlogOverflow = true
                                                } else {
                                                    sendResult = _audio.trySend(
                                                        AudioFrame(
                                                            pcmBytes,
                                                            myEpoch,
                                                            currentGen
                                                        )
                                                    )
                                                }
                                            }
                                        }

                                        if (backlogOverflow) {
                                            logManager.w(
                                                "AudioStream",
                                                "Превышен лимит бэклога. Controlled recovery."
                                            )
                                            if (
                                                myEpoch == epoch &&
                                                webSocket === sourceWebSocket
                                            ) {
                                                audioEngine.invalidateAndFlushPlayback(
                                                    "server audio backlog overflow"
                                                )
                                            }
                                            return@forEach
                                        }

                                        if (sendResult == null) {
                                            releaseAudio(pcmBytes.size)
                                            return@forEach
                                        }

                                        if (
                                            sendResult.isFailure
                                        ) {

                                            releaseAudio(
                                                pcmBytes.size
                                            )

                                            return@forEach
                                        }
                                    }
                                }
                        }
                    }
                }

        } catch (e: Exception) {

            logManager.e(
                "GeminiLive:Parse",
                "Ошибка разбора входящего кадра: ${e.message}",
                e
            )
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

        // Invalidate the old session BEFORE closing its socket. OkHttp may
        // invoke callbacks asynchronously during close; the new epoch and
        // socket identity become stale atomically.
        synchronized(sessionStateLock) {
            ws = webSocket
            newEpoch =
                epochGen.incrementAndGet()
            epoch = newEpoch

            webSocket = null
            isReady = false
            activeConfig = null
        }

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        stopAudioWriter()

        // The current consumer may still own a frame concurrently, but the
        // frame carries its epoch and SessionManager will drop it after the
        // epoch fence. All frames remaining in the transport queue must be
        // explicitly released here so the byte watermark cannot poison the
        // next connection.
        while (true) {
            val frame = _audio.tryReceive().getOrNull() ?: break
            releaseAudio(frame.pcm.size)
        }

        // Remove stale events from the FIFO as well. Otherwise setting the
        // pending-data counter to zero while stale data events remain queued
        // would let their later delivery decrement the counter that belongs
        // to the next epoch.
        while (true) {
            _events.tryReceive().getOrNull() ?: break
        }

        queuedAudioBytes.set(0L)
        pendingDataEvents.set(0L)

        runCatching {
            ws?.close(
                1000,
                "close"
            )
        }

        runCatching {
            ws?.cancel()
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
