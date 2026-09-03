// >>> FILE: app/src/main/java/com/client/app/api/GeminiLiveClient.kt
package com.client.app.api

import android.util.Base64
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/* ═══════════════════════════════════════════════════════════════════════════
 *  СОБЫТИЯ LIVE API
 * ═══════════════════════════════════════════════════════════════════════════ */

sealed interface GeminiEvent {
    data object Connected : GeminiEvent
    data object SetupComplete : GeminiEvent
    data object Interrupted : GeminiEvent
    data object GenerationComplete : GeminiEvent
    data object TurnComplete : GeminiEvent
    data class ModelText(val text: String) : GeminiEvent
    data class InputTranscript(val text: String, val interim: Boolean) : GeminiEvent
    data class OutputTranscript(val text: String) : GeminiEvent
    data class ToolCall(val calls: List<FunctionCall>) : GeminiEvent
    data class ToolCallCancelled(val ids: List<String>) : GeminiEvent
    data class GoAway(val millisLeft: Long) : GeminiEvent
    data class ResumptionHandle(val handle: String) : GeminiEvent
    data class Usage(val totalTokens: Int) : GeminiEvent
    data class Error(val message: String, val fatal: Boolean) : GeminiEvent
    data class Disconnected(val code: Int, val reason: String, val epoch: Long) : GeminiEvent
}

data class FunctionCall(val name: String, val id: String, val args: Map<String, String>)
data class ToolResponse(val name: String, val id: String, val resultJson: String)

/** Кадр PCM 24 кГц с защитой от устаревших сессий через epoch */
class AudioFrame(val pcm: ByteArray, val epoch: Long)

enum class ThinkingLevel(val wire: String) {
    MINIMAL("minimal"), LOW("low"), MEDIUM("medium"), HIGH("high")
}

data class LiveConfig(
    val apiKey: String,
    val model: String,
    val systemInstruction: String,
    val voiceName: String = "Charon",
    val thinkingLevel: ThinkingLevel = ThinkingLevel.MINIMAL,
    val temperature: Double = 0.5,
    val toolsJson: JsonArray? = null,
    val resumptionHandle: String? = null,
    val transcriptionLanguages: List<String> = emptyList(),
    val customVocabulary: List<String> = emptyList(),
    val initialHistory: List<Pair<String, String>> = emptyList(),
    val silenceDurationMs: Int = 600,
    val prefixPaddingMs: Int = 60,
    val conservative: Boolean = false
)

@Singleton
class GeminiLiveClient @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        /** Порог очереди: 64 КБ (~2 сек звука). Исключает говорение в пустоту при обрыве сети */
        private const val MAX_QUEUE_BYTES = 64L * 1024
        private const val MAX_HISTORY_TURNS = 24
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
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
    @Volatile var droppedAudioFrames: Long = 0L; private set

    /* ─────────────────────────── ПОДКЛЮЧЕНИЕ ─────────────────────────── */

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()
        isReady = false
        seededHistory = false
        
        while (_audio.tryReceive().isSuccess) { /* drain */ }

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        val url = "wss://$WS_HOST/$WS_PATH?key=${cfg.apiKey.trim()}"
        val req = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myEpoch != epoch) { ws.close(1000, "stale"); return }
                _events.tryEmit(GeminiEvent.Connected)
                ws.send(buildSetup(cfg).toString())
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (myEpoch == epoch) parseMessage(text, myEpoch, cfg)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myEpoch == epoch) parseMessage(bytes.utf8(), myEpoch, cfg)
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
                _events.tryEmit(GeminiEvent.Error(describeFailure(http, t), fatal))
                _events.tryEmit(
                    GeminiEvent.Disconnected(http ?: 1006, t.message.orEmpty(), myEpoch)
                )
            }
        })
    }

    private fun describeFailure(http: Int?, t: Throwable): String = when (http) {
        400 -> "400: некорректный setup — проверьте имя модели"
        401, 403 -> "403: Gemini API Key недействителен или без доступа к Live API"
        429 -> "429: превышена квота Gemini API"
        503 -> "503: модель перегружена, повторите позже"
        else -> t.localizedMessage ?: "Сбой сети"
    }

    fun explainCloseCode(code: Int): String? = when (code) {
        1007 -> "1007: сервер отклонил формат сообщения (срабатывает автооткат)"
        1008 -> "1008: превышен лимит или нарушение политики"
        1011 -> null
        else -> null
    }

    /* ────────────────────────────── SETUP ────────────────────────────── */

    private fun buildSetup(cfg: LiveConfig): JsonObject {
        val modelPath = if (cfg.model.startsWith("models/")) cfg.model else "models/${cfg.model}"
        return buildJsonObject {
            put("setup", buildJsonObject {
                put("model", modelPath)

                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                    put("temperature", cfg.temperature)
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", cfg.voiceName)
                            })
                        })
                    })
                    if (!cfg.conservative) {
                        put("thinkingConfig", buildJsonObject {
                            put("thinkingLevel", cfg.thinkingLevel.wire)
                        })
                        put("mediaResolution", "MEDIA_RESOLUTION_HIGH")
                    }
                })

                put("inputAudioTranscription", buildJsonObject {
                    if (!cfg.conservative && cfg.transcriptionLanguages.isNotEmpty()) {
                        put("languageCodes", buildJsonArray {
                            cfg.transcriptionLanguages.forEach { add(JsonPrimitive(it)) }
                        })
                    }
                    if (!cfg.conservative && cfg.customVocabulary.isNotEmpty()) {
                        put("customVocabulary", buildJsonArray {
                            cfg.customVocabulary.take(200).forEach { add(JsonPrimitive(it)) }
                        })
                    }
                    if (!cfg.conservative) put("mode", "SMART")
                })
                put("outputAudioTranscription", buildJsonObject {
                    if (!cfg.conservative) put("mode", "SMART")
                })

                if (cfg.systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", cfg.systemInstruction) })
                        })
                    })
                }

                if (cfg.toolsJson != null && cfg.toolsJson.isNotEmpty()) {
                    put("tools", cfg.toolsJson)
                }

                put("realtimeInputConfig", buildJsonObject {
                    put("automaticActivityDetection", buildJsonObject {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                        put("prefixPaddingMs", cfg.prefixPaddingMs)
                        put("silenceDurationMs", cfg.silenceDurationMs)
                    })
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                })

                // Стандартные механизмы управления сессией (всегда включены)
                put("contextWindowCompression", buildJsonObject {
                    put("triggerTokens", 48000L)
                    put("slidingWindow", buildJsonObject { put("targetTokens", 16000L) })
                })

                put("sessionResumption", buildJsonObject {
                    cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let { put("handle", it) }
                })

                if (!cfg.conservative && cfg.initialHistory.isNotEmpty()) {
                    put("historyConfig", buildJsonObject {
                        put("initialHistoryInClientContent", true)
                    })
                }
            })
        }
    }

    /* ──────────────────────── ОТПРАВКА ДАННЫХ ──────────────────────── */

    fun sendAudio(pcm: ByteArray) {
        val ws = webSocket ?: return
        if (!isReady) return
        if (ws.queueSize() > MAX_QUEUE_BYTES) { droppedAudioFrames++; return }
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws.send("""{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=16000"}}}""")
    }

    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!isReady || text.isBlank()) return
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject { put("text", text) })
        }
        ws.send(msg.toString())
    }

    fun seedHistory(turns: List<Pair<String, String>>) {
        val ws = webSocket ?: return
        if (seededHistory || turns.isEmpty()) return
        seededHistory = true
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turns", buildJsonArray {
                    turns.takeLast(MAX_HISTORY_TURNS).forEach { (role, text) ->
                        add(buildJsonObject {
                            put("role", if (role == "model") "model" else "user")
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", text) })
                            })
                        })
                    }
                })
                // turnComplete = true: завершает этап initialHistoryInClientContent и открывает прием realtimeInput
                put("turnComplete", true)
            })
        }
        ws.send(msg.toString())
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        webSocket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
    }

    fun finalizeTurnNow() = sendAudioStreamEnd()

    fun sendToolResponses(responses: List<ToolResponse>) {
        val ws = webSocket ?: return
        val payload = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    responses.forEach { resp ->
                        add(buildJsonObject {
                            put("name", resp.name)
                            put("id", resp.id)
                            put("response", runCatching {
                                json.parseToJsonElement(resp.resultJson).jsonObject
                            }.getOrElse {
                                buildJsonObject { put("output", resp.resultJson) }
                            })
                        })
                    }
                })
            })
        }
        ws.send(payload.toString())
    }

    /* ──────────────────────────── ПАРСИНГ ──────────────────────────── */

    private fun parseMessage(raw: String, myEpoch: Long, cfg: LiveConfig) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
                // Затравка истории вызывается только если флаг historyConfig был передан в setup
                if (!cfg.conservative && cfg.initialHistory.isNotEmpty()) {
                    seedHistory(cfg.initialHistory)
                }
            }

            root["usageMetadata"]?.jsonObject
                ?.get("totalTokenCount")?.jsonPrimitive?.intOrNull
                ?.let { _events.tryEmit(GeminiEvent.Usage(it)) }

            root["goAway"]?.jsonObject?.let { ga ->
                val left = ga["timeLeft"]?.jsonPrimitive?.content
                _events.tryEmit(GeminiEvent.GoAway(parseProtoDurationMs(left)))
            }

            root["sessionResumptionUpdate"]?.jsonObject?.let { sru ->
                val resumable = sru["resumable"]?.jsonPrimitive?.booleanOrNull == true
                val handle = sru["newHandle"]?.jsonPrimitive?.content
                if (resumable && !handle.isNullOrBlank()) {
                    _events.tryEmit(GeminiEvent.ResumptionHandle(handle))
                }
            }

            root["toolCallCancellation"]?.jsonObject
                ?.get("ids")?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.let { if (it.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCallCancelled(it)) }

            root["toolCall"]?.jsonObject?.let { tc ->
                val fcs = tc["functionCalls"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    val args = mutableMapOf<String, String>()
                    o["args"]?.jsonObject?.forEach { (k, v) ->
                        args[k] = if (v is JsonPrimitive) v.content else v.toString()
                    }
                    FunctionCall(
                        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                        id = o["id"]?.jsonPrimitive?.content.orEmpty(),
                        args = args
                    )
                } ?: emptyList()
                if (fcs.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCall(fcs))
            }

            emitTranscripts(root)

            val sc = root["serverContent"]?.jsonObject ?: return
            emitTranscripts(sc)

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.Interrupted)
            }
            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.GenerationComplete)
            }
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                val o = part.jsonObject
                if (o["thought"]?.jsonPrimitive?.booleanOrNull == true) return@forEach
                o["text"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { _events.tryEmit(GeminiEvent.ModelText(it)) }
                o["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            _audio.trySend(AudioFrame(pcm, myEpoch))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Live parse error", e)
        }
    }

    private fun emitTranscripts(obj: JsonObject) {
        obj["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { _events.tryEmit(GeminiEvent.InputTranscript(it, interim = false)) }

        obj["interimInputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { _events.tryEmit(GeminiEvent.InputTranscript(it, interim = true)) }

        obj["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
            ?.takeIf { it.isNotBlank() }
            ?.let { _events.tryEmit(GeminiEvent.OutputTranscript(it)) }
    }

    private fun parseProtoDurationMs(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        val sec = raw.removeSuffix("s").toDoubleOrNull() ?: return 0L
        return (sec * 1000).toLong()
    }

    /* ───────────────────────────── ЗАКРЫТИЕ ───────────────────────────── */

    private fun closeInternal() {
        val ws = webSocket
        webSocket = null
        isReady = false
        runCatching { ws?.close(1000, "client close") }
        runCatching { ws?.cancel() }
    }

    suspend fun disconnect() = wsMutex.withLock {
        epoch = epochGen.incrementAndGet()
        closeInternal()
    }
}