// >>> FILE: app/src/main/java/com/client/app/api/GeminiProtobufLiveClient.kt
package com.client.app.api

import com.client.app.audio.NativeAudioBridge
import com.client.app.util.AppLogger
import com.google.ai.generativelanguage.v1beta.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream
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

    // Буфер склейки 10-мс квантов в 40-мс батчи (сокращение сериализации в 4 раза)
    private val audioBatchBuffer = ByteArrayOutputStream(AUDIO_BATCH_THRESHOLD_BYTES * 2)
    private val batchLock = Any()

    suspend fun connect(cfg: LiveConfig) = wsMutex.withLock {
        closeInternal()
        isReady = false

        while (_audio.tryReceive().isSuccess) { /* очистка */ }

        val myEpoch = epochGen.incrementAndGet()
        epoch = myEpoch

        // Безопасность OWASP: Ключ передается в заголовке x-goog-api-key, а не в открытом URL query
        val url = "wss://$WS_HOST/$WS_PATH"
        val req = Request.Builder()
            .url(url)
            .header("x-goog-api-key", cfg.apiKey.trim())
            .header("X-Accel-Buffering", "no")
            .header("Cache-Control", "no-cache")
            .build()

        webSocket = httpClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (myEpoch != epoch) { ws.close(1000, "stale"); return }
                _events.tryEmit(GeminiEvent.Connected)
                val setupMessage = buildSetupMessage(cfg)
                ws.send(setupMessage.toByteArray().toByteString())
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (myEpoch == epoch) {
                    parseProtobufMessage(bytes.toByteArray(), myEpoch)
                }
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

    /**
     * Потоковая батчированная передача 40-мс блоков звука без микро-дропов.
     */
    fun sendAudioPcm(pcm: ByteArray) {
        val ws = webSocket ?: return
        if (!isReady) return

        var toSend: ByteArray? = null
        synchronized(batchLock) {
            audioBatchBuffer.write(pcm)
            if (audioBatchBuffer.size() >= AUDIO_BATCH_THRESHOLD_BYTES) {
                toSend = audioBatchBuffer.toByteArray()
                audioBatchBuffer.reset()
            }
        }

        val payload = toSend ?: return

        // Защита от перегрузки очереди: завершаем ход штатно, а не режем синусоиду
        if (ws.queueSize() > MAX_QUEUE_BYTES) {
            sendAudioStreamEnd()
            return
        }

        val blob = Blob.newBuilder()
            .setMimeType("audio/pcm;rate=16000")
            .setData(com.google.protobuf.ByteString.copyFrom(payload))
            .build()

        val realtimeInput = BidiGenerateContentRealtimeInput.newBuilder()
            .addMediaChunks(blob)
            .build()

        val message = BidiGenerateContentClientMessage.newBuilder()
            .setRealtimeInput(realtimeInput)
            .build()

        ws.send(message.toByteArray().toByteString())
    }

    fun sendRealtimeText(text: String) {
        val ws = webSocket ?: return
        if (!isReady || text.isBlank()) return

        val realtimeInput = BidiGenerateContentRealtimeInput.newBuilder()
            .setText(text)
            .build()

        val message = BidiGenerateContentClientMessage.newBuilder()
            .setRealtimeInput(realtimeInput)
            .build()

        ws.send(message.toByteArray().toByteString())
    }

    fun sendAudioStreamEnd() {
        if (!isReady) return
        synchronized(batchLock) { audioBatchBuffer.reset() }

        val realtimeInput = BidiGenerateContentRealtimeInput.newBuilder()
            .setAudioStreamEnd(true)
            .build()

        val message = BidiGenerateContentClientMessage.newBuilder()
            .setRealtimeInput(realtimeInput)
            .build()

        webSocket?.send(message.toByteArray().toByteString())
    }

    fun sendToolResponses(responses: List<ToolResponse>) {
        val ws = webSocket ?: return
        val toolResponseBuilder = BidiGenerateContentToolResponse.newBuilder()

        responses.forEach { resp ->
            val fnResponse = FunctionResponse.newBuilder()
                .setId(resp.id)
                .setName(resp.name)
                .setResponse(resp.resultJson)
                .build()
            toolResponseBuilder.addFunctionResponses(fnResponse)
        }

        val message = BidiGenerateContentClientMessage.newBuilder()
            .setToolResponse(toolResponseBuilder.build())
            .build()

        ws.send(message.toByteArray().toByteString())
    }

    private fun buildSetupMessage(cfg: LiveConfig): BidiGenerateContentClientMessage {
        val modelPath = if (cfg.model.startsWith("models/")) cfg.model else "models/${cfg.model}"

        val generationConfig = GenerationConfig.newBuilder()
            .addResponseModalities("AUDIO")
            .setMaxOutputTokens(65536)
            .setTemperature(cfg.temperature.toFloat())
            .setSpeechConfig(
                SpeechConfig.newBuilder().setVoiceConfig(
                    VoiceConfig.newBuilder().setPrebuiltVoiceConfig(
                        PrebuiltVoiceConfig.newBuilder().setVoiceName(cfg.voiceName).build()
                    ).build()
                ).build()
            )
            .setThinkingConfig(
                ThinkingConfig.newBuilder()
                    .setThinkingLevel("LOW")
                    .setIncludeThoughts(true)
                    .build()
            )
            .build()

        val setupBuilder = BidiGenerateContentSetup.newBuilder()
            .setModel(modelPath)
            .setGenerationConfig(generationConfig)

        cfg.cachedContentId?.takeIf { it.isNotBlank() }?.let {
            setupBuilder.setCachedContent(it)
        }

        if (cfg.systemInstruction.isNotBlank()) {
            setupBuilder.setSystemInstruction(
                Content.newBuilder()
                    .setRole("user")
                    .addParts(Part.newBuilder().setText(cfg.systemInstruction).build())
                    .build()
            )
        }

        setupBuilder.addTools(
            Tool.newBuilder().setGoogleSearch(GoogleSearch.getDefaultInstance()).build()
        )

        cfg.resumptionHandle?.takeIf { it.isNotBlank() }?.let {
            setupBuilder.setSessionResumption(SessionResumption.newBuilder().setHandle(it).build())
        }

        listOf(
            "HARM_CATEGORY_HARASSMENT",
            "HARM_CATEGORY_HATE_SPEECH",
            "HARM_CATEGORY_SEXUALLY_EXPLICIT",
            "HARM_CATEGORY_DANGEROUS_CONTENT"
        ).forEach { cat ->
            setupBuilder.addSafetySettings(
                SafetySetting.newBuilder()
                    .setCategory(cat)
                    .setThreshold("BLOCK_ONLY_HIGH")
                    .build()
            )
        }

        return BidiGenerateContentClientMessage.newBuilder()
            .setSetup(setupBuilder.build())
            .build()
    }

    private fun parseProtobufMessage(rawBytes: ByteArray, myEpoch: Long) {
        try {
            val serverMsg = BidiGenerateContentServerMessage.parseFrom(rawBytes)

            if (serverMsg.hasSetupComplete()) {
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
            }
            if (serverMsg.hasUsageMetadata()) {
                _events.tryEmit(GeminiEvent.Usage(serverMsg.usageMetadata.totalTokenCount))
            }
            if (serverMsg.hasGoAway()) {
                _events.tryEmit(GeminiEvent.GoAway(serverMsg.goAway.timeLeftMs))
            }
            if (serverMsg.hasSessionResumptionUpdate()) {
                val update = serverMsg.sessionResumptionUpdate
                if (update.resumable && update.newHandle.isNotBlank()) {
                    _events.tryEmit(GeminiEvent.ResumptionHandle(update.newHandle))
                }
            }
            if (serverMsg.hasToolCall()) {
                val calls = serverMsg.toolCall.functionCallsList.map { fc ->
                    FunctionCall(fc.name, fc.id, fc.argsMap)
                }
                if (calls.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCall(calls))
            }
            if (serverMsg.hasToolCallCancellation()) {
                val ids = serverMsg.toolCallCancellation.idsList
                if (ids.isNotEmpty()) _events.tryEmit(GeminiEvent.ToolCallCancelled(ids))
            }
            if (serverMsg.hasServerContent()) {
                val sc = serverMsg.serverContent
                if (sc.interrupted) _events.tryEmit(GeminiEvent.Interrupted)
                if (sc.generationComplete) _events.tryEmit(GeminiEvent.GenerationComplete)
                if (sc.turnComplete) _events.tryEmit(GeminiEvent.TurnComplete)

                if (sc.hasModelTurn()) {
                    for (part in sc.modelTurn.partsList) {
                        if (part.hasText() && part.text.isNotBlank()) {
                            _events.tryEmit(GeminiEvent.ModelText(part.text))
                        }
                        if (part.hasInlineData()) {
                            val mime = part.inlineData.mimeType
                            if (mime.startsWith("audio/pcm")) {
                                _audio.trySend(AudioFrame(part.inlineData.data.toByteArray(), myEpoch))
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("GeminiProtobufLiveClient: Ошибка разбора сообщения", e)
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