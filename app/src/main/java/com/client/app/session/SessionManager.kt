package com.client.app.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.api.*
import com.client.app.attach.AnalysisResult
import com.client.app.attach.VocabItem
import com.client.app.attach.VocabularyExtractor
import com.client.app.audio.AudioStreamEvent
import com.client.app.audio.CaptureShutdownResult
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private val idGen = AtomicLong(0)

data class ChatMessage(
    val id: Long = idGen.incrementAndGet(),
    val role: ClientRole,
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

data class ToolCallKey(
    val epoch: Long,
    val callId: String
)

/**
 * Bounded shutdown policy.
 *
 * Graceful producer wait: 1500 ms.
 * Forced producer cancellation join: 100 ms.
 * StreamStop enqueue: 500 ms.
 * Graceful consumer wait: 1500 ms.
 * Forced consumer cancellation join: 100 ms.
 * Emergency server finalization: 500 ms.
 *
 * These are bounded coroutine waits in this layer.
 * They do not constitute a mathematically proven end-to-end
 * wall-clock deadline for non-cooperative native/network code.
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: GeminiProtobufLiveClient,
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
        val KEY_ANALYZER_MODEL = stringPreferencesKey("analyzer_model")
        val KEY_SYSTEM_PROMPT = stringPreferencesKey("gemini_system_prompt")
        val KEY_VOICE = stringPreferencesKey("gemini_voice")
        val KEY_SPEECH_LANGUAGE = stringPreferencesKey("gemini_speech_language")
        val KEY_TEMPERATURE = floatPreferencesKey("gemini_temperature")
        val KEY_MEDIA_RESOLUTION = stringPreferencesKey("gemini_media_resolution")

        val KEY_INPUT_TRANSCRIPTION_ENABLED = booleanPreferencesKey("gemini_input_tx_enabled")
        val KEY_INPUT_TRANSCRIPTION_LANGUAGES = stringPreferencesKey("gemini_input_tx_languages")
        val KEY_INPUT_TRANSCRIPTION_VOCAB = stringPreferencesKey("gemini_input_tx_vocab")
        val KEY_INPUT_TRANSCRIPTION_MODE = stringPreferencesKey("gemini_input_tx_mode")

        val KEY_OUTPUT_TRANSCRIPTION_ENABLED = booleanPreferencesKey("gemini_output_tx_enabled")
        val KEY_OUTPUT_TRANSCRIPTION_LANGUAGES = stringPreferencesKey("gemini_output_tx_languages")
        val KEY_OUTPUT_TRANSCRIPTION_VOCAB = stringPreferencesKey("gemini_output_tx_vocab")
        val KEY_OUTPUT_TRANSCRIPTION_MODE = stringPreferencesKey("gemini_output_tx_mode")

        val KEY_AAD_ENABLED = booleanPreferencesKey("gemini_aad_enabled")
        val KEY_AAD_START_SENSITIVITY = stringPreferencesKey("gemini_aad_start_sensitivity")
        val KEY_AAD_END_SENSITIVITY = stringPreferencesKey("gemini_aad_end_sensitivity")
        val KEY_PREFIX_PADDING_MS = intPreferencesKey("gemini_prefix_padding_ms")
        val KEY_SILENCE_DURATION_MS = intPreferencesKey("gemini_silence_duration_ms")
        val KEY_ACTIVITY_HANDLING = stringPreferencesKey("gemini_activity_handling")
        val KEY_TURN_COVERAGE = stringPreferencesKey("gemini_turn_coverage")

        val KEY_COMPRESSION_ENABLED = booleanPreferencesKey("gemini_compression_enabled")
        val KEY_COMPRESSION_TRIGGER_TOKENS = intPreferencesKey("gemini_compression_trigger_tokens")
        val KEY_COMPRESSION_TARGET_TOKENS = intPreferencesKey("gemini_compression_target_tokens")

        val KEY_SESSION_RESUMPTION_ENABLED = booleanPreferencesKey("gemini_session_resumption_enabled")
        val KEY_INITIAL_HISTORY_TURNS = intPreferencesKey("gemini_initial_history_turns")

        val KEY_ENABLE_FORVO = booleanPreferencesKey("enable_forvo")
        val KEY_ENABLE_SEARCH = booleanPreferencesKey("enable_search")
        val KEY_VOLUME = floatPreferencesKey("audio_volume")
        val KEY_MIC_GAIN = floatPreferencesKey("audio_mic_gain")

        const val DEFAULT_SYSTEM_PROMPT =
            "Ты — интеллектуальный персональный голосовой ассистент с академической культурой речи. " +
            "Отвечай лаконично, точно и структурированно, без шаблонных вводных слов."

        const val DEFAULT_LIVE_MODEL = "gemini-3.8-live"

        private const val MAX_MESSAGES = 200
        private const val MAX_RECONNECT_ATTEMPTS = 5
    }

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logger.e("Unhandled coroutine exception in SessionManager", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + coroutineExceptionHandler)
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
    @Volatile private var streamingRole: ClientRole? = null
    @Volatile private var resumptionHandle: String? = null
    @Volatile private var reconnectAttempts = 0
    @Volatile private var userStopped = false
    @Volatile private var pendingGoAway = false
    @Volatile private var userMicDesired = true
    @Volatile private var hasReceivedAudioTranscript = false

    @Volatile private var currentAadEnabled = true

    // Атомарный флаг активного окна речи (Manual VAD)
    private val isManualActivityActive = AtomicBoolean(false)

    private val activeToolJobs = ConcurrentHashMap<ToolCallKey, Job>()
    private val cancelledToolCallKeys = ConcurrentHashMap.newKeySet<ToolCallKey>()

    init {
        observeSettings()
        observeEvents()
        observeAudio()
        observeBargeIn()
        observeFocus()
        observeForvoQuota()
    }

    private fun observeForvoQuota() = scope.launch {
        forvoRepo.quota.collect { q ->
            _state.update { it.copy(forvoUsed = q.used, forvoLimit = q.limit) }
        }
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
        if (_state.value.isMicActive) stopMic(userInitiated = true) else startMic()
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

        addMessage(ChatMessage(role = ClientRole.USER, text = trimmed))
        streamingRole = null
        hasReceivedAudioTranscript = false

        if (!ensureLive()) {
            _state.update { it.copy(error = "Нет соединения с сервером") }
            return@launch
        }
        
        client.sendClientContent(
            turns = listOf(ClientTurn(role = ClientRole.USER, text = trimmed)),
            turnComplete = true
        )
    }

    fun playForvo(word: ForvoWord) = scope.launch {
        val url = forvoRepo.freshUrl(word.query, word.language) ?: run {
            _state.update { it.copy(error = "Ссылка Forvo недоступна или устарела") }
            return@launch
        }

        val wasMic = _state.value.isMicActive
        if (wasMic) stopMic(userInitiated = false)

        audioEngine.flushPlayback()
        forvoPlayer.play(url)

        if (wasMic && userMicDesired && !userStopped) {
            delay(200)
            startMic()
        }
    }

    fun refetchAllForvo() {
        val words = _state.value.forvoWords
        if (words.isEmpty()) return
        forvoRepo.clearMisses()
        scope.launch {
            resolveForvo(
                words.map { VocabItem(it.word, it.query, it.translation) },
                words.first().language
            )
        }
    }

    fun clearForvo() {
        forvoRepo.clearMisses()
        _state.update { it.copy(forvoWords = emptyList()) }
    }

    fun clearError() = _state.update { it.copy(error = null) }
    fun clearChat() = _state.update { it.copy(messages = emptyList()) }

    private suspend fun resolveForvo(items: List<VocabItem>, lang: String) {
        _state.update { s ->
            s.copy(forvoWords = items.map {
                ForvoWord(
                    word = it.lemma,
                    query = it.forvoQuery,
                    language = lang,
                    translation = it.translation,
                    isLoading = true
                )
            })
        }

        forvoRepo.lookupBatch(items.map { it.forvoQuery }, lang) { query, res ->
            _state.update { s ->
                s.copy(forvoWords = s.forvoWords.map { w ->
                    if (!w.query.equals(query, ignoreCase = true)) w
                    else when (res) {
                        is ForvoResult.Found -> w.copy(
                            audioUrl = res.pronunciation.mp3Url,
                            isLoading = false, notFound = false
                        )
                        else -> w.copy(isLoading = false, notFound = true)
                    }
                })
            }
        }
    }

    private suspend fun handleAttachments(text: String, uris: List<Uri>) {
        _state.update { it.copy(isAnalyzing = true, error = null) }
        try {
            val processed = attachmentProcessor.process(uris)
            val prefs = dataStore.data.first()
            val apiKey = cryptoManager.decrypt(prefs[KEY_API]?.trim().orEmpty())
            val forvoOn = prefs[KEY_ENABLE_FORVO] ?: false

            addMessage(ChatMessage(role = ClientRole.USER, text = text.ifEmpty { "Изучи приложенный документ." }, attachmentNames = processed.accepted))

            val result = extractor.analyze(
                apiKey = apiKey,
                images = processed.images,
                plainText = processed.extractedText,
                forLanguageLearning = forvoOn,
                model = VocabularyExtractor.DEFAULT_MODEL
            )

            when (result) {
                is AnalysisResult.Success -> {
                    val a = result.analysis
                    if (forvoOn && a.vocabulary.isNotEmpty()) {
                        scope.launch { resolveForvo(a.vocabulary, a.language) }
                    }
                    if (!ensureLive()) return
                    client.sendClientContent(
                        turns = listOf(ClientTurn(role = ClientRole.USER, text = a.fullText.take(15000))),
                        turnComplete = true
                    )
                }
                is AnalysisResult.Failure -> {
                    _state.update { it.copy(error = "Ошибка анализа: ${result.reason}") }
                }
            }
        } catch (e: Exception) {
            logger.e("Attachment error", e)
            _state.update { it.copy(error = "Сбой обработки файлов: ${e.localizedMessage}") }
        } finally {
            _state.update { it.copy(isAnalyzing = false) }
        }
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

    private fun buildForvoToolDeclaration(): JsonObject = buildJsonObject {
        putJsonArray("functionDeclarations") {
            addJsonObject {
                put("name", "lookup_pronunciation")
                put("description", "Запрашивает аудиозаписи произношения слов носителями языка из базы Forvo.")
                put("behavior", "NON_BLOCKING")
                putJsonObject("parameters") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("words") {
                            put("type", "STRING")
                            put("description", "Слово или список слов через запятую для поиска произношения.")
                        }
                        putJsonObject("language") {
                            put("type", "STRING")
                            put("description", "Двухбуквенный код языка ISO 639-1 (например, 'de', 'en', 'fr', 'es'). По умолчанию 'de'.")
                        }
                    }
                    putJsonArray("required") {
                        add(JsonPrimitive("words"))
                    }
                }
            }
        }
    }

    private fun recentHistory(maxTurns: Int): List<ClientTurn> {
        val raw = _state.value.messages
            .filter { !it.interim && it.text.isNotBlank() }

        if (raw.isEmpty()) return emptyList()

        val merged = mutableListOf<ClientTurn>()
        for (msg in raw) {
            val last = merged.lastOrNull()
            if (last != null && last.role == msg.role) {
                merged[merged.size - 1] = ClientTurn(msg.role, "${last.text}\n\n${msg.text.trim()}")
            } else {
                merged.add(ClientTurn(msg.role, msg.text.trim()))
            }
        }

        var slice = merged.takeLast(maxTurns.coerceIn(1, 100))
        while (slice.isNotEmpty() && slice.first().role != ClientRole.USER) {
            slice = slice.drop(1)
        }
        return slice
    }

    private suspend fun startInternal(resume: Boolean) {
        pendingGoAway = false
        hasReceivedAudioTranscript = false
        isManualActivityActive.set(false)

        val prefs = dataStore.data.first()
        val apiKey = cryptoManager.decrypt(prefs[KEY_API]?.trim().orEmpty())
        if (apiKey.isEmpty()) {
            _state.update { it.copy(error = "Укажите Gemini API Key в Настройках", link = LinkState.IDLE) }
            return
        }

        val voice = prefs[KEY_VOICE]?.ifBlank { null } ?: "Charon"
        val speechLang = prefs[KEY_SPEECH_LANGUAGE]?.takeIf { it.isNotBlank() }
        val temperature = prefs[KEY_TEMPERATURE] ?: 0.5f
        val mediaResolution = prefs[KEY_MEDIA_RESOLUTION] ?: "MEDIA_RESOLUTION_HIGH"

        val inputTx = TranscriptionSettings(
            enabled = prefs[KEY_INPUT_TRANSCRIPTION_ENABLED] ?: true,
            languageCodes = prefs[KEY_INPUT_TRANSCRIPTION_LANGUAGES]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            customVocabulary = prefs[KEY_INPUT_TRANSCRIPTION_VOCAB]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(1000) ?: emptyList(),
            mode = prefs[KEY_INPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        val outputTx = TranscriptionSettings(
            enabled = prefs[KEY_OUTPUT_TRANSCRIPTION_ENABLED] ?: true,
            languageCodes = prefs[KEY_OUTPUT_TRANSCRIPTION_LANGUAGES]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
            customVocabulary = prefs[KEY_OUTPUT_TRANSCRIPTION_VOCAB]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }?.take(1000) ?: emptyList(),
            mode = prefs[KEY_OUTPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        val aadEnabled = prefs[KEY_AAD_ENABLED] ?: true
        currentAadEnabled = aadEnabled
        
        audioEngine.isAadMode = aadEnabled

        val realtimeInput = RealtimeInputSettings(
            aadEnabled = aadEnabled,
            startSensitivity = prefs[KEY_AAD_START_SENSITIVITY] ?: "START_SENSITIVITY_HIGH",
            endSensitivity = prefs[KEY_AAD_END_SENSITIVITY] ?: "END_SENSITIVITY_LOW",
            prefixPaddingMs = prefs[KEY_PREFIX_PADDING_MS] ?: 60,
            silenceDurationMs = prefs[KEY_SILENCE_DURATION_MS] ?: 600,
            activityHandling = prefs[KEY_ACTIVITY_HANDLING] ?: "START_OF_ACTIVITY_INTERRUPTS",
            turnCoverage = prefs[KEY_TURN_COVERAGE] ?: "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"
        )

        val compression = CompressionSettings(
            enabled = prefs[KEY_COMPRESSION_ENABLED] ?: true,
            triggerTokens = prefs[KEY_COMPRESSION_TRIGGER_TOKENS] ?: 0,
            targetTokens = prefs[KEY_COMPRESSION_TARGET_TOKENS] ?: 0
        )

        val resumptionEnabled = prefs[KEY_SESSION_RESUMPTION_ENABLED] ?: true
        val maxHistoryTurns = prefs[KEY_INITIAL_HISTORY_TURNS] ?: 20

        audioEngine.setVolume(prefs[KEY_VOLUME] ?: 1.0f)
        audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)

        if (!audioEngine.start()) {
            _state.update { it.copy(error = "Сбой инициализации аудиодрайвера", link = LinkState.IDLE) }
            return
        }

        if (!resume && _state.value.link == LinkState.IDLE) {
            startForegroundService()
        }

        _state.update {
            it.copy(link = if (resume) LinkState.RECONNECTING else LinkState.CONNECTING, error = null)
        }

        val forvoEnabled = prefs[KEY_ENABLE_FORVO] ?: false
        val searchEnabled = prefs[KEY_ENABLE_SEARCH] ?: false

        val dynamicTools = if (forvoEnabled) {
            buildJsonArray { add(buildForvoToolDeclaration()) }
        } else {
            null
        }

        client.connect(
            LiveConfig(
                apiKey = apiKey,
                model = DEFAULT_LIVE_MODEL,
                systemInstruction = _state.value.activePrompt,
                voiceName = voice,
                speechLanguage = speechLang,
                temperature = temperature,
                mediaResolution = mediaResolution,
                inputTranscription = inputTx,
                outputTranscription = outputTx,
                realtimeInput = realtimeInput,
                compression = compression,
                sessionResumptionEnabled = resumptionEnabled,
                resumptionHandle = if (resume && resumptionEnabled) resumptionHandle else null,
                toolsJson = dynamicTools,
                enableGoogleSearch = searchEnabled,
                initialHistory = if (resume) emptyList() else recentHistory(maxHistoryTurns)
            )
        )
    }

    private suspend fun stopInternal(full: Boolean) {
        pendingGoAway = false
        hasReceivedAudioTranscript = false
        reconnectJob?.cancel()
        if (full) {
            cancelAllPendingToolJobs()
        }

        // 1. Остановка логического захвата микрофона
        stopMic(userInitiated = false)
        client.disconnect()
        isManualActivityActive.set(false)

        // 2. Освобождение физического аудиоядра ТОЛЬКО при полном завершении сессии
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

    private fun cancelAllPendingToolJobs() {
        activeToolJobs.values.forEach { it.cancel() }
        activeToolJobs.clear()
        cancelledToolCallKeys.clear()
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

            val baseDelay = minOf(400L * (1L shl (attempt - 1)), 6000L)
            val jitteredDelay = (baseDelay * (0.8 + Math.random() * 0.4)).toLong()
            delay(jitteredDelay)

            mutex.withLock {
                if (!userStopped) startInternal(resume = resumptionHandle != null)
            }
        }
    }

    private suspend fun finalizeMicActivityBounded() {
        withTimeoutOrNull(500L) {
            if (currentAadEnabled) {
                client.sendAudioStreamEnd()
            } else {
                if (isManualActivityActive.compareAndSet(true, false) && client.isReady) {
                    client.sendActivityEnd()
                }
            }
        } ?: logger.w("SessionManager: bounded mic activity finalization timed out")
    }

    /**
     * Запуск микрофона с монотонной обработкой AudioStreamEvent.
     */
    private suspend fun startMic() = micMutex.withLock {
        if (_state.value.isMicActive) return@withLock
        userMicDesired = true
        isManualActivityActive.set(false)

        if (!audioEngine.start()) {
            _state.update { it.copy(error = "Микрофон недоступен") }
            return@withLock
        }
        _state.update { it.copy(isMicActive = true) }

        micJob = scope.launch {
            for (event in audioEngine.micOutput) {
                if (!isActive) break
                when (event) {
                    is AudioStreamEvent.SpeechStart -> {
                        if (!currentAadEnabled && client.isReady) {
                            if (isManualActivityActive.compareAndSet(false, true)) {
                                logger.d("SessionManager: VAD SpeechStart (Manual VAD) -> sendActivityStart")
                                client.sendActivityStart()
                            }
                        }
                    }
                    is AudioStreamEvent.Audio -> {
                        if (!forvoPlayer.isPlaying.value) {
                            if (currentAadEnabled || isManualActivityActive.get()) {
                                client.sendAudioPcm(event.pcm)
                            }
                        }
                    }
                    is AudioStreamEvent.SpeechEnd -> {
                        if (currentAadEnabled) {
                            withTimeoutOrNull(500L) {
                                client.sendAudioStreamEnd()
                            } ?: logger.w("SessionManager: SpeechEnd audioStreamEnd timed out")
                        } else {
                            if (isManualActivityActive.compareAndSet(true, false) && client.isReady) {
                                withTimeoutOrNull(500L) {
                                    client.sendActivityEnd()
                                } ?: logger.w("SessionManager: SpeechEnd activityEnd timed out")
                            }
                        }
                    }
                    is AudioStreamEvent.StreamStop -> {
                        if (currentAadEnabled) {
                            withTimeoutOrNull(500L) {
                                client.sendAudioStreamEnd()
                            } ?: logger.w("SessionManager: StreamStop audioStreamEnd timed out")
                        } else {
                            if (isManualActivityActive.compareAndSet(true, false) && client.isReady) {
                                withTimeoutOrNull(500L) {
                                    client.sendActivityEnd()
                                } ?: logger.w("SessionManager: StreamStop activityEnd timed out")
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * Bounded Graceful Shutdown логического микрофона.
     */
    private suspend fun stopMic(
        userInitiated: Boolean = false
    ) = micMutex.withLock {
        if (userInitiated) {
            userMicDesired = false
        }
        if (!_state.value.isMicActive) {
            return@withLock
        }

        val producerResult = audioEngine.stopCaptureGraceful(gracefulTimeoutMs = 1500L)
        if (producerResult == CaptureShutdownResult.FORCED_TIMEOUT) {
            logger.w("SessionManager: producer shutdown forced")
        }

        val consumerJob = micJob
        val consumerCompleted = if (consumerJob == null) {
            true
        } else {
            withTimeoutOrNull(1500L) {
                consumerJob.join()
                true
            } ?: false
        }

        if (!consumerCompleted && consumerJob != null) {
            logger.w("SessionManager: micJob consumer timeout; forcing bounded cancellation")
            consumerJob.cancel()
            val cancelledAndJoined = withTimeoutOrNull(100L) {
                consumerJob.join()
                true
            } ?: false

            if (!cancelledAndJoined) {
                logger.e("SessionManager: micJob did not terminate after bounded cancellation")
            }

            finalizeMicActivityBounded()
        }

        micJob = null
        isManualActivityActive.set(false)

        _state.update {
            it.copy(isMicActive = false)
        }
    }

    private fun observeAudio() = scope.launch {
        for (frame in client.audio) {
            if (frame.epoch != client.epoch) continue
            _state.update { it.copy(isAiSpeaking = true) }
            audioEngine.enqueuePlayback(frame.pcm)
        }
    }

    private fun observeBargeIn() = scope.launch {
        audioEngine.bargeInEvents.collect {
            _state.update { it.copy(isAiSpeaking = false) }
            streamingRole = null
            hasReceivedAudioTranscript = false
        }
    }

    private fun observeFocus() = scope.launch {
        audioEngine.focusLost.collect { lost ->
            if (lost && _state.value.isMicActive) {
                stopMic(userInitiated = false)
                _state.update { it.copy(error = "Аудио прервано другим приложением или вызовом") }
            }
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
                    audioEngine.resetBargeInState()
                    _state.update { it.copy(isAiSpeaking = false) }
                    streamingRole = null
                    hasReceivedAudioTranscript = false
                }
                is GeminiEvent.GenerationComplete -> {
                    streamingRole = null
                    hasReceivedAudioTranscript = false
                    audioEngine.resetBargeInState()
                }
                is GeminiEvent.TurnComplete -> {
                    streamingRole = null
                    hasReceivedAudioTranscript = false
                    audioEngine.resetBargeInState()
                    scope.launch {
                        delay(80)
                        _state.update { it.copy(isAiSpeaking = false) }
                        if (pendingGoAway && !userStopped) {
                            pendingGoAway = false
                            scheduleReconnect("плановый переход goAway")
                        }
                    }
                }
                is GeminiEvent.InputTranscript -> appendTranscript(ClientRole.USER, event.text, event.interim)
                is GeminiEvent.OutputTranscript -> {
                    hasReceivedAudioTranscript = true
                    appendTranscript(ClientRole.MODEL, event.text, false)
                }
                is GeminiEvent.ModelText -> Unit
                is GeminiEvent.Usage -> _state.update { it.copy(tokensUsed = event.totalTokens) }
                is GeminiEvent.ToolCall -> handleToolCall(event.calls)
                is GeminiEvent.ToolCallCancelled -> {
                    val currentEpoch = client.epoch
                    event.ids.forEach { id ->
                        val key = ToolCallKey(currentEpoch, id)
                        cancelledToolCallKeys.add(key)
                        activeToolJobs.remove(key)?.cancel()
                    }
                }
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

    private fun handleToolCall(calls: List<FunctionCall>) {
        val currentEpoch = client.epoch
        for (call in calls) {
            val callId = call.id
            if (callId.isNullOrBlank()) {
                client.sendToolResponses(
                    listOf(
                        ToolResponse(
                            name = call.name,
                            id = null,
                            response = buildJsonObject { put("error", "missing_call_id") },
                            scheduling = FunctionResponseScheduling.SILENT
                        )
                    )
                )
                continue
            }

            val key = ToolCallKey(currentEpoch, callId)

            if (call.name != "lookup_pronunciation") {
                client.sendToolResponses(
                    listOf(
                        ToolResponse(
                            name = call.name,
                            id = callId,
                            response = buildJsonObject { put("error", "unknown_tool") },
                            scheduling = FunctionResponseScheduling.SILENT
                        )
                    )
                )
                continue
            }

            val rawWords = call.getString("words")
            val lang = call.getString("language", default = "de").ifBlank { "de" }

            val list = runCatching {
                Json.parseToJsonElement(rawWords).jsonArray.map { it.jsonPrimitive.content }
            }.getOrElse { rawWords.split(",").map { it.trim() } }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(40)

            val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                try {
                    if (list.isNotEmpty()) {
                        val existing = _state.value.forvoWords.map { it.query.lowercase() }.toSet()
                        val fresh = list.filter { it.lowercase() !in existing }
                        if (fresh.isNotEmpty()) {
                            _state.update { s ->
                                s.copy(forvoWords = s.forvoWords + fresh.map {
                                    ForvoWord(word = it, query = it, language = lang)
                                })
                            }
                            forvoRepo.lookupBatch(fresh, lang) { q, res ->
                                if (currentEpoch != client.epoch || cancelledToolCallKeys.contains(key) || !coroutineContext.isActive) {
                                    return@lookupBatch
                                }
                                _state.update { s ->
                                    s.copy(forvoWords = s.forvoWords.map { w ->
                                        if (!w.query.equals(q, true)) w
                                        else when (res) {
                                            is ForvoResult.Found -> w.copy(
                                                audioUrl = res.pronunciation.mp3Url,
                                                isLoading = false, notFound = false
                                            )
                                            else -> w.copy(isLoading = false, notFound = true)
                                        }
                                    })
                                }
                            }
                        }
                    }

                    if (currentEpoch != client.epoch || cancelledToolCallKeys.contains(key) || !isActive) {
                        return@launch
                    }

                    val respPayload = buildJsonObject {
                        put("status", "ok")
                        put("accepted_words_count", list.size)
                    }

                    client.sendToolResponses(
                        listOf(
                            ToolResponse(
                                name = call.name,
                                id = callId,
                                response = respPayload,
                                scheduling = FunctionResponseScheduling.WHEN_IDLE,
                                willContinue = false
                            )
                        )
                    )
                } finally {
                    activeToolJobs.remove(key)
                }
            }

            val existing = activeToolJobs.putIfAbsent(key, job)
            if (existing == null) {
                if (currentEpoch == client.epoch) {
                    job.start()
                } else {
                    job.cancel()
                    activeToolJobs.remove(key, job)
                }
            } else {
                job.cancel()
            }
        }
    }

    private fun appendTranscript(role: ClientRole, text: String, interim: Boolean) {
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
        hasReceivedAudioTranscript = false
        _state.update { it.copy(messages = (it.messages + msg).takeLast(MAX_MESSAGES)) }
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