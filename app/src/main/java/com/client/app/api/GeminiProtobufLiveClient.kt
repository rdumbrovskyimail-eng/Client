package com.client.app.api

import android.util.Base64
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
        
        private const val MAX_QUEUE_BYTES = 256L * 1024
        private const val AUDIO_BATCH_THRESHOLD_BYTES = 1280 // 40 мс @ 16 кГц PCM16
        private const val MAX_INITIAL_HISTORY_TURNS = 20
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
    @Volatile private var webSocket: WebSocket? = null

    private val epochGen = AtomicLong(0)
    @Volatile var epoch: Long = 0L; private set

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0, extraBufferCapacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<GeminiEvent> = _events.asSharedFlow()

    private val _audio = Channel<AudioFrame>(1024, BufferOverflow.DROP_OLDEST)
    val audio: ReceiveChannel<AudioFrame> = _audio

    @Volatile var isReady: Boolean = false; private set
    @Volatile private var activeConfig: LiveConfig? = null

    private val audioBatchBuffer = ByteArrayOutputStream(AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()

    private var isAudioStreamEnded = true

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()
        isReady = false
        activeConfig = cfg
        synchronized(batchLock) {
            isAudioStreamEnded = true
            audioBatchBuffer.reset()
        }

        while (_audio.tryReceive().isSuccess) { }

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        val rawKey = cfg.apiKey.trim()
        val encodedKey = URLEncoder.encode(rawKey, "UTF-8")
        val url = "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        logManager.net("WebSocket", "Инициализация Bidi сессии Gemini 3.8 Live (epoch=$myEpoch, key=[REDACTED])")

        val req = Request.Builder()
            .url(url)
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myEpoch != epoch) { ws.close(1000, "stale"); return }
                logManager.net("WebSocket:Open", "Соединение открыто! HTTP ${response.code} ${response.message}")
                _events.tryEmit(GeminiEvent.Connected)

                val setupMsg = buildSetupMessage(cfg)
                logManager.net("WebSocket:Tx", "Отправка 3.8 setup сообщения", setupMsg)
                ws.send(setupMsg)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myEpoch == epoch) {
                    val logSummary = if (text.contains("\"audio/pcm")) {
                        "[Аудиочанк ~${text.length} байт]"
                    } else {
                        text
                    }
                    logManager.net("WebSocket:Rx", logSummary)
                    parseServerJsonMessage(text, myEpoch)
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myEpoch == epoch) {
                    logManager.net("WebSocket:RxBinary", "Получено ${bytes.size} байт")
                    parseServerJsonMessage(bytes.utf8(), myEpoch)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                logManager.w("WebSocket:Closing", "Сервер инициировал закрытие: $code / '$reason'")
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                logManager.w("WebSocket:Closed", "Соединение закрыто (code=$code, reason='$reason', epoch=$myEpoch)")
                if (myEpoch != epoch) return
                isReady = false
                _events.tryEmit(GeminiEvent.Disconnected(code, reason, myEpoch))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val httpCode = response?.code
                val errBody = runCatching { response?.body?.string() }.getOrNull()
                logManager.e("WebSocket:Failure", "Сбой сокета (HTTP $httpCode): ${t.localizedMessage}. Ответ: $errBody", t)

                if (myEpoch != epoch) return
                isReady = false
                val fatal = httpCode == 400 || httpCode == 401 || httpCode == 403 || httpCode == 404
                _events.tryEmit(GeminiEvent.Error("Сетевой сбой ($httpCode): ${t.localizedMessage}", fatal))
                _events.tryEmit(GeminiEvent.Disconnected(httpCode ?: 1006, t.message.orEmpty(), myEpoch))
            }
        })
    }

    fun sendAudioPcm(pcm: ByteArray) {
        val ws = webSocket ?: return
        if (!isReady || pcm.isEmpty()) return

        synchronized(batchLock) {
            isAudioStreamEnded = false
            audioBatchBuffer.write(pcm)
            if (audioBatchBuffer.size() >= AUDIO_BATCH_THRESHOLD_BYTES) {
                val payload = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
                sendAudioPayloadLocked(ws, payload)
            }
        }
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        val ws = webSocket ?: return

        synchronized(batchLock) {
            if (isAudioStreamEnded) {
                return
            }
            isAudioStreamEnded = true

            if (audioBatchBuffer.size() > 0) {
                val tailPayload = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
                sendAudioPayloadLocked(ws, tailPayload)
            }

            val jsonMessage = buildJsonObject {
                putJsonObject("realtimeInput") {
                    put("audioStreamEnd", true)
                }
            }.toString()

            logManager.net("WebSocket:AudioStreamEnd", "Сигнал завершения речи audioStreamEnd")
            ws.send(jsonMessage)
        }
    }

    private fun sendAudioPayloadLocked(ws: WebSocket, payload: ByteArray) {
        val currentQueue = ws.queueSize()
        if (currentQueue > MAX_QUEUE_BYTES) {
            logManager.w("WebSocket:Backpressure", "Очередь сокета переполнена ($currentQueue байт > $MAX_QUEUE_BYTES). Пропуск кадра.")
            return
        }

        val base64Data = Base64.encodeToString(payload, Base64.NO_WRAP)
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("mimeType", "audio/pcm;rate=16000")
                    put("data", base64Data)
                }
            }
        }.toString()

        ws.send(jsonMessage)
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

        logManager.net("WebSocket:TxText", "Отправка realtimeInput.text: '$cleanText'", jsonMessage)
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

        logManager.net("WebSocket:TxImage", "Отправка изображения (${jpegBytes.size} байт)")
        ws.send(jsonMessage)
    }

    fun sendActivityStart() {
        val ws = webSocket ?: return
        if (!isReady) return

        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("activityStart") {}
            }
        }.toString()
        ws.send(jsonMessage)
    }

    fun sendActivityEnd() {
        val ws = webSocket ?: return
        if (!isReady) return

        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("activityEnd") {}
            }
        }.toString()
        ws.send(jsonMessage)
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

        logManager.net("WebSocket:TxClientContent", "Отправка clientContent (${turns.size} ходов, turnComplete=$turnComplete)", jsonMessage)
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
                            if (!resp.id.isNullOrBlank()) {
                                put("id", resp.id)
                            }
                            put("name", resp.name)
                            put("response", resp.response)
                            put("scheduling", resp.scheduling.name)

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

        val debugLog = responses.joinToString { "${it.name}(id=${it.id}, sched=${it.scheduling.name})" }
        logManager.net("WebSocket:ToolResp", "Ответы функций: [$debugLog]", jsonMessage)
        ws.send(jsonMessage)
    }

    private fun buildSetupMessage(cfg: LiveConfig): String {
        val rawModelName = cfg.model.trim()
            .removePrefix("publishers/google/models/")
            .removePrefix("models/")
        val cleanModel = "models/$rawModelName"

        val setupObj = buildJsonObject {
            putJsonObject("setup") {
                put("model", cleanModel)
                putJsonObject("generationConfig") {
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

                // Управление сжатием контекста: исключение блока при disabled
                if (cfg.compression.enabled) {
                    putJsonObject("contextWindowCompression") {
                        putJsonObject("slidingWindow") {
                            if (cfg.compression.triggerTokens > 0 && cfg.compression.targetTokens > 0 && 
                                cfg.compression.triggerTokens > cfg.compression.targetTokens) {
                                put("triggerTokens", cfg.compression.triggerTokens)
                                put("targetTokens", cfg.compression.targetTokens)
                            }
                        }
                    }
                }

                // Семантика VAD: при disabled = true отправляется только статус отключения
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
                        addJsonObject {
                            putJsonObject("googleSearch") {}
                        }
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
        }
        return setupObj.toString()
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
                    logManager.net("WebSocket:History", "Первоначальная история (${bounded.size} ходов) внедрена до готовности сессии")
                }

                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
            }

            root["usageMetadata"]?.jsonObject?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.let {
                _events.tryEmit(GeminiEvent.Usage(it))
            }

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeftMs = goAway["timeLeft"]?.jsonPrimitive?.contentOrNull?.let { str ->
                    if (str.endsWith("s")) str.removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() } else str.toLongOrNull()
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
                if (ids.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCallCancelled(ids))
            }

            root["serverContent"]?.jsonObject?.let { sc ->
                if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                    _events.tryEmit(GeminiEvent.Interrupted)
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
                            _audio.trySend(AudioFrame(pcmBytes, myEpoch))
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
        isAudioStreamEnded = true
        synchronized(batchLock) { audioBatchBuffer.reset() }
        runCatching { ws?.close(1000, "close") }
        runCatching { ws?.cancel() }
    }

    suspend fun disconnect() = wsMutex.withLock {
        epoch = epochGen.incrementAndGet()
        logManager.w("WebSocket", "Отключение сессии (эпоха=$epoch)")
        closeInternal()
    }
}