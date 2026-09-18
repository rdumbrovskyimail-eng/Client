package com.client.app.api

import android.util.Base64
import kotlinx.coroutines.*
import com.client.app.audio.NativeAudioBridge
import com.client.app.logging.AppLogManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    private val logManager: AppLogManager
) {
    companion object {
        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val MAX_QUEUE_BYTES = 256L * 1024L
        private const val AUDIO_BATCH_THRESHOLD_BYTES = 1280
        private const val WS_QUEUE_POLL_MS = 5L
        private const val AUDIO_COMMAND_CHANNEL_CAPACITY = 32
        private const val MAX_INITIAL_HISTORY_TURNS = 20
        private const val MAX_AI_AUDIO_BACKLOG_BYTES = 4L * 1024L * 1024L
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private sealed interface AudioOutboundCommand {
        data class Pcm(val payload: ByteArray) : AudioOutboundCommand
        data object ActivityStart : AudioOutboundCommand
        data object ActivityEnd : AudioOutboundCommand
        data object AudioStreamEnd : AudioOutboundCommand
    }

    private val loggingEventListener = object : EventListener() {
        override fun dnsStart(call: Call, domainName: String) {
            logManager.net("OkHttp:DNS", "Старт DNS-резолва: $domainName")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            logManager.net("OkHttp:DNS", "DNS успешен: $domainName -> $inetAddressList")
        }

        override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
            logManager.net("OkHttp:TCP", "Подключение к $inetSocketAddress...")
        }

        override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
            logManager.net("OkHttp:TCP", "TCP соединение установлено ($protocol)")
        }

        override fun secureConnectStart(call: Call) {
            logManager.net("OkHttp:TLS", "Старт TLS 1.3 хендшейка...")
        }

        override fun secureConnectEnd(call: Call, handshake: Handshake?) {
            val tls = handshake?.tlsVersion
            val cipher = handshake?.cipherSuite
            logManager.net("OkHttp:TLS", "TLS успешен: $tls [$cipher]")
        }

        override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
            logManager.e("OkHttp:Connect", "Сбой подключения к $inetSocketAddress: ${ioe.message}", ioe)
        }
    }

    private val httpClient = OkHttpClient.Builder()
        .eventListener(loggingEventListener)
        .socketFactory(TunedSocketFactory(SocketFactory.getDefault(), nativeBridge, logManager))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(12, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val wsMutex = Mutex()

    @Volatile
    private var webSocket: WebSocket? = null
    private val epochGen = AtomicLong(0)

    @Volatile
    var epoch: Long = 0L
        private set

    private val audioGenerationGen = AtomicLong(0L)
    val audioGeneration: Long get() = audioGenerationGen.get()

    private val queuedAudioBytes = AtomicLong(0L)

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<GeminiEvent> = _events.asSharedFlow()

    private val _audio = Channel<AudioFrame>(Channel.UNLIMITED)
    val audio: ReceiveChannel<AudioFrame> = _audio

    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    private var activeConfig: LiveConfig? = null

    private val audioBatchBuffer = ByteArrayOutputStream(AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()
    private val audioOutboundMutex = Mutex()
    private var isAudioStreamEnded = true

    @Volatile
    private var audioWriterScope: CoroutineScope? = null

    @Volatile
    private var audioWriterChannel: Channel<AudioOutboundCommand>? = null

    fun invalidateAudio(): Long {
        val newGeneration = audioGenerationGen.incrementAndGet()
        logManager.audio("AudioStream", "Audio generation invalidated: $newGeneration")
        return newGeneration
    }

    fun releaseAudio(bytes: Int) {
        if (bytes <= 0) return
        while (true) {
            val current = queuedAudioBytes.get()
            val next = (current - bytes.toLong()).coerceAtLeast(0L)
            if (queuedAudioBytes.compareAndSet(current, next)) return
        }
    }

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()

        isReady = false
        activeConfig = cfg

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        invalidateAudio()

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        val rawKey = cfg.apiKey.trim()
        val encodedKey = URLEncoder.encode(rawKey, "UTF-8")
        val url = "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        logManager.net("WebSocket", "Инициализация Bidi сессии Gemini 3.8 Live (epoch=$myEpoch)")

        val req = Request.Builder()
            .url(url)
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .build()

        val ws = httpClient.newWebSocket(
            req,
            object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    if (myEpoch != epoch) {
                        ws.close(1000, "stale")
                        return
                    }

                    logManager.net("WebSocket:Open", "Соединение открыто! HTTP ${response.code}")
                    _events.tryEmit(GeminiEvent.Connected)

                    val setupMsg = buildSetupMessage(cfg)
                    logManager.net("WebSocket:Tx", "Отправка setup сообщения", setupMsg)
                    ws.send(setupMsg)
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (myEpoch == epoch) {
                        parseServerJsonMessage(text, myEpoch)
                    }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    if (myEpoch == epoch) {
                        parseServerJsonMessage(bytes.utf8(), myEpoch)
                    }
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    logManager.w("WebSocket:Closing", "Сервер инициировал закрытие: $code / '$reason'")
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    logManager.w("WebSocket:Closed", "Соединение закрыто (code=$code, reason='$reason')")
                    if (myEpoch != epoch) return
                    isReady = false
                    stopAudioWriter()
                    _events.tryEmit(GeminiEvent.Disconnected(code, reason, myEpoch))
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    val httpCode = response?.code
                    logManager.e("WebSocket:Failure", "Сбой сокета (HTTP $httpCode): ${t.localizedMessage}", t)
                    if (myEpoch != epoch) return
                    isReady = false
                    stopAudioWriter()
                    val fatal = httpCode in listOf(400, 401, 403, 404)
                    _events.tryEmit(GeminiEvent.Error("Сетевой сбой ($httpCode): ${t.localizedMessage}", fatal))
                    _events.tryEmit(GeminiEvent.Disconnected(httpCode ?: 1006, t.message.orEmpty(), myEpoch))
                }
            }
        )

        webSocket = ws
        startAudioWriter(ws, myEpoch)
    }

    private fun startAudioWriter(ws: WebSocket, writerEpoch: Long) {
        stopAudioWriter()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val channel = Channel<AudioOutboundCommand>(capacity = AUDIO_COMMAND_CHANNEL_CAPACITY)

        audioWriterScope = scope
        audioWriterChannel = channel

        scope.launch {
            try {
                for (command in channel) {
                    if (writerEpoch != epoch || !isReady || webSocket !== ws) break
                    if (!awaitWebSocketQueueCapacity(ws, writerEpoch)) break

                    val jsonMessage = when (command) {
                        is AudioOutboundCommand.Pcm -> {
                            val base64Data = Base64.encodeToString(command.payload, Base64.NO_WRAP)
                            buildJsonObject {
                                putJsonObject("realtimeInput") {
                                    putJsonObject("audio") {
                                        put("mimeType", "audio/pcm;rate=16000")
                                        put("data", base64Data)
                                    }
                                }
                            }.toString()
                        }
                        AudioOutboundCommand.ActivityStart -> {
                            buildJsonObject {
                                putJsonObject("realtimeInput") {
                                    putJsonObject("activityStart") {}
                                }
                            }.toString()
                        }
                        AudioOutboundCommand.ActivityEnd -> {
                            buildJsonObject {
                                putJsonObject("realtimeInput") {
                                    putJsonObject("activityEnd") {}
                                }
                            }.toString()
                        }
                        AudioOutboundCommand.AudioStreamEnd -> {
                            buildJsonObject {
                                putJsonObject("realtimeInput") {
                                    put("audioStreamEnd", true)
                                }
                            }.toString()
                        }
                    }

                    if (!ws.send(jsonMessage)) {
                        logManager.w("WebSocket:AudioWriter", "OkHttp отверг outbound audio/control command")
                        if (writerEpoch == epoch) isReady = false
                        break
                    }
                }
            } catch (e: CancellationException) {
                // Обычное завершение
            } catch (t: Throwable) {
                logManager.e("WebSocket:AudioWriter", "Ошибка realtime audio writer", t)
            }
        }
    }

    private suspend fun awaitWebSocketQueueCapacity(ws: WebSocket, writerEpoch: Long): Boolean {
        while (ws.queueSize() > MAX_QUEUE_BYTES) {
            if (writerEpoch != epoch || !isReady || webSocket !== ws) {
                return false
            }
            delay(WS_QUEUE_POLL_MS)
        }
        return (writerEpoch == epoch && isReady && webSocket === ws)
    }

    private fun stopAudioWriter() {
        audioWriterChannel?.close()
        audioWriterChannel = null
        audioWriterScope?.cancel()
        audioWriterScope = null
    }

    suspend fun sendAudioPcm(pcm: ByteArray) {
        if (!isReady || pcm.isEmpty()) return

        audioOutboundMutex.withLock {
            if (!isReady) return

            val payload: ByteArray? = synchronized(batchLock) {
                isAudioStreamEnded = false
                audioBatchBuffer.write(pcm)
                if (audioBatchBuffer.size() < AUDIO_BATCH_THRESHOLD_BYTES) {
                    null
                } else {
                    audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
                }
            }

            if (payload != null) {
                val channel = audioWriterChannel ?: return
                channel.send(AudioOutboundCommand.Pcm(payload))
            }
        }
    }

    suspend fun sendAudioStreamEnd() {
        audioOutboundMutex.withLock {
            if (!isReady || isAudioStreamEnded) return
            isAudioStreamEnded = true

            val channel = audioWriterChannel ?: return
            val tailPayload = synchronized(batchLock) {
                if (audioBatchBuffer.size() == 0) null
                else audioBatchBuffer.toByteArray().also { audioBatchBuffer.reset() }
            }

            if (tailPayload != null) {
                channel.send(AudioOutboundCommand.Pcm(tailPayload))
            }
            channel.send(AudioOutboundCommand.AudioStreamEnd)
        }
    }

    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!isReady || text.isBlank()) return

        val cleanText = text.trim()
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("text", cleanText)
            }
        }.toString()

        ws.send(jsonMessage)
    }

    fun sendRealtimeImage(jpegBytes: ByteArray) {
        val ws = webSocket ?: return
        if (!isReady || jpegBytes.isEmpty()) return

        val base64Data = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("video") {
                    put("mimeType", "image/jpeg")
                    put("data", base64Data)
                }
            }
        }.toString()

        ws.send(jsonMessage)
    }

    suspend fun sendActivityStart() {
        audioOutboundMutex.withLock {
            if (!isReady) return
            val channel = audioWriterChannel ?: return
            channel.send(AudioOutboundCommand.ActivityStart)
        }
    }

    suspend fun sendActivityEnd() {
        audioOutboundMutex.withLock {
            if (!isReady) return
            val channel = audioWriterChannel ?: return
            channel.send(AudioOutboundCommand.ActivityEnd)
        }
    }

    fun sendClientContent(turns: List<ClientTurn>, turnComplete: Boolean = true) {
        val ws = webSocket ?: return
        if (!isReady || turns.isEmpty()) return
        sendClientContentInternal(turns, turnComplete)
    }

    private fun sendClientContentInternal(turns: List<ClientTurn>, turnComplete: Boolean) {
        val ws = webSocket ?: return
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

        ws.send(jsonMessage)
    }

    fun sendToolResponses(responses: List<ToolResponse>) {
        val ws = webSocket ?: return
        if (!isReady || responses.isEmpty()) return

        val jsonMessage = buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    responses.forEach { resp ->
                        addJsonObject {
                            if (!resp.id.isNullOrBlank()) put("id", resp.id)
                            put("name", resp.name)
                            put("response", resp.response)
                            put("scheduling", resp.scheduling.name)
                            if (resp.willContinue) put("willContinue", true)
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

        ws.send(jsonMessage)
    }

    private fun buildSetupMessage(cfg: LiveConfig): String {
        val rawModelName = cfg.model.trim()
            .removePrefix("publishers/google/models/")
            .removePrefix("models/")
        val cleanModel = "models/$rawModelName"

        return buildJsonObject {
            putJsonObject("setup") {
                put("model", cleanModel)
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                    put("temperature", cfg.temperature)
                    put("mediaResolution", cfg.mediaResolution)
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", cfg.voiceName)
                            }
                        }
                        if (!cfg.speechLanguage.isNullOrBlank()) {
                            put("languageCode", cfg.speechLanguage)
                        }
                    }
                }

                if (cfg.inputTranscription.enabled) {
                    putJsonObject("inputAudioTranscription") {
                        if (cfg.inputTranscription.languageCodes.isNotEmpty()) {
                            putJsonArray("languageCodes") {
                                cfg.inputTranscription.languageCodes.forEach { add(it) }
                            }
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
                        if (cfg.outputTranscription.languageCodes.isNotEmpty()) {
                            putJsonArray("languageCodes") {
                                cfg.outputTranscription.languageCodes.forEach { add(it) }
                            }
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
                    putJsonObject("contextWindowCompression") {
                        putJsonObject("slidingWindow") {
                            if (cfg.compression.triggerTokens > 0 &&
                                cfg.compression.targetTokens > 0 &&
                                cfg.compression.triggerTokens > cfg.compression.targetTokens) {
                                put("triggerTokens", cfg.compression.triggerTokens)
                                put("targetTokens", cfg.compression.targetTokens)
                            }
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
                            addJsonObject { put("text", cfg.systemInstruction) }
                        }
                    }
                }

                val allTools = buildJsonArray {
                    if (cfg.toolsJson != null && cfg.toolsJson.isNotEmpty()) {
                        cfg.toolsJson.forEach { add(it) }
                    }
                    if (cfg.enableGoogleSearch) {
                        addJsonObject { putJsonObject("googleSearch") {} }
                    }
                }

                if (allTools.isNotEmpty()) {
                    put("tools", allTools)
                }

                if (cfg.sessionResumptionEnabled) {
                    cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let { handle ->
                        putJsonObject("sessionResumption") { put("handle", handle) }
                    }
                }

                if (cfg.initialHistory.isNotEmpty()) {
                    putJsonObject("historyConfig") {
                        put("initialHistoryInClientContent", true)
                    }
                }
            }
        }.toString()
    }

    private fun parseServerJsonMessage(rawJson: String, myEpoch: Long) {
        try {
            val root = json.parseToJsonElement(rawJson).jsonObject

            if (root.containsKey("setupComplete")) {
                logManager.i("GeminiLive", "Сессия 3.8 готова: setupComplete получен")
                val history = activeConfig?.initialHistory
                if (!history.isNullOrEmpty()) {
                    val bounded = history.takeLast(MAX_INITIAL_HISTORY_TURNS)
                    sendClientContentInternal(bounded, turnComplete = true)
                }
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
            }

            root["usageMetadata"]?.jsonObject?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.let {
                _events.tryEmit(GeminiEvent.Usage(it))
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeftMs = goAway["timeLeft"]?.jsonPrimitive?.contentOrNull?.let { str ->
                    if (str.endsWith("s")) {
                        str.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }
                    } else {
                        str.toLongOrNull()
                    }
                } ?: goAway["timeLeftMs"]?.jsonPrimitive?.longOrNull ?: 10000L

                logManager.w("GeminiLive", "Получен сигнал GoAway: осталось $timeLeftMs мс")
                _events.tryEmit(GeminiEvent.GoAway(timeLeftMs))
            }

            root["sessionResumptionUpdate"]?.jsonObject?.let { sru ->
                val handle = sru["newHandle"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val resumable = sru["resumable"]?.jsonPrimitive?.booleanOrNull ?: true
                if (resumable && handle.isNotBlank()) {
                    _events.tryEmit(GeminiEvent.ResumptionHandle(handle))
                }
            }

            root["toolCall"]?.jsonObject?.let { tc ->
                val calls = tc["functionCalls"]?.jsonArray?.mapNotNull { fcEl ->
                    val fc = fcEl.jsonObject
                    val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val id = fc["id"]?.jsonPrimitive?.contentOrNull
                    val argsObj = fc["args"]?.jsonObject ?: buildJsonObject {}
                    FunctionCall(name, id, argsObj)
                } ?: emptyList()

                if (calls.isNotEmpty()) {
                    _events.tryEmit(GeminiEvent.ToolCall(calls))
                }
            }

            root["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray?.let { idsArr ->
                val ids = idsArr.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (ids.isNotEmpty()) {
                    _events.tryEmit(GeminiEvent.ToolCallCancelled(ids))
                }
            }

            root["serverContent"]?.jsonObject?.let { sc ->
                val interrupted = sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true
                if (interrupted) {
                    _events.tryEmit(GeminiEvent.Interrupted)
                    return
                }

                if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                    _events.tryEmit(GeminiEvent.GenerationComplete)
                }

                if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                    _events.tryEmit(GeminiEvent.TurnComplete)
                }

                sc["interimInputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank()) _events.tryEmit(GeminiEvent.InputTranscript(it, interim = true))
                }

                sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank()) _events.tryEmit(GeminiEvent.InputTranscript(it, interim = false))
                }

                sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull?.let {
                    if (it.isNotBlank()) _events.tryEmit(GeminiEvent.OutputTranscript(it))
                }

                sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partEl ->
                    val part = partEl.jsonObject
                    val isThought = part["thought"]?.jsonPrimitive?.booleanOrNull == true

                    if (!isThought) {
                        part["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            if (text.isNotBlank()) _events.tryEmit(GeminiEvent.ModelText(text))
                        }
                    }

                    part["inlineData"]?.jsonObject?.let { inline ->
                        val mime = inline["mimeType"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        val dataB64 = inline["data"]?.jsonPrimitive?.contentOrNull.orEmpty()

                        if (mime.startsWith("audio/pcm") && dataB64.isNotEmpty()) {
                            val pcmBytes = Base64.decode(dataB64, Base64.NO_WRAP)
                            val currentGen = audioGenerationGen.get()
                            val newBacklog = queuedAudioBytes.addAndGet(pcmBytes.size.toLong())

                            if (newBacklog > MAX_AI_AUDIO_BACKLOG_BYTES) {
                                queuedAudioBytes.addAndGet(-pcmBytes.size.toLong())
                                logManager.w("AudioStream", "Превышен лимит бэклога ($newBacklog > $MAX_AI_AUDIO_BACKLOG_BYTES).")
                                val recoveryGen = invalidateAudio()
                                nativeBridge.flushPlayback(recoveryGen)
                                return
                            }

                            val sendResult = _audio.trySend(AudioFrame(pcmBytes, myEpoch, currentGen))
                            if (sendResult.isFailure) {
                                releaseAudio(pcmBytes.size)
                                return
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logManager.e("GeminiLive:Parse", "Ошибка разбора входящего кадра: ${e.message}", e)
        }
    }

    private fun closeInternal() {
        val ws = webSocket
        webSocket = null
        isReady = false
        activeConfig = null

        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        stopAudioWriter()
        invalidateAudio()

        runCatching { ws?.close(1000, "close") }
        runCatching { ws?.cancel() }
    }

    suspend fun disconnect() = wsMutex.withLock {
        epoch = epochGen.incrementAndGet()
        invalidateAudio()
        logManager.w("WebSocket", "Отключение сессии (эпоха=$epoch)")
        closeInternal()
    }
}