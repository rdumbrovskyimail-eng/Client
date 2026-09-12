// >>> FILE: app/src/main/java/com/client/app/api/GeminiLiveClient.kt
package com.client.app.api

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonArray
import javax.inject.Inject
import javax.inject.Singleton

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
class AudioFrame(val pcm: ByteArray, val epoch: Long)

data class LiveConfig(
    val apiKey: String,
    val model: String,
    val systemInstruction: String,
    val voiceName: String = "Charon",
    val temperature: Double = 0.5,
    val toolsJson: JsonArray? = null,
    val resumptionHandle: String? = null,
    val cachedContentId: String? = null,
    val initialHistory: List<Pair<String, String>> = emptyList(),
    val conservative: Boolean = false
)

/**
 * Фасад, прозрачно делегирующий вызовы высокоскоростному клиенту GeminiProtobufLiveClient.
 */
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
    fun sendAudioStreamEnd() = protobufClient.sendAudioStreamEnd()
    fun sendToolResponses(responses: List<ToolResponse>) = protobufClient.sendToolResponses(responses)
    suspend fun disconnect() = protobufClient.disconnect()
}