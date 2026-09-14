// >>> FILE: app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt
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
        
        // Увеличен до 256 КБ: сетевые джиттер-всплески больше не глушат микрофон
        private const val MAX_QUEUE_BYTES = 256L * 1024
        private const val AUDIO_BATCH_THRESHOLD_BYTES = 1280
        private const val MAX_HISTORY_TURNS = 20
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val loggingEventListener = object : EventListener() {
        override fun dnsStart(call: Call, domainName: String) {
            logManager.net("OkHttp:DNS", "Старт резолва: $domainName")
        }

        override fun dnsEnd(call: Call, domainName: String, inetAddressList: List<InetAddress>) {
            logManager.net("OkHttp:DNS", "Резолв успешен: $domainName -> $inetAddressList")
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
    @Volatile private var seededHistory = false
    @Volatile private var activeConfig: LiveConfig? = null

    private val audioBatchBuffer = ByteArrayOutputStream(AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()
        isReady = false
        seededHistory = false
        activeConfig = cfg

        while (_audio.tryReceive().isSuccess) { /* сброс буфера вывода */ }
        synchronized(batchLock) { audioBatchBuffer.reset() }

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        val rawKey = cfg.apiKey.trim()
        val encodedKey = URLEncoder.encode(rawKey, "UTF-8")
        val url = "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        logManager.net("WebSocket", "Инициализация соединения AI Studio (epoch=$myEpoch, model=${cfg.model})")

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
                logManager.net("WebSocket:Tx", "Отправка сообщения setup", setupMsg)
                ws.send(setupMsg)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myEpoch == epoch) {
                    val logSummary = if (text.contains("\"audio/pcm")) {
                        "[Серверный чанк аудио ~${text.length} байт]"
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
                logManager.w("WebSocket:Closing", "Сервер инициировал закрытие сокета: $code / '$reason'")
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

        var toSend: ByteArray? = null
        synchronized(batchLock) {
            audioBatchBuffer.write(pcm)
            if (audioBatchBuffer.size() >= AUDIO_BATCH_THRESHOLD_BYTES) {
                toSend = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
            }
        }

        val payload = toSend ?: return
        sendAudioPayload(ws, payload)
    }

    private fun sendAudioPayload(ws: WebSocket, payload: ByteArray) {
        if (ws.queueSize() > MAX_QUEUE_BYTES) {
            logManager.w("WebSocket:Backpressure", "Очередь переполнена (${ws.queueSize()} байт). Пропуск чанка.")
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

    /**
     * Отправка текстовых команд по спецификации Gemini Live API:
     * 'text' обязан быть скалярной строкой (Scalar String), а не объектом!
     */
    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!isReady || text.isBlank()) return

        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("text", text.trim()) // Строго скалярное значение "text": "привет"
            }
        }.toString()

        logManager.net("WebSocket:TxText", "Отправка текста: '$text'", jsonMessage)
        ws.send(jsonMessage)
    }

    fun seedHistory(turns: List<Pair<String, String>>) {
        val ws = webSocket ?: return
        if (seededHistory || turns.isEmpty()) return
        seededHistory = true

        val jsonMessage = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    turns.takeLast(MAX_HISTORY_TURNS).forEach { (role, text) ->
                        addJsonObject {
                            put("role", if (role == "model") "model" else "user")
                            putJsonArray("parts") {
                                addJsonObject { put("text", text) }
                            }
                        }
                    }
                }
                put("turnComplete", true)
            }
        }.toString()

        logManager.net("WebSocket:TxHistory", "Затравка контекста сессии (${turns.size} шагов)", jsonMessage)
        ws.send(jsonMessage)
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        val ws = webSocket ?: return

        var tailPayload: ByteArray? = null
        synchronized(batchLock) {
            if (audioBatchBuffer.size() > 0) {
                tailPayload = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
            }
        }

        tailPayload?.let { sendAudioPayload(ws, it) }

        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                put("audioStreamEnd", true)
            }
        }.toString()

        logManager.net("WebSocket:AudioStreamEnd", "Сигнал завершения речи audioStreamEnd")
        ws.send(jsonMessage)
    }

    fun sendToolResponses(responses: List<ToolResponse>) {
        val ws = webSocket ?: return
        val jsonMessage = buildJsonObject {
            putJsonObject("toolResponse") {
                putJsonArray("functionResponses") {
                    responses.forEach { resp ->
                        addJsonObject {
                            put("id", resp.id)
                            put("name", resp.name)
                            val responseStruct = runCatching {
                                json.parseToJsonElement(resp.resultJson).jsonObject
                            }.getOrElse {
                                buildJsonObject { put("output", resp.resultJson) }
                            }
                            put("response", responseStruct)
                        }
                    }
                }
            }
        }.toString()

        logManager.net("WebSocket:ToolResp", "Ответы функций", jsonMessage)
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
                    putJsonObject("speechConfig") {
                        putJsonObject("voiceConfig") {
                            putJsonObject("prebuiltVoiceConfig") {
                                put("voiceName", cfg.voiceName)
                            }
                        }
                    }
                }

                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}

                putJsonObject("contextWindowCompression") {
                    putJsonObject("slidingWindow") {}
                }

                putJsonObject("realtimeInputConfig") {
                    putJsonObject("automaticActivityDetection") {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", 60)
                        put("silenceDurationMs", 600)
                    }
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                }

                cfg.cachedContentId?.takeIf { it.isNotBlank() }?.let {
                    put("cachedContent", it)
                }

                if (cfg.systemInstruction.isNotBlank()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            addJsonObject { put("text", cfg.systemInstruction) }
                        }
                    }
                }

                if (cfg.toolsJson != null && cfg.toolsJson.isNotEmpty()) {
                    putJsonArray("tools") {
                        cfg.toolsJson.forEach { add(it) }
                    }
                }

                cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let { handle ->
                    putJsonObject("sessionResumption") { put("handle", handle) }
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
                isReady = true
                logManager.i("GeminiLive", "Сессия полностью готова (setupComplete получен)")
                _events.tryEmit(GeminiEvent.SetupComplete)

                activeConfig?.initialHistory?.takeIf { it.isNotEmpty() }?.let { history ->
                    seedHistory(history)
                }
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
                    val id = fc["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    val argsMap = mutableMapOf<String, String>()
                    fc["args"]?.jsonObject?.forEach { (k, v) ->
                        argsMap[k] = if (v is JsonPrimitive) v.content else v.toString()
                    }
                    FunctionCall(name, id, argsMap)
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
            logManager.e("GeminiLive:Parse", "Ошибка разбора входящего фрейма: ${e.message}", e)
        }
    }

    private fun closeInternal() {
        val ws = webSocket
        webSocket = null
        isReady = false
        activeConfig = null
        synchronized(batchLock) { audioBatchBuffer.reset() }
        runCatching { ws?.close(1000, "close") }
        runCatching { ws?.cancel() }
    }

    suspend fun disconnect() = wsMutex.withLock {
        epoch = epochGen.incrementAndGet()
        logManager.w("WebSocket", "Отключение сессии (новая эпоха=$epoch)")
        closeInternal()
    }
}