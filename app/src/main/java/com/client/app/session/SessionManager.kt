// >>> FILE: app/src/main/java/com/client/app/session/SessionManager.kt
package com.client.app.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.api.*
import com.client.app.attach.AnalysisResult
import com.client.app.attach.VocabItem
import com.client.app.attach.VocabularyExtractor
import com.client.app.audio.NativeAudioEngine
import com.client.app.audio.PronunciationPlayer
import com.client.app.forvo.ForvoRepository
import com.client.app.forvo.ForvoResult
import com.client.app.service.LiveSessionForegroundService
import com.client.app.util.AppLogger
import com.client.app.util.AttachmentProcessor
import com.client.app.util.CryptoManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val idGen = AtomicLong(0)

data class ChatMessage(
    val id: Long = idGen.incrementAndGet(),
    val role: String,
    val text: String,
    val attachmentNames: List<String> = emptyList(),
    val interim: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class ForvoWord(
    val word: String,
    val query: String = word,
    val language: String = "de",
    val translation: String? = null,
    val audioUrl: String? = null,
    val isLoading: Boolean = true,
    val notFound: Boolean = false
)

enum class LinkState { IDLE, CONNECTING, LIVE, RECONNECTING }

data class SessionState(
    val link: LinkState = LinkState.IDLE,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val isAnalyzing: Boolean = false,
    val activePrompt: String = "",
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val forvoWords: List<ForvoWord> = emptyList(),
    val forvoUsed: Int = 0,
    val forvoLimit: Int = 500,
    val tokensUsed: Int = 0
) {
    val isConnected: Boolean get() = link == LinkState.LIVE
    val isConnecting: Boolean get() = link == LinkState.CONNECTING || link == LinkState.RECONNECTING
}

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GeminiLiveClient,
    private val audioEngine: NativeAudioEngine,
    private val forvoRepo: ForvoRepository,
    private val forvoPlayer: PronunciationPlayer,
    private val attachmentProcessor: AttachmentProcessor,
    private val extractor: VocabularyExtractor,
    private val dataStore: DataStore<Preferences>,
    private val cryptoManager: CryptoManager,
    private val logger: AppLogger
) {
    companion object {
        val KEY_API = stringPreferencesKey("gemini_api_key")
        val KEY_MODEL = stringPreferencesKey("gemini_model")
        val KEY_ANALYZER_MODEL = stringPreferencesKey("analyzer_model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("gemini_system_prompt")
        val KEY_ENABLE_FORVO = booleanPreferencesKey("enable_forvo")
        val KEY_VOICE = stringPreferencesKey("gemini_voice")
        val KEY_VOLUME = floatPreferencesKey("audio_volume")
        val KEY_MIC_GAIN = floatPreferencesKey("audio_mic_gain")

        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — интеллектуальный персональный голосовой ассистент с академической культурой речи. " +
            "Отвечай лаконично, точно и структурированно, без шаблонных вводных слов. " +
            "Говори естественным, уверенным тоном."

        private const val MAX_MESSAGES = 200
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val micMutex = Mutex()

    private val _state = MutableStateFlow(SessionState(activePrompt = DEFAULT_SYSTEM_PROMPT))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val amplitude: StateFlow<Float> = combine(
        audioEngine.micLevel, audioEngine.outLevel
    ) { mic, out -> maxOf(mic, out) }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    private var micJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var streamingRole: String? = null
    @Volatile private var resumptionHandle: String? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var userStopped = false
    @Volatile private var conservativeSetup = false
    @Volatile private var pendingGoAway = false
    @Volatile private var userMicDesired = true

    init {
        observeSettings()
        observeEvents()
        observeAudio()
        observeBargeIn()
    }

    fun toggleConnection() = scope.launch {
        mutex.withLock {
            if (_state.value.link != LinkState.IDLE) {
                userStopped = true
                stopInternal(full = true)
            } else {
                userStopped = false
                userMicDesired = true
                reconnectAttempts = 0
                resumptionHandle = null
                startInternal(resume = false)
            }
        }
    }

    fun toggleMic() = scope.launch {
        if (_state.value.isMicActive) {
            stopMic(userInitiated = true)
        } else {
            startMic()
        }
    }

    fun stopSession() = scope.launch {
        mutex.withLock {
            userStopped = true
            stopInternal(full = true)
        }
    }

    fun applyPrompt(newPrompt: String) {
        val changed = _state.value.activePrompt != newPrompt
        _state.update { it.copy(activePrompt = newPrompt) }
        if (!changed || _state.value.link == LinkState.IDLE) return

        scope.launch {
            mutex.withLock {
                resumptionHandle = null
                stopInternal(full = false)
                startInternal(resume = false)
            }
        }
    }

    fun sendText(text: String, uris: List<Uri> = emptyList()) = scope.launch {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && uris.isEmpty()) return@launch

        if (_state.value.isAiSpeaking) {
            audioEngine.flushPlayback()
            _state.update { it.copy(isAiSpeaking = false) }
        }

        if (uris.isNotEmpty()) {
            handleAttachments(trimmed, uris)
            return@launch
        }

        addMessage(ChatMessage(role = "user", text = trimmed))
        streamingRole = null

        if (!ensureLive()) {
            _state.update { it.copy(error = "Нет соединения с сервером") }
            return@launch
        }
        client.sendRealtimeText(trimmed)
    }

    fun playForvo(word: ForvoWord) = scope.launch {
        val url = forvoRepo.freshUrl(word.query, word.language) ?: run {
            _state.update { it.copy(error = "Ссылка Forvo недоступна или устарела") }
            return@launch
        }

        val wasMic = _state.value.isMicActive
        if (wasMic) stopMic(userInitiated = false)

        forvoPlayer.play(url)
        syncQuota()

        if (wasMic && userMicDesired && !userStopped) {
            delay(200)
            startMic()
        }
    }

    fun clearForvo() {
        forvoRepo.clearMisses()
        _state.update { it.copy(forvoWords = emptyList()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearChat() = _state.update { it.copy(messages = emptyList()) }

    private suspend fun handleAttachments(text: String, uris: List<Uri>) {
        _state.update { it.copy(isAnalyzing = true, error = null) }
        try {
            val processed = attachmentProcessor.process(uris)
            val prefs = dataStore.data.first()
            val apiKey = cryptoManager.decrypt(prefs[KEY_API]?.trim().orEmpty())
            val forvoOn = prefs[KEY_ENABLE_FORVO] ?: false

            addMessage(ChatMessage(role = "user", text = text.ifEmpty { "Изучи приложенный документ." }, attachmentNames = processed.accepted))

            val result = extractor.analyze(
                apiKey = apiKey,
                images = processed.images,
                plainText = processed.extractedText,
                forLanguageLearning = forvoOn
            )

            if (result is AnalysisResult.Success) {
                if (!ensureLive()) return
                client.sendRealtimeText(result.analysis.fullText.take(15000))
            }
        } catch (e: Exception) {
            logger.e("Attachment error", e)
        } finally {
            _state.update { it.copy(isAnalyzing = false) }
        }
    }

    private fun syncQuota() {
        val q = forvoRepo.quota.value
        _state.update { it.copy(forvoUsed = q.used, forvoLimit = q.limit) }
    }

    private suspend fun ensureLive(): Boolean {
        if (client.isReady) return true
        if (_state.value.link == LinkState.IDLE) {
            mutex.withLock {
                userStopped = false
                userMicDesired = true
                startInternal(resume = false)
            }
        }
        return withTimeoutOrNull(8000L) {
            while (!client.isReady) delay(40)
            true
        } == true
    }

    private suspend fun startInternal(resume: Boolean) {
        pendingGoAway = false
        val prefs = dataStore.data.first()
        val apiKey = cryptoManager.decrypt(prefs[KEY_API]?.trim().orEmpty())
        if (apiKey.isEmpty()) {
            _state.update { it.copy(error = "Укажите Gemini API Key в Настройках", link = LinkState.IDLE) }
            return
        }

        val rawModel = prefs[KEY_MODEL]?.trim().orEmpty()
        val model = if (rawModel.contains("live", ignoreCase = true) || rawModel.contains("native-audio", ignoreCase = true)) {
            rawModel
        } else {
            "gemini-2.5-flash-native-audio-latest"
        }

        val voice = prefs[KEY_VOICE]?.ifBlank { null } ?: "Charon"
        val enableForvo = prefs[KEY_ENABLE_FORVO] ?: false

        audioEngine.setVolume(prefs[KEY_VOLUME] ?: 1.0f)
        audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)

        _state.update {
            it.copy(link = if (resume) LinkState.RECONNECTING else LinkState.CONNECTING, error = null)
        }

        audioEngine.start()
        startForegroundService()

        client.connect(
            LiveConfig(
                apiKey = apiKey,
                model = model,
                systemInstruction = _state.value.activePrompt,
                voiceName = voice,
                toolsJson = if (enableForvo) buildForvoToolsSchema() else null,
                resumptionHandle = if (resume) resumptionHandle else null,
                initialHistory = if (resume) emptyList() else recentHistory(),
                conservative = conservativeSetup
            )
        )
    }

    private fun recentHistory(): List<Pair<String, String>> {
        val raw = _state.value.messages.filter { !it.interim && it.text.isNotBlank() }
        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf<Pair<String, String>>()
        for (msg in raw) {
            val role = if (msg.role == "model") "model" else "user"
            val last = merged.lastOrNull()
            if (last != null && last.first == role) {
                merged[merged.size - 1] = role to "${last.second}\n\n${msg.text.trim()}"
            } else {
                merged.add(role to msg.text.trim())
            }
        }
        var slice = merged.takeLast(20)
        while (slice.isNotEmpty() && slice.first().first != "user") {
            slice = slice.drop(1)
        }
        return slice
    }

    private suspend fun stopInternal(full: Boolean) {
        pendingGoAway = false
        reconnectJob?.cancel()
        stopMic(userInitiated = false)
        client.disconnect()
        if (full) {
            audioEngine.stop()
            stopForegroundService()
        } else {
            audioEngine.flushPlayback()
        }
        _state.update {
            it.copy(link = LinkState.IDLE, isAiSpeaking = false, isMicActive = false)
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (userStopped) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            scope.launch {
                mutex.withLock { stopInternal(full = true) }
                _state.update { it.copy(error = "Соединение потеряно: $reason") }
            }
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val attempt = ++reconnectAttempts
            _state.update { it.copy(link = LinkState.RECONNECTING) }
            delay(minOf(400L * (1L shl (attempt - 1)), 6000L))
            mutex.withLock {
                if (!userStopped) startInternal(resume = resumptionHandle != null)
            }
        }
    }

    private suspend fun startMic() = micMutex.withLock {
        if (_state.value.isMicActive) return@withLock
        userMicDesired = true

        if (!audioEngine.start()) {
            _state.update { it.copy(error = "Микрофон недоступен") }
            return@withLock
        }
        _state.update { it.copy(isMicActive = true) }

        micJob = scope.launch {
            for (chunk in audioEngine.micOutput) {
                if (!isActive) break
                if (!forvoPlayer.isPlaying.value) {
                    client.sendAudio(chunk)
                }
            }
        }
    }

    private suspend fun stopMic(userInitiated: Boolean = false) = micMutex.withLock {
        if (userInitiated) userMicDesired = false
        micJob?.cancelAndJoin()
        micJob = null
        client.sendAudioStreamEnd()
        _state.update { it.copy(isMicActive = false) }
    }

    private fun observeAudio() = scope.launch {
        for (frame in client.audio) {
            if (frame.epoch != client.epoch) continue
            _state.update { it.copy(isAiSpeaking = true) }
            audioEngine.enqueuePlayback(frame.pcm)
        }
    }

    // Подписка на нативный нейросетевой Barge-In
    private fun observeBargeIn() = scope.launch {
        audioEngine.bargeInEvents.collect {
            _state.update { it.copy(isAiSpeaking = false) }
            streamingRole = null
        }
    }

    private fun observeEvents() = scope.launch {
        client.events.collect { event ->
            when (event) {
                is GeminiEvent.SetupComplete -> {
                    reconnectAttempts = 0
                    _state.update { it.copy(link = LinkState.LIVE, error = null) }
                    if (userMicDesired) scope.launch { startMic() }
                }
                is GeminiEvent.ResumptionHandle -> resumptionHandle = event.handle
                is GeminiEvent.GoAway -> {
                    pendingGoAway = true
                    scope.launch {
                        delay(maxOf(event.millisLeft - 2000L, 1000L))
                        if (pendingGoAway && !userStopped) {
                            pendingGoAway = false
                            scheduleReconnect("дедлайн goAway")
                        }
                    }
                }
                is GeminiEvent.Interrupted -> {
                    audioEngine.flushPlayback()
                    _state.update { it.copy(isAiSpeaking = false) }
                    streamingRole = null
                }
                is GeminiEvent.GenerationComplete,
                is GeminiEvent.TurnComplete -> {
                    streamingRole = null
                    scope.launch {
                        delay(100)
                        _state.update { it.copy(isAiSpeaking = false) }
                        if (pendingGoAway && !userStopped) {
                            pendingGoAway = false
                            scheduleReconnect("плановый переход goAway")
                        }
                    }
                }
                is GeminiEvent.InputTranscript -> appendTranscript("user", event.text, event.interim)
                is GeminiEvent.OutputTranscript -> appendTranscript("model", event.text, false)
                is GeminiEvent.ModelText -> appendTranscript("model", event.text, false)
                is GeminiEvent.Usage -> _state.update { it.copy(tokensUsed = event.totalTokens) }
                is GeminiEvent.Error -> {
                    _state.update { it.copy(error = event.message) }
                    if (event.fatal) {
                        userStopped = true
                        scope.launch { mutex.withLock { stopInternal(full = true) } }
                    }
                }
                is GeminiEvent.Disconnected -> {
                    if (event.epoch != client.epoch) return@collect
                    if (!userStopped) scheduleReconnect("код ${event.code}")
                }
                else -> Unit
            }
        }
    }

    private fun appendTranscript(role: String, text: String, interim: Boolean) {
        _state.update { s ->
            val list = s.messages.toMutableList()
            if (interim) {
                val idx = list.indexOfLast { it.role == role && it.interim }
                val msg = ChatMessage(role = role, text = text, interim = true)
                if (idx >= 0) list[idx] = list[idx].copy(text = text) else list.add(msg)
                return@update s.copy(messages = list.takeLast(MAX_MESSAGES))
            }
            list.removeAll { it.role == role && it.interim }
            val last = list.lastOrNull()
            if (streamingRole == role && last != null && last.role == role && !last.interim) {
                list[list.size - 1] = last.copy(text = last.text + text)
            } else {
                list.add(ChatMessage(role = role, text = text))
                streamingRole = role
            }
            s.copy(messages = list.takeLast(MAX_MESSAGES))
        }
    }

    private fun addMessage(msg: ChatMessage) {
        streamingRole = null
        _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES)) }
    }

    private fun buildForvoToolsSchema(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "lookup_pronunciation")
                    put("description", "Озвучивание слов носителями языка с Forvo")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("words", buildJsonObject {
                                put("type", "ARRAY")
                                put("items", buildJsonObject { put("type", "STRING") })
                            })
                            put("language", buildJsonObject { put("type", "STRING") })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("words")) })
                    })
                })
            })
        })
    }

    private fun observeSettings() = scope.launch {
        dataStore.data.collect { prefs ->
            audioEngine.setVolume(prefs[KEY_VOLUME] ?: 1.0f)
            audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)
        }
    }

    private fun startForegroundService() {
        val intent = Intent(context, LiveSessionForegroundService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopForegroundService() {
        runCatching { context.stopService(Intent(context, LiveSessionForegroundService::class.java)) }
    }
}