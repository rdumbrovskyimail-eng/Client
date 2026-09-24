// >>> FILE: app/src/main/java/com/client/app/api/GeminiLiveClient.kt
package com.client.app.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.*
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

    data class InteractionStatus(
        val status: String
    ) : GeminiEvent

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
        val millisLeft: Long?
    ) : GeminiEvent

    data class ResumptionHandle(
        val handle: String?,
        val resumable: Boolean
    ) : GeminiEvent

    data class GroundingMetadata(
        val metadata: com.client.app.api.GroundingMetadata
    ) : GeminiEvent

    data class UrlContextMetadata(
        val metadata: com.client.app.api.UrlContextMetadata
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
        val sessionId: Long,
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
            ?.contentOrNull
            ?: default

    fun getStringList(
        key: String
    ): List<String> {
        val value = args[key] ?: return emptyList()
        return when (value) {
            is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
            else -> value.jsonPrimitive.contentOrNull
                ?.split(',', ';')
                ?.map { it.trim() }
                ?.filter(String::isNotBlank)
                ?: emptyList()
        }
    }
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
    // Null means the field must be omitted from the wire message.
    val scheduling: FunctionResponseScheduling? = null,
    val willContinue: Boolean = false
)

data class ClientTurn(
    val role: ClientRole,
    val text: String
)

// AUD-005.2
class AudioFrame(
    val pcm: ByteArray,
    val sessionId: Long,
    val epoch: Long,
    val generation: Long,
    val frameId: Long
)

data class GeminiEventEnvelope(
    val sessionId: Long,
    val epoch: Long,
    val frameId: Long,
    val generationId: Long,
    val event: GeminiEvent
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

/**
 * Model-specific Live API contract. The model string alone is not enough to
 * safely serialize setup/tool responses because Gemini Live models expose
 * different thinking, tool-scheduling and interaction-lifecycle semantics.
 */
data class GroundingMetadata(
    val raw: JsonObject
)

data class UrlContextMetadata(
    val raw: JsonObject
)

data class LiveModelCapabilities(
    val supportsThinkingConfig: Boolean,
    val supportsInteractionStatus: Boolean,
    val supportsAsyncFunctionCalling: Boolean,
    val supportsFunctionScheduling: Boolean,
    val supportsContextCache: Boolean,
    val requiresNonBlockingTools: Boolean,
    val supportsSearchGrounding: Boolean,
    val supportsUrlContext: Boolean,
    val maxInputTokens: Int
)

object LiveModelCapabilitiesRegistry {
    private fun normalize(model: String): String =
        model.trim()
            .removePrefix("publishers/google/models/")
            .removePrefix("models/")

    fun normalizeResourceName(model: String): String {
        val id = normalize(model)
        require(id.matches(Regex("[a-zA-Z0-9._-]+"))) {
            "Invalid Gemini model resource name: '$model'"
        }
        return "models/$id"
    }

    fun requireBcp47Language(language: String): String {
        val clean = language.trim()
        require(clean.matches(Regex("^[A-Za-z]{2,3}(?:-[A-Za-z]{2,4})?(?:-[A-Za-z0-9]{2,8})*$"))) {
            "Invalid BCP-47 speech language: '$language'"
        }
        return clean
    }

    fun requireVoiceName(voice: String): String {
        val clean = voice.trim()
        require(clean.matches(Regex("^[A-Za-z][A-Za-z0-9_-]{1,63}$"))) {
            "Invalid Gemini voice name: '$voice'"
        }
        return clean
    }

    fun forModel(model: String): LiveModelCapabilities {
        return when (normalize(model)) {
            "gemini-3.8-live" -> LiveModelCapabilities(
                supportsThinkingConfig = false,
                supportsInteractionStatus = false,
                supportsAsyncFunctionCalling = true,
                supportsFunctionScheduling = true,
                supportsContextCache = false,
                requiresNonBlockingTools = false,
                supportsSearchGrounding = true,
                supportsUrlContext = false,
                maxInputTokens = 131_072
            )
            "gemini-3.8-live-extended-thinking" -> LiveModelCapabilities(
                supportsThinkingConfig = true,
                supportsInteractionStatus = true,
                supportsAsyncFunctionCalling = true,
                supportsFunctionScheduling = false,
                supportsContextCache = false,
                requiresNonBlockingTools = true,
                supportsSearchGrounding = true,
                supportsUrlContext = false,
                maxInputTokens = 131_072
            )
            "gemini-3.1-flash-live-preview" -> LiveModelCapabilities(
                supportsThinkingConfig = true,
                supportsInteractionStatus = false,
                supportsAsyncFunctionCalling = false,
                supportsFunctionScheduling = false,
                supportsContextCache = false,
                requiresNonBlockingTools = false,
                supportsSearchGrounding = false,
                supportsUrlContext = false,
                maxInputTokens = 131_072
            )
            "gemini-2.5-flash-native-audio-preview-12-2025" -> LiveModelCapabilities(
                supportsThinkingConfig = false,
                supportsInteractionStatus = false,
                supportsAsyncFunctionCalling = false,
                supportsFunctionScheduling = false,
                supportsContextCache = false,
                requiresNonBlockingTools = false,
                supportsSearchGrounding = false,
                supportsUrlContext = false,
                maxInputTokens = 131_072
            )
            else -> throw IllegalArgumentException(
                "Unsupported Gemini Live model '${normalize(model)}'. Add it to LiveModelCapabilitiesRegistry only after verifying its official protocol contract."
            )
        }
    }
}

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
    val thinkingLevel: String? = null,
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

    val events: Flow<GeminiEventEnvelope>
        get() = protobufClient.events

    val audio: ReceiveChannel<AudioFrame>
        get() = protobufClient.audio

    val isReady: Boolean
        get() = protobufClient.isReady

    val epoch: Long
        get() = protobufClient.epoch

    val sessionId: Long
        get() = protobufClient.sessionId

    val audioGeneration: Long
        get() = protobufClient.audioGeneration

    suspend fun connect(
        cfg: LiveConfig,
        beforeOpen: (suspend () -> Unit)? = null
    ) =
        protobufClient.connect(
            cfg,
            beforeOpen
        )

    // AUD-067
    suspend fun sendAudio(
        pcm: ByteArray
    ) =
        protobufClient.sendAudioPcm(pcm)

    suspend fun flushAudio() =
        protobufClient.flushAudio()

    suspend fun sendRealtimeText(
        text: String
    ) =
        protobufClient.sendRealtimeText(text)

    suspend fun sendRealtimeImage(
        jpegBytes: ByteArray
    ) =
        protobufClient.sendRealtimeImage(jpegBytes)

    // AUD-067
    suspend fun sendActivityStart() =
        protobufClient.sendActivityStart()

    // AUD-067
    suspend fun sendActivityEnd() =
        protobufClient.sendActivityEnd()

    suspend fun sendClientContent(
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

    suspend fun sendToolResponses(
        responses: List<ToolResponse>
    ): Boolean =
        protobufClient.sendToolResponses(
            responses
        )

    // AUD-005.3
    fun invalidateAudio(): Long =
        protobufClient.invalidateAudio()

    fun releaseAudio(frame: AudioFrame) =
        protobufClient.releaseAudio(frame)

    suspend fun disconnect() =
        protobufClient.disconnect()
}