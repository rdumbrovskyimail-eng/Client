// >>> FILE: app/src/main/java/com/client/app/api/GeminiLiveClient.kt
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

    data class ModelText(
        val text: String
    ) : GeminiEvent

    data class InputTranscript(
        val text: String,
        val interim: Boolean
    ) : GeminiEvent

    data class OutputTranscript(
        val text: String
    ) : GeminiEvent

    data class ToolCall(
        val calls: List<FunctionCall>
    ) : GeminiEvent

    data class ToolCallCancelled(
        val ids: List<String>
    ) : GeminiEvent

    data class GoAway(
        val millisLeft: Long
    ) : GeminiEvent

    data class ResumptionHandle(
        val handle: String
    ) : GeminiEvent

    data class Usage(
        val totalTokens: Int
    ) : GeminiEvent

    data class Error(
        val message: String,
        val fatal: Boolean
    ) : GeminiEvent

    data class Disconnected(
        val code: Int,
        val reason: String,
        val epoch: Long
    ) : GeminiEvent
}

data class FunctionCall(
    val name: String,
    val id: String?,
    val args: JsonObject
) {
    fun getString(
        key: String,
        default: String = ""
    ): String =
        args[key]
            ?.jsonPrimitive
            ?.content
            ?: default
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
    val scheduling: FunctionResponseScheduling =
        FunctionResponseScheduling.WHEN_IDLE,
    val willContinue: Boolean = false
)

data class ClientTurn(
    val role: ClientRole,
    val text: String
)

// AUD-005.2
class AudioFrame(
    val pcm: ByteArray,
    val epoch: Long,
    val generation: Long
)

data class TranscriptionSettings(
    val enabled: Boolean = true,
    val languageCodes: List<String> = emptyList(),
    val customVocabulary: List<String> = emptyList(),
    val mode: String = "VERBATIM"
)

data class RealtimeInputSettings(
    val aadEnabled: Boolean = true,
    val startSensitivity: String =
        "START_SENSITIVITY_HIGH",
    val endSensitivity: String =
        "END_SENSITIVITY_LOW",
    val prefixPaddingMs: Int = 60,
    val silenceDurationMs: Int = 600,
    val activityHandling: String =
        "START_OF_ACTIVITY_INTERRUPTS",
    val turnCoverage: String =
        "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"
)

data class CompressionSettings(
    val enabled: Boolean = true,
    val triggerTokens: Int = 0,
    val targetTokens: Int = 0
)

data class LiveConfig(
    val apiKey: String,
    val model: String = "gemini-3.8-live",
    val systemInstruction: String,
    val voiceName: String = "Charon",
    val speechLanguage: String? = null,
    val temperature: Float = 0.5f,
    val mediaResolution: String =
        "MEDIA_RESOLUTION_HIGH",
    val inputTranscription:
        TranscriptionSettings =
        TranscriptionSettings(),
    val outputTranscription:
        TranscriptionSettings =
        TranscriptionSettings(),
    val realtimeInput:
        RealtimeInputSettings =
        RealtimeInputSettings(),
    val compression:
        CompressionSettings =
        CompressionSettings(),
    val sessionResumptionEnabled: Boolean =
        true,
    val resumptionHandle: String? = null,
    val toolsJson: JsonArray? = null,
    val enableGoogleSearch: Boolean = false,
    val initialHistory: List<ClientTurn> =
        emptyList()
)

@Singleton
class GeminiLiveClient @Inject constructor(
    private val protobufClient:
        GeminiProtobufLiveClient
) {

    val events: Flow<GeminiEvent>
        get() = protobufClient.events

    val audio: ReceiveChannel<AudioFrame>
        get() = protobufClient.audio

    val isReady: Boolean
        get() = protobufClient.isReady

    val epoch: Long
        get() = protobufClient.epoch

    val audioGeneration: Long
        get() = protobufClient.audioGeneration

    suspend fun connect(
        cfg: LiveConfig
    ) =
        protobufClient.connect(cfg)

    // AUD-067
    suspend fun sendAudio(
        pcm: ByteArray
    ) =
        protobufClient.sendAudioPcm(pcm)

    fun sendRealtimeText(
        text: String
    ) =
        protobufClient.sendRealtimeText(text)

    fun sendRealtimeImage(
        jpegBytes: ByteArray
    ) =
        protobufClient.sendRealtimeImage(jpegBytes)

    // AUD-067
    suspend fun sendActivityStart() =
        protobufClient.sendActivityStart()

    // AUD-067
    suspend fun sendActivityEnd() =
        protobufClient.sendActivityEnd()

    fun sendClientContent(
        turns: List<ClientTurn>,
        turnComplete: Boolean = true
    ) =
        protobufClient.sendClientContent(
            turns,
            turnComplete
        )

    // AUD-067
    suspend fun sendAudioStreamEnd() =
        protobufClient.sendAudioStreamEnd()

    fun sendToolResponses(
        responses: List<ToolResponse>
    ) =
        protobufClient.sendToolResponses(
            responses
        )

    // AUD-005.3
    fun invalidateAudio(): Long =
        protobufClient.invalidateAudio()

    fun releaseAudio(
        bytes: Int
    ) =
        protobufClient.releaseAudio(bytes)

    suspend fun disconnect() =
        protobufClient.disconnect()
}