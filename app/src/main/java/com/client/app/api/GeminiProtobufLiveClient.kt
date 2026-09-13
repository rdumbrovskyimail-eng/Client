// >>> FILE: app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt
package com.client.app.api

import android.util.Base64
import com.client.app.audio.NativeAudioBridge
import com.client.app.util.AppLogger
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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.SocketFactory

@Singleton
class GeminiProtobufLiveClient @Inject constructor(
    private val nativeBridge: NativeAudioBridge,
    private val logger: AppLogger
) {
    companion object {
        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val MAX_QUEUE_BYTES = 64L * 1024
        private const val AUDIO_BATCH_THRESHOLD_BYTES = 1280 // 40 мс @ 16 кГц (640 сэмплов)
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .socketFactory(TunedSocketFactory(SocketFactory.getDefault(), nativeBridge, logger))
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

    private val audioBatchBuffer = ByteArrayOutputStream(AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()
        isReady = false

        while (_audio.tryReceive().isSuccess) { /* сброс старых фреймов */ }

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        val encodedKey = URLEncoder.encode(cfg.apiKey.trim(), "UTF-8")
        val url = "wss://$WS_HOST/$WS_PATH?key=$encodedKey"

        val req = Request.Builder()
            .url(url)
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myEpoch != epoch) { ws.close(1000, "stale"); return }
                _events.tryEmit(GeminiEvent.Connected)
                ws.send(buildSetupMessage(cfg))
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myEpoch == epoch) parseServerJsonMessage(text, myEpoch)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myEpoch == epoch) parseServerJsonMessage(bytes.utf8(), myEpoch)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (myEpoch != epoch) return
                isReady = false
                _events.tryEmit(GeminiEvent.Disconnected(code, reason, myEpoch))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (myEpoch != epoch) return
                isReady = false
                val http = response?.code
                val fatal = http == 400 || http == 401 || http == 403
                _events.tryEmit(GeminiEvent.Error("Сетевой сбой ($http): ${t.localizedMessage}", fatal))
                _events.tryEmit(GeminiEvent.Disconnected(http ?: 1006, t.message.orEmpty(), myEpoch))
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

    // Ошибка №23 [NET/PROTO]: Передача медиа-чанков строго через mediaChunks с объектом Blob
    private fun sendAudioPayload(ws: WebSocket, payload: ByteArray) {
        if (ws.queueSize() > MAX_QUEUE_BYTES) {
            logger.w("GeminiProtobufLiveClient: Очередь сокета переполнена (${ws.queueSize()} байт), пропуск блока для исключения задержки")
            return
        }

        val base64Data = Base64.encodeToString(payload, Base64.NO_WRAP)
        val jsonMessage = buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonArray("mediaChunks") {
                    addJsonObject {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Data)
                    }
                }
            }
        }.toString()

        ws.send(jsonMessage)
    }

    // Ошибка №24 [NET/PROTO]: Передача текста через clientContent с массивом turns и флагом turnComplete
    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!isReady || text.isBlank()) return

        val jsonMessage = buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") {
                            addJsonObject { put("text", text) }
                        }
                    }
                }
                put("turnComplete", true)
            }
        }.toString()

        ws.send(jsonMessage)
    }

    // ERR-10 & Ошибка №25 [NET/PROTO]: Гарантированная выгрузка хвоста речи и сигнал turnComplete
    fun sendAudioStreamEnd() {
        if (!isReady) return
        val ws = webSocket ?: return

        // 1. Извлекаем и отправляем задержанный хвост речи (от 10 до 30 мс звука)
        var tailPayload: ByteArray? = null
        synchronized(batchLock) {
            if (audioBatchBuffer.size() > 0) {
                tailPayload = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
            }
        }

        tailPayload?.let { sendAudioPayload(ws, it) }

        // 2. Отправляем признак завершения речевого хода модели через clientContent.turnComplete
        val jsonMessage = buildJsonObject {
            putJsonObject("clientContent") {
                put("turnComplete", true)
            }
        }.toString()

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

        ws.send(jsonMessage)
    }

    // E-29 & Ошибка №26 [NET/PROTO]: Исключение googleSearch из Live сетапа
    private fun buildSetupMessage(cfg: LiveConfig): String {
        val cleanModel = if (cfg.model.startsWith("models/")) cfg.model else "models/${cfg.model}"

        val setupObj = buildJsonObject {
            putJsonObject("setup") {
                put("model", cleanModel)

                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
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

                // Ошибка №26 [NET/PROTO]: Live API поддерживает исключительно functionDeclarations
                if (cfg.toolsJson != null && cfg.toolsJson.isNotEmpty()) {
                    putJsonArray("tools") {
                        cfg.toolsJson.forEach { add(it) }
                    }
                }

                cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let { handle ->
                    putJsonObject("sessionResumption") { put("handle", handle) }
                }

                putJsonArray("safetySettings") {
                    listOf(
                        "HARM_CATEGORY_HARASSMENT",
                        "HARM_CATEGORY_HATE_SPEECH",
                        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                        "HARM_CATEGORY_DANGEROUS_CONTENT"
                    ).forEach { cat ->
                        addJsonObject {
                            put("category", cat)
                            put("threshold", "BLOCK_ONLY_HIGH")
                        }
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

                if (calls.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCall(calls))
            }

            root["toolCallCancellation"]?.jsonObject?.get("ids")?.jsonArray?.let { idsArr ->
                val ids = idsArr.mapNotNull { it.jsonPrimitive.contentOrNull }
                if (ids.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCallCancelled(ids))
            }

            root["serverContent"]?.jsonObject?.let { sc ->
                if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) _events.tryEmit(GeminiEvent.Interrupted)
                if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) _events.tryEmit(GeminiEvent.GenerationComplete)
                if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) _events.tryEmit(GeminiEvent.TurnComplete)

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
            logger.e("GeminiLiveClient: Ошибка разбора сообщения", e)
        }
    }

    private fun closeInternal() {
        val ws = webSocket
        webSocket = null
        isReady = false
        synchronized(batchLock) { audioBatchBuffer.reset() }
        runCatching { ws?.close(1000, "close") }
        runCatching { ws?.cancel() }
    }

    suspend fun disconnect() = wsMutex.withLock {
        epoch = epochGen.incrementAndGet()
        closeInternal()
    }
}