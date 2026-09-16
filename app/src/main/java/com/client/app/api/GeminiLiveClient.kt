package com.client.app.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

enum class FunctionResponseScheduling {
    INTERRUPT,
    WHEN_IDLE,
    SILENT
}

enum class ClientRole(val value: String) {
    USER("user"),
    MODEL("model")
}

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

/**
 * Функция вызова со строго типизированными аргументами в виде JsonObject
 * и опциональным ID согласно wire-спецификации Google Live API.
 */
data class FunctionCall(
    val name: String,
    val id: String?,
    val args: JsonObject
) {
    fun getString(key: String, default: String = ""): String =
        args[key]?.jsonPrimitive?.content ?: default
}

data class FunctionResponsePart(
    val mimeType: String,
    val base64Data: String
)

data class ToolResponse(
    val name: String,
    val id: String?,
    val response: JsonObject,
    val parts: List<FunctionResponsePart> = emptyList(),
    val scheduling: FunctionResponseScheduling = FunctionResponseScheduling.WHEN_IDLE,
    val willContinue: Boolean = false
)

data class ClientTurn(
    val role: ClientRole,
    val text: String
)

class AudioFrame(val pcm: ByteArray, val epoch: Long)

data class LiveConfig(
    val apiKey: String,
    val model: String = "gemini-3.8-live",
    val systemInstruction: String,
    val voiceName: String = "Charon",
    val temperature: Double = 0.5,
    val mediaResolution: String = "MEDIA_RESOLUTION_HIGH",
    val toolsJson: JsonArray? = null,
    val enableGoogleSearch: Boolean = false,
    val resumptionHandle: String? = null,
    val initialHistory: List<ClientTurn> = emptyList()
)

@Singleton
class GeminiLiveClient @Inject constructor(
    private val protobufClient: GeminiProtobufLiveClient
) {
    val events: Flow<GeminiEvent> get() = protobufClient.events
    val audio: ReceiveChannel<AudioFrame> get() = protobufClient.audio
    val isReady: Boolean get() = protobufClient.isReady
    val epoch: Long get() = protobufClient.epoch

    suspend fun connect(cfg: LiveConfig) = protobufClient.connect(cfg)
    fun sendAudio(pcm: ByteArray) = protobufClient.sendAudioPcm(pcm)
    fun sendRealtimeText(text: String) = protobufClient.sendRealtimeText(text)
    fun sendRealtimeImage(jpegBytes: ByteArray) = protobufClient.sendRealtimeImage(jpegBytes)
    fun sendActivityStart() = protobufClient.sendActivityStart()
    fun sendActivityEnd() = protobufClient.sendActivityEnd()
    fun sendClientContent(turns: List<ClientTurn>, turnComplete: Boolean = true) =
        protobufClient.sendClientContent(turns, turnComplete)
    fun sendAudioStreamEnd() = protobufClient.sendAudioStreamEnd()
    fun sendToolResponses(responses: List<ToolResponse>) = protobufClient.sendToolResponses(responses)
    suspend fun disconnect() = protobufClient.disconnect()
}