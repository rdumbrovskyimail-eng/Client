package com.client.app.api

import android.util.Base64
import com.client.app.util.AppLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GeminiEvent {
    object Connected : GeminiEvent
    object SetupComplete : GeminiEvent
    object Interrupted : GeminiEvent
    object TurnComplete : GeminiEvent
    data class AudioChunk(val pcm: ByteArray) : GeminiEvent
    data class ModelText(val text: String) : GeminiEvent
    data class InputTranscript(val text: String) : GeminiEvent
    data class OutputTranscript(val text: String) : GeminiEvent
    data class ToolCall(val calls: List<FunctionCall>) : GeminiEvent
    data class Error(val message: String) : GeminiEvent
    data class Disconnected(val code: Int, val reason: String) : GeminiEvent
}

data class FunctionCall(val name: String, val id: String, val args: Map<String, String>)
data class ToolResponse(val name: String, val id: String, val resultJson: String)

@Singleton
class GeminiLiveClient @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        const val WS_HOST = "generativelanguage.googleapis.com"
        // Официальный эндпоинт v1beta для Gemini 3.1 Live
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val httpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wsMutex = Mutex()
    @Volatile private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: Flow<GeminiEvent> = _events.asSharedFlow()

    @Volatile var isReady: Boolean = false; private set

    suspend fun connect(
        apiKey: String,
        model: String,
        systemInstruction: String,
        toolsJson: JsonArray? = null
    ) = wsMutex.withLock {
        disconnectInternal()
        isReady = false

        val url = "wss://$WS_HOST/$WS_PATH?key=${apiKey.trim()}"
        val req = Request.Builder().url(url).build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                _events.tryEmit(GeminiEvent.Connected)
                sendSetup(model, systemInstruction, toolsJson)
            }

            override fun onMessage(ws: WebSocket, text: String) = parseMessage(text)
            override fun onMessage(ws: WebSocket, bytes: ByteString) = parseMessage(bytes.utf8())

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                isReady = false
                _events.tryEmit(GeminiEvent.Disconnected(code, reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isReady = false
                val code = response?.code
                val err = if (code == 403) "Ошибка 403: Проверьте Gemini API Key" else (t.localizedMessage ?: "Сбой сети")
                _events.tryEmit(GeminiEvent.Error(err))
                _events.tryEmit(GeminiEvent.Disconnected(code ?: 1006, t.message.orEmpty()))
            }
        })
    }

    private fun sendSetup(model: String, systemInstruction: String, toolsJson: JsonArray?) {
        val modelPath = if (model.startsWith("models/")) model else "models/$model"
        val setupPayload = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", modelPath)
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", "Charon") // Фиксированный научный/академический голос
                            })
                        })
                    })
                    // Минимальное время размышления для голосового диалога без задержки
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", "minimal")
                    })
                    put("temperature", 0.5)
                })

                put("inputAudioTranscription", buildJsonObject {})
                put("outputAudioTranscription", buildJsonObject {})

                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", systemInstruction) })
                        })
                    })
                }

                if (toolsJson != null && toolsJson.isNotEmpty()) {
                    put("tools", toolsJson)
                }

                put("realtimeInputConfig", buildJsonObject {
                    put("automaticActivityDetection", buildJsonObject {
                        put("disabled", false)
                        put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        put("prefixPaddingMs", 100)
                        put("silenceDurationMs", 450)
                    })
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                })
            })
        }
        webSocket?.send(setupPayload.toString())
    }

    /**
     * СТРОГО ПО СПЕЦИФИКАЦИИ GEMINI 3.1:
     * realtimeInput.audio (вместо устаревшего mediaChunks, вызывающего 1007 Close Code)
     */
    fun sendAudio(pcm: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        val msg = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=16000"}}}"""
        webSocket?.send(msg)
    }

    fun sendClientTurn(text: String, imagesJpeg: List<ByteArray> = emptyList()) {
        if (!isReady) return
        scope.launch {
            val msg = buildJsonObject {
                put("clientContent", buildJsonObject {
                    put("turns", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray {
                                imagesJpeg.forEach { bytes ->
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", "image/jpeg")
                                            put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                                        })
                                    })
                                }
                                if (text.isNotBlank()) {
                                    add(buildJsonObject { put("text", text) })
                                }
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            webSocket?.send(msg.toString())
        }
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        webSocket?.send("""{"realtimeInput":{"audioStreamEnd":true}}""")
    }

    fun sendToolResponses(responses: List<ToolResponse>) {
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
        webSocket?.send(payload.toString())
    }

    private fun parseMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
            }

            root["toolCall"]?.jsonObject?.let { tc ->
                val fcs = tc["functionCalls"]?.jsonArray?.map { item ->
                    val o = item.jsonObject
                    val name = o["name"]?.jsonPrimitive?.content.orEmpty()
                    val id = o["id"]?.jsonPrimitive?.content.orEmpty()
                    val argsMap = mutableMapOf<String, String>()
                    o["args"]?.jsonObject?.forEach { (k, v) ->
                        argsMap[k] = if (v is JsonPrimitive) v.content else v.toString()
                    }
                    FunctionCall(name, id, argsMap)
                } ?: emptyList()
                if (fcs.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCall(fcs))
            }

            val sc = root["serverContent"]?.jsonObject ?: return

            sc["inputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { _events.tryEmit(GeminiEvent.InputTranscript(it)) }

            sc["outputTranscription"]?.jsonObject?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { _events.tryEmit(GeminiEvent.OutputTranscript(it)) }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.Interrupted)
            }
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            sc["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { part ->
                val o = part.jsonObject
                o["text"]?.jsonPrimitive?.content?.let { _events.tryEmit(GeminiEvent.ModelText(it)) }
                o["inlineData"]?.jsonObject?.let { inline ->
                    if (inline["mimeType"]?.jsonPrimitive?.content?.startsWith("audio/pcm") == true) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            _events.tryEmit(GeminiEvent.AudioChunk(pcm))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Parse error", e)
        }
    }

    private fun disconnectInternal() {
        runCatching { webSocket?.close(1000, "close") }
        webSocket = null
        isReady = false
    }

    suspend fun disconnect() = wsMutex.withLock { disconnectInternal() }
}