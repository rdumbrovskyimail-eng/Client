package com.client.app.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.api.GeminiEvent
import com.client.app.api.GeminiLiveClient
import com.client.app.api.ToolResponse
import com.client.app.audio.AndroidAudioEngine
import com.client.app.audio.PronunciationPlayer
import com.client.app.forvo.ForvoRepository
import com.client.app.service.LiveSessionForegroundService
import com.client.app.util.AppLogger
import com.client.app.util.AttachmentProcessor
import com.client.app.viewmodel.SettingsViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: String,
    val text: String,
    val attachmentNames: List<String> = emptyList()
)

data class ForvoWord(
    val word: String,
    val audioUrl: String? = null,
    val isLoading: Boolean = true
)

data class SessionState(
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val activePrompt: String = "",
    val error: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val forvoWords: List<ForvoWord> = emptyList()
)

@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GeminiLiveClient,
    private val audioEngine: AndroidAudioEngine,
    private val forvoRepo: ForvoRepository,
    private val forvoPlayer: PronunciationPlayer,
    private val attachmentProcessor: AttachmentProcessor,
    private val dataStore: DataStore<Preferences>,
    private val logger: AppLogger
) {
    companion object {
        val KEY_API = stringPreferencesKey("gemini_api_key")
        val KEY_MODEL = stringPreferencesKey("gemini_model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("gemini_system_prompt")
        val KEY_ENABLE_FORVO = booleanPreferencesKey("enable_forvo")

        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — интеллектуальный персональный ассистент с академической культурой речи. " +
            "Отвечай лаконично, точно и структурированно, без шаблонных вводных слов. " +
            "Помогай изучать любые дисциплины, научные концепции, языки и решать прикладные задачи. " +
            "Говори естественным, уверенным тоном."
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    private val _state = MutableStateFlow(SessionState(activePrompt = DEFAULT_SYSTEM_PROMPT))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private var micJob: Job? = null
    @Volatile private var streamingRole: String? = null

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                val savedPrompt = prefs[KEY_SYSTEM_PROMPT]
                if (!savedPrompt.isNullOrBlank() && _state.value.activePrompt == DEFAULT_SYSTEM_PROMPT) {
                    _state.update { it.copy(activePrompt = savedPrompt) }
                }
                // Применяем громкость и гейн S23 Ultra динамически
                val vol = prefs[SettingsViewModel.KEY_VOLUME] ?: 1.0f
                val gain = prefs[SettingsViewModel.KEY_MIC_GAIN] ?: 1.25f
                audioEngine.playbackVolume = vol
                audioEngine.micGain = gain
            }
        }
        observeEvents()
        observePlaybackAmplitude()
        startDecayLoop()
    }

    fun updatePrompt(newPrompt: String) {
        val changed = _state.value.activePrompt != newPrompt
        _state.update { it.copy(activePrompt = newPrompt) }
        // Если сессия уже подключена, переподключаем с новым промптом
        if (changed && (_state.value.isConnected || _state.value.isConnecting)) {
            scope.launch {
                mutex.withLock {
                    stopInternal()
                    startInternal()
                }
            }
        }
    }

    fun toggleConnection() = scope.launch {
        mutex.withLock {
            if (_state.value.isConnected || _state.value.isConnecting) stopInternal()
            else startInternal()
        }
    }

    fun toggleMic() {
        if (_state.value.isMicActive) stopMic() else startMic()
    }

    fun sendText(text: String, uris: List<Uri> = emptyList()) = scope.launch {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && uris.isEmpty()) return@launch

        // Если не подключены, подключаемся автоматически
        if (!_state.value.isConnected && !_state.value.isConnecting) {
            startInternal()
            // Ждем готовности до 4 секунд
            withTimeoutOrNull(4000L) {
                while (!client.isReady) delay(50)
            }
        }

        streamingRole = null

        if (uris.isEmpty()) {
            addMessage(ChatMessage(role = "user", text = trimmed))
            client.sendClientTurn(trimmed)
        } else {
            val res = attachmentProcessor.process(uris)
            val fullText = buildString {
                if (trimmed.isNotEmpty()) append(trimmed).append("\n\n")
                if (res.extractedText.isNotEmpty()) append(res.extractedText)
            }
            client.sendClientTurn(fullText.ifBlank { "Изучи вложенные материалы." }, res.images)
            addMessage(ChatMessage(role = "user", text = trimmed.ifEmpty { "Вложенные материалы" }, attachmentNames = res.accepted))
        }
    }

    fun playForvo(word: ForvoWord) {
        val url = word.audioUrl ?: return
        scope.launch {
            val wasMic = _state.value.isMicActive
            if (wasMic) stopMic()
            forvoPlayer.play(url)
            if (wasMic) {
                delay(1200)
                startMic()
            }
        }
    }

    fun clearForvo() = _state.update { it.copy(forvoWords = emptyList()) }
    fun clearError() = _state.update { it.copy(error = null) }

    private suspend fun startInternal() {
        val prefs = dataStore.data.first()
        val apiKey = prefs[KEY_API]?.trim().orEmpty()
        val model = prefs[KEY_MODEL]?.ifBlank { "gemini-3.1-flash-live-preview" } ?: "gemini-3.1-flash-live-preview"
        val enableForvo = prefs[KEY_ENABLE_FORVO] ?: false

        audioEngine.playbackVolume = prefs[SettingsViewModel.KEY_VOLUME] ?: 1.0f
        audioEngine.micGain = prefs[SettingsViewModel.KEY_MIC_GAIN] ?: 1.25f

        if (apiKey.isEmpty()) {
            _state.update { it.copy(error = "Укажите Gemini API Key в Настройках") }
            return
        }

        _state.update { it.copy(isConnecting = true, error = null, messages = emptyList()) }

        audioEngine.initPlayback()
        startForegroundService()

        val tools = if (enableForvo) buildForvoToolsSchema() else null
        val prompt = buildEffectivePrompt(_state.value.activePrompt, enableForvo)

        client.connect(
            apiKey = apiKey,
            model = model,
            systemInstruction = prompt,
            toolsJson = tools
        )
    }

    private fun buildEffectivePrompt(base: String, enableForvo: Boolean): String {
        if (!enableForvo) return base
        return base + "\n\nИНСТРУМЕНТ FORVO: Тебе доступен инструмент `lookup_pronunciation`. " +
                "Когда речь заходит об иностранных словах, правильном произношении или переводе терминов, " +
                "ОБЯЗАТЕЛЬНО вызывай `lookup_pronunciation`, передавая изучаемые слова в базовой словарной форме " +
                "и соответствующий код языка (например, 'de', 'en', 'fr', 'es'). Приложение покажет пользователю аудио-карточки от носителей языка."
    }

    private suspend fun stopInternal() {
        stopMic()
        client.disconnect()
        audioEngine.releaseAll()
        stopForegroundService()
        _amplitude.value = 0f
        _state.update {
            it.copy(isConnected = false, isConnecting = false, isAiSpeaking = false, forvoWords = emptyList())
        }
    }

    private fun startMic() {
        if (_state.value.isMicActive) return
        micJob = scope.launch {
            audioEngine.startCapture()
            if (!audioEngine.isCapturing) {
                _state.update { it.copy(error = "Микрофон недоступен") }
                return@launch
            }
            _state.update { it.copy(isMicActive = true) }
            audioEngine.resetClock()

            audioEngine.micOutput.collect { chunk ->
                val isAiPlaying = System.currentTimeMillis() <= audioEngine.audibleUntilMs + 100L
                val isForvoPlaying = forvoPlayer.isPlaying.value

                if (!isForvoPlaying) {
                    client.sendAudio(chunk)
                    if (!isAiPlaying) {
                        _amplitude.value = (calculateRms(chunk) * 4.0f).coerceIn(0f, 1f)
                    }
                }
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        scope.launch {
            audioEngine.stopCapture()
            client.sendAudioStreamEnd()
            _state.update { it.copy(isMicActive = false) }
        }
    }

    private fun observeEvents() = scope.launch {
        client.events.collect { event ->
            when (event) {
                is GeminiEvent.SetupComplete -> {
                    _state.update { it.copy(isConnected = true, isConnecting = false) }
                    startMic()
                }
                is GeminiEvent.AudioChunk -> {
                    _state.update { it.copy(isAiSpeaking = true) }
                    audioEngine.enqueuePlayback(event.pcm)
                }
                is GeminiEvent.TurnComplete -> {
                    // TurnComplete означает конец генерации, но звук еще проигрывается из буфера!
                    // resetClock() намеренно НЕ вызываем здесь, чтобы не обнулять audibleUntilMs
                    scope.launch {
                        delay(audioEngine.audibleUntilMs - System.currentTimeMillis())
                        if (!_state.value.isConnecting) {
                            _state.update { it.copy(isAiSpeaking = false) }
                        }
                    }
                }
                is GeminiEvent.Interrupted -> {
                    _state.update { it.copy(isAiSpeaking = false) }
                    audioEngine.flushPlayback()
                }
                is GeminiEvent.InputTranscript -> appendTranscript("user", event.text)
                is GeminiEvent.OutputTranscript -> appendTranscript("model", event.text)
                is GeminiEvent.Error -> _state.update { it.copy(error = event.message) }
                is GeminiEvent.Disconnected -> stopInternal()
                is GeminiEvent.ToolCall -> handleToolCall(event.calls)
                else -> Unit
            }
        }
    }

    private fun handleToolCall(calls: List<com.client.app.api.FunctionCall>) = scope.launch {
        val responses = calls.map { call ->
            if (call.name == "lookup_pronunciation") {
                val wordsRaw = call.args["words"].orEmpty()
                val lang = call.args["language"]?.ifBlank { "de" } ?: "de"

                val list = runCatching {
                    Json.parseToJsonElement(wordsRaw).jsonArray.map { it.jsonPrimitive.content }
                }.getOrElse { wordsRaw.split(",").map { it.trim() } }
                    .filter { it.isNotBlank() }.distinct().take(8)

                _state.update { it.copy(forvoWords = list.map { w -> ForvoWord(w) }) }

                list.forEach { word ->
                    scope.launch {
                        val url = forvoRepo.fetchPronunciationUrl(word, lang)
                        _state.update { s ->
                            s.copy(forvoWords = s.forvoWords.map { if (it.word == word) it.copy(audioUrl = url, isLoading = false) else it })
                        }
                    }
                }
                ToolResponse(call.name, call.id, """{"status":"success"}""")
            } else {
                ToolResponse(call.name, call.id, """{"error":"unknown_tool"}""")
            }
        }
        client.sendToolResponses(responses)
    }

    private fun buildForvoToolsSchema(): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("functionDeclarations", buildJsonArray {
                add(buildJsonObject {
                    put("name", "lookup_pronunciation")
                    put("description", "Запрашивает аудиозаписи эталонного произношения слов или терминов у носителей языка (Forvo).")
                    put("parameters", buildJsonObject {
                        put("type", "OBJECT")
                        put("properties", buildJsonObject {
                            put("words", buildJsonObject {
                                put("type", "ARRAY")
                                put("description", "Массив слов в начальной словарной форме")
                                put("items", buildJsonObject { put("type", "STRING") })
                            })
                            put("language", buildJsonObject {
                                put("type", "STRING")
                                put("description", "Двухбуквенный код языка (например, 'de', 'en', 'fr', 'es', 'it').")
                            })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("words")) })
                    })
                })
            })
        })
    }

    private fun appendTranscript(role: String, text: String) {
        _state.update { s ->
            val list = s.messages.toMutableList()
            if (streamingRole == role && list.isNotEmpty() && list.last().role == role) {
                val last = list.last()
                list[list.size - 1] = last.copy(text = last.text + text)
            } else {
                list.add(ChatMessage(role = role, text = text))
                streamingRole = role
            }
            s.copy(messages = list.takeLast(60))
        }
    }

    private fun addMessage(msg: ChatMessage) {
        _state.update { it.copy(messages = (it.messages + msg).takeLast(60)) }
    }

    private fun observePlaybackAmplitude() = scope.launch {
        audioEngine.playbackSync.collect { pcm ->
            _amplitude.value = (calculateRms(pcm) * 4.0f).coerceIn(0f, 1f)
        }
    }

    private fun startDecayLoop() = scope.launch {
        while (true) {
            delay(35)
            val v = _amplitude.value
            if (v > 0.01f) _amplitude.value = v * 0.86f
            else if (v != 0f) _amplitude.value = 0f
        }
    }

    private fun calculateRms(pcm: ByteArray): Float {
        if (pcm.size < 2) return 0f
        var sum = 0.0
        val count = pcm.size / 2
        var i = 0
        while (i < pcm.size - 1) {
            val s = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort()
            sum += s * s
            i += 2
        }
        return (sqrt(sum / count) / 32768.0).toFloat()
    }

    private fun startForegroundService() {
        val intent = Intent(context, LiveSessionForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun stopForegroundService() {
        context.stopService(Intent(context, LiveSessionForegroundService::class.java))
    }
}