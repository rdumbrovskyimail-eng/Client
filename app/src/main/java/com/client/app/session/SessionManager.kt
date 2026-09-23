package com.client.app.session

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.client.app.api.*
import com.client.app.attach.AnalysisResult
import com.client.app.attach.VocabItem
import com.client.app.attach.VocabularyExtractor
import com.client.app.audio.AudioFocusEvent
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
import java.util.ArrayDeque
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
    val modelReplayText: String? = null,
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
    val sessionId: Long,
    val epoch: Long,
    val callId: String
)

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
        val KEY_LIVE_MODEL = stringPreferencesKey("gemini_live_model")
        val KEY_THINKING_LEVEL = stringPreferencesKey("gemini_thinking_level")
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
        val KEY_SESSION_RESUMPTION_HANDLE = stringPreferencesKey("gemini_session_resumption_handle")
        val KEY_SESSION_RESUMPTION_TIMESTAMP =
            longPreferencesKey("gemini_resumption_timestamp")

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
    private val commandMutex = Mutex()
    private val transcriptLock = Any()
    private val userTurnMutex = Mutex()
    private val toolResponseMutex = Mutex()

    private val _state = MutableStateFlow(SessionState(activePrompt = DEFAULT_SYSTEM_PROMPT))
    val state: StateFlow<SessionState> = _state.asStateFlow()

    val amplitude: StateFlow<Float> = combine(
        audioEngine.micLevel, audioEngine.outLevel
    ) { mic, out -> maxOf(mic, out) }
        .stateIn(scope, SharingStarted.Eagerly, 0f)

    private var micJob: Job? = null
    private var reconnectJob: Job? = null
    private var goAwayJob: Job? = null
    private val reconnectGuard = Any()
    private val reconnectToken = AtomicLong(0L)
    private val goAwayToken = AtomicLong(0L)
    @Volatile private var resumptionHandle: String? = null
    private val resumptionPersistenceMutex = Mutex()
    @Volatile private var reconnectAttempts = 0
    @Volatile private var pendingGoAway = false
    @Volatile private var activeConnectUsedResumption = false

    @Volatile private var connectionDesired = false

    @Volatile private var userMicDesired = false
    private var transcriptStreamGenerationId: Long = -1L
    @Volatile private var currentOutputTranscriptionEnabled = true

    @Volatile private var currentAadEnabled = true
    @Volatile private var activeSessionId = 0L
    @Volatile private var interactionStatus: String? = null
    @Volatile private var turnCompleteSeen = false
    @Volatile private var activeLiveCapabilities =
        LiveModelCapabilitiesRegistry.forModel(DEFAULT_LIVE_MODEL)

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
        commandMutex.withLock {
            mutex.withLock {
                if (_state.value.link != LinkState.IDLE) {
                    connectionDesired = false
                    userMicDesired = false
                    cancelReconnectWork()
                    stopInternal(full = true)
                } else {
                    connectionDesired = true
                    reconnectAttempts = 0
                    resumptionHandle = null
                    cancelReconnectWork()
                    try {
                        startInternal(resume = false)
                    } catch (cancelled: CancellationException) {
                        connectionDesired = false
                        cancelReconnectWork()
                        throw cancelled
                    } catch (t: Throwable) {
                        connectionDesired = false
                        cancelReconnectWork()
                        logger.e("SessionManager: initial connection start failed", t)
                    }
                }
            }
        }
    }

    fun toggleMic() = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                micMutex.withLock {
                    val physicallyActive =
                        _state.value.isMicActive ||
                            audioEngine.isCapturing.value

                    if (physicallyActive) {
                        userMicDesired = false
                        stopMicLocked()
                        return@withLock
                    }

                    userMicDesired = !userMicDesired
                    if (!userMicDesired) {
                        _state.update { it.copy(error = null) }
                        return@withLock
                    }

                    if (connectionDesired &&
                        _state.value.link == LinkState.LIVE &&
                        client.isReady
                    ) {
                        startMicLocked()
                    } else {
                        _state.update {
                            it.copy(
                                error =
                                    "Сессия ещё не готова: микрофон будет запущен после подключения"
                            )
                        }
                    }
                }
            }
        }
    }

    fun stopSession() = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                connectionDesired = false
                userMicDesired = false
                stopInternal(full = true)
            }
        }
    }

    fun applyPrompt(newPrompt: String) = scope.launch {
        commandMutex.withLock {
            mutex.withLock {
                val changed = _state.value.activePrompt != newPrompt
                if (!changed) return@withLock

                val shouldRestart = _state.value.link != LinkState.IDLE
                _state.update { it.copy(activePrompt = newPrompt) }

                if (!shouldRestart || !connectionDesired) return@withLock

                resumptionHandle = null
                resumptionPersistenceMutex.withLock {
                    dataStore.edit {
                        it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                        it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                    }
                }
                cancelReconnectWork()
                stopInternal(full = false)

                if (connectionDesired) {
                    try {
                        startInternal(resume = false)
                    } catch (cancelled: CancellationException) {
                        connectionDesired = false
                        cancelReconnectWork()
                        throw cancelled
                    } catch (t: Throwable) {
                        connectionDesired = false
                        cancelReconnectWork()
                        logger.e("SessionManager: prompt restart failed", t)
                    }
                }
            }
        }
    }

    private suspend fun invalidateAndFlushAudio(reason: String): Long =
        withContext(Dispatchers.IO) {
            audioEngine.invalidateAndFlushPlayback(reason)
        }

    fun sendText(text: String, uris: List<Uri> = emptyList()) = scope.launch {
        commandMutex.withLock {
            userTurnMutex.withLock {
                val trimmed = text.trim()
                if (trimmed.isEmpty() && uris.isEmpty()) return@withLock

                if (_state.value.isAiSpeaking) {
                    invalidateAndFlushAudio("user text")
                    _state.update { it.copy(isAiSpeaking = false) }
                }

                if (uris.isNotEmpty()) {
                    handleAttachments(trimmed, uris)
                    return@withLock
                }

                addMessage(ChatMessage(role = ClientRole.USER, text = trimmed))
                resetTranscriptRuntime()
                turnCompleteSeen = false
                interactionStatus = null

                if (!ensureLive()) {
                    _state.update { it.copy(error = "Нет соединения с сервером") }
                    return@withLock
                }

                client.sendClientContent(
                    turns = listOf(ClientTurn(role = ClientRole.USER, text = trimmed)),
                    turnComplete = true
                )
            }
        }
    }

    fun playForvo(word: ForvoWord) = scope.launch {
        commandMutex.withLock {
            val url = forvoRepo.freshUrl(word.query, word.language) ?: run {
                _state.update { it.copy(error = "Ссылка Forvo недоступна или устарела") }
                return@launch
            }

            val wasMic = _state.value.isMicActive
            if (wasMic) stopMic(userInitiated = false)

            invalidateAndFlushAudio("Forvo playback")
            forvoPlayer.play(url)

            if (wasMic && userMicDesired && connectionDesired) {
                delay(200)
                startMic()
            }
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
            val apiKey = decryptStoredValue(
                prefs[KEY_API]?.trim().orEmpty(),
                "Gemini API key"
            ).orEmpty()
            val forvoOn = prefs[KEY_ENABLE_FORVO] ?: false

            val displayText = text.ifEmpty { "Изучи приложенный документ." }

            addMessage(
                ChatMessage(
                    role = ClientRole.USER,
                    text = displayText,
                    attachmentNames = processed.accepted,
                    modelReplayText = processed.extractedText.takeIf { it.isNotBlank() }?.take(15000)
                )
            )

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
                    synchronized(transcriptLock) {
                        _state.update { current ->
                            val idx = current.messages.indexOfLast {
                                it.role == ClientRole.USER &&
                                    it.attachmentNames == processed.accepted &&
                                    it.text == displayText
                            }
                            if (idx < 0) current else {
                                val updated = current.messages.toMutableList()
                                updated[idx] = updated[idx].copy(
                                    modelReplayText = a.fullText.take(15000)
                                )
                                current.copy(messages = updated)
                            }
                        }
                    }
                    if (forvoOn && a.vocabulary.isNotEmpty()) {
                        scope.launch { resolveForvo(a.vocabulary, a.language) }
                    }
                    if (!ensureLive()) return
                    client.sendClientContent(
                        turns = listOf(
                            ClientTurn(
                                role = ClientRole.USER,
                                text = a.fullText.take(15000)
                            )
                        ),
                        turnComplete = true
                    )
                }

                is AnalysisResult.Failure -> {
                    _state.update {
                        it.copy(error = "Ошибка анализа: ${result.reason}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("Attachment error", e)
            _state.update {
                it.copy(error = "Сбой обработки файлов: ${e.localizedMessage}")
            }
        } finally {
            _state.update { it.copy(isAnalyzing = false) }
        }
    }

    private suspend fun ensureLive(): Boolean {
        val targetEpoch = mutex.withLock {
            if (client.isReady && _state.value.link == LinkState.LIVE) {
                return@withLock client.epoch
            }

            if (_state.value.link == LinkState.IDLE) {
                connectionDesired = true
                try {
                    startInternal(resume = false)
                } catch (cancelled: CancellationException) {
                    connectionDesired = false
                    cancelReconnectWork()
                    throw cancelled
                } catch (t: Throwable) {
                    connectionDesired = false
                    cancelReconnectWork()
                    logger.e("SessionManager: ensureLive startup failed", t)
                    return@withLock null
                }
            }

            if (!connectionDesired || _state.value.link == LinkState.IDLE) {
                return@withLock null
            }

            client.epoch
        } ?: return false

        return withTimeoutOrNull(8000L) {
            while (true) {
                val readyForTarget = mutex.withLock {
                    connectionDesired &&
                        _state.value.link == LinkState.LIVE &&
                        client.isReady &&
                        client.epoch == targetEpoch
                }

                if (readyForTarget) return@withTimeoutOrNull true
                delay(40)
            }
        } == true
    }

    private fun buildForvoToolDeclaration(): JsonObject = buildJsonObject {
        putJsonArray("functionDeclarations") {
            addJsonObject {
                put("name", "lookup_pronunciation")
                put(
                    "description",
                    "Запрашивает аудиозаписи произношения слов носителями языка из базы Forvo."
                )
                put("behavior", "NON_BLOCKING")

                putJsonObject("parameters") {
                    put("type", "OBJECT")

                    putJsonObject("properties") {
                        putJsonObject("words") {
                            put("type", "ARRAY")
                            putJsonObject("items") { put("type", "STRING") }
                            put(
                                "description",
                                "Список слов для поиска произношения."
                            )
                        }

                        putJsonObject("language") {
                            put("type", "STRING")
                            put(
                                "description",
                                "Двухбуквенный код языка ISO 639-1 (например, 'de', 'en', 'fr', 'es'). По умолчанию 'de'."
                            )
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

        val candidates = raw
            .takeLast(maxTurns.coerceIn(1, 100))
            .map {
                val replayText = it.modelReplayText?.takeIf { value -> value.isNotBlank() } ?: it.text
                ClientTurn(it.role, replayText.trim())
            }

        val firstUser = candidates.indexOfFirst { it.role == ClientRole.USER }
        if (firstUser < 0) return emptyList()

        val maxHistoryChars = (131_072 * 3)
            .coerceAtMost(240_000)
        val out = ArrayDeque<ClientTurn>()
        var chars = 0

        for (turn in candidates.drop(firstUser).asReversed()) {
            val cost = turn.text.length
            if (out.isNotEmpty() && chars + cost > maxHistoryChars) break
            if (out.isEmpty() && cost > maxHistoryChars) {
                out.addFirst(turn.copy(text = turn.text.take(maxHistoryChars)))
                break
            }
            out.addFirst(turn)
            chars += cost
        }
        return out.toList()
    }

    private suspend fun startInternal(resume: Boolean) {
        if (!resume) {
            resumptionHandle = null
        } else if (resumptionHandle.isNullOrBlank()) {
            val prefs = dataStore.data.first()
            val storedResumeHandle = decryptStoredValue(
                prefs[KEY_SESSION_RESUMPTION_HANDLE]?.trim().orEmpty(),
                "Live session resumption handle"
            ).orEmpty()
            if (storedResumeHandle.isNotBlank() && prefs[KEY_SESSION_RESUMPTION_ENABLED] != false) {
                resumptionHandle = storedResumeHandle
            }
        }
        activeConnectUsedResumption = resume && (resumptionHandle?.isNotBlank() == true)

        synchronized(reconnectGuard) {
            goAwayJob?.cancel()
            goAwayJob = null
        }

        pendingGoAway = false
        resetTranscriptRuntime()
        isManualActivityActive.set(false)

        val prefs = dataStore.data.first()
        val encryptedApiKey = prefs[KEY_API]?.trim().orEmpty()
        val apiKeyResult = decryptStoredValue(
            encryptedApiKey,
            "Gemini API key"
        )

        if (apiKeyResult == null && encryptedApiKey.isNotBlank()) {
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    error = "Сохранённый Gemini API Key повреждён или недоступен. Введите ключ заново.",
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
            return
        }

        val apiKey = apiKeyResult.orEmpty()

        if (apiKey.isEmpty()) {
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    error = "Укажите Gemini API Key в Настройках",
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false
                )
            }
            return
        }

        val liveModel = prefs[KEY_LIVE_MODEL]
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: DEFAULT_LIVE_MODEL

        val liveCapabilities =
            LiveModelCapabilitiesRegistry.forModel(liveModel)
        activeLiveCapabilities = liveCapabilities

        val thinkingLevel =
            if (
                liveCapabilities.supportsThinkingConfig &&
                liveModel
                    .removePrefix("models/")
                    .equals(
                        "gemini-3.8-live-extended-thinking",
                        ignoreCase = true
                    )
            ) {
                prefs[KEY_THINKING_LEVEL]
                    ?.trim()
                    ?.lowercase()
                    ?.takeIf {
                        it == "low" ||
                            it == "medium" ||
                            it == "high"
                    }
                    ?: "low"
            } else {
                null
            }

        val voice = prefs[KEY_VOICE]?.ifBlank { null } ?: "Charon"
        val speechLang = prefs[KEY_SPEECH_LANGUAGE]
            ?.takeIf { it.isNotBlank() }
        val temperature = prefs[KEY_TEMPERATURE] ?: 0.5f
        val mediaResolution =
            prefs[KEY_MEDIA_RESOLUTION] ?: "MEDIA_RESOLUTION_HIGH"

        val inputTx = TranscriptionSettings(
            enabled = prefs[KEY_INPUT_TRANSCRIPTION_ENABLED] ?: true,
            languageCodes =
                prefs[KEY_INPUT_TRANSCRIPTION_LANGUAGES]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
            customVocabulary =
                prefs[KEY_INPUT_TRANSCRIPTION_VOCAB]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(1000)
                    ?: emptyList(),
            mode = prefs[KEY_INPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        currentOutputTranscriptionEnabled =
            prefs[KEY_OUTPUT_TRANSCRIPTION_ENABLED] ?: true

        val outputTx = TranscriptionSettings(
            enabled = currentOutputTranscriptionEnabled,
            languageCodes =
                prefs[KEY_OUTPUT_TRANSCRIPTION_LANGUAGES]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: emptyList(),
            customVocabulary =
                prefs[KEY_OUTPUT_TRANSCRIPTION_VOCAB]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(1000)
                    ?: emptyList(),
            mode = prefs[KEY_OUTPUT_TRANSCRIPTION_MODE] ?: "VERBATIM"
        )

        val aadEnabled = prefs[KEY_AAD_ENABLED] ?: true
        currentAadEnabled = aadEnabled

        audioEngine.isAadMode = aadEnabled

        val realtimeInput = RealtimeInputSettings(
            aadEnabled = aadEnabled,
            startSensitivity =
                prefs[KEY_AAD_START_SENSITIVITY]
                    ?: "START_SENSITIVITY_HIGH",
            endSensitivity =
                prefs[KEY_AAD_END_SENSITIVITY]
                    ?: "END_SENSITIVITY_LOW",
            prefixPaddingMs =
                prefs[KEY_PREFIX_PADDING_MS] ?: 60,
            silenceDurationMs =
                prefs[KEY_SILENCE_DURATION_MS] ?: 600,
            activityHandling =
                prefs[KEY_ACTIVITY_HANDLING]
                    ?: "START_OF_ACTIVITY_INTERRUPTS",
            turnCoverage =
                prefs[KEY_TURN_COVERAGE]
                    ?: "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO"
        )

        val compression = CompressionSettings(
            enabled = prefs[KEY_COMPRESSION_ENABLED] ?: true,
            triggerTokens =
                prefs[KEY_COMPRESSION_TRIGGER_TOKENS] ?: 0,
            targetTokens =
                prefs[KEY_COMPRESSION_TARGET_TOKENS] ?: 0
        )

        val resumptionEnabled =
            prefs[KEY_SESSION_RESUMPTION_ENABLED] ?: true
        val maxHistoryTurns =
            prefs[KEY_INITIAL_HISTORY_TURNS] ?: 20

        audioEngine.setVolume(
            prefs[KEY_VOLUME] ?: 1.0f
        )
        audioEngine.setMicGain(
            prefs[KEY_MIC_GAIN] ?: 1.0f
        )

        val startingFreshSession =
            !resume &&
                _state.value.link == LinkState.IDLE

        if (startingFreshSession) {
            val foregroundServiceError = ensureForegroundServiceActive()
            if (foregroundServiceError != null) {
                audioEngine.stop()
                connectionDesired = false
                cancelReconnectWork()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = foregroundServiceError
                    )
                }
                return
            }
        } else if (!LiveSessionForegroundService.isServiceActive.value) {
            audioEngine.stop()
            connectionDesired = false
            userMicDesired = false
            cancelReconnectWork()
            _state.update {
                it.copy(
                    link = LinkState.IDLE,
                    isMicActive = false,
                    isAiSpeaking = false,
                    error = "Сессия остановлена: запустите её из приложения снова"
                )
            }
            return
        }

        if (!audioEngine.startPlayback()) {
            audioEngine.stop()
            if (startingFreshSession) {
                connectionDesired = false
                cancelReconnectWork()
                stopForegroundService()
            }
            _state.update {
                it.copy(
                    error = "Сбой инициализации аудиодрайвера",
                    link = LinkState.IDLE
                )
            }
            return
        }

        _state.update {
            it.copy(
                link = if (resume) {
                    LinkState.RECONNECTING
                } else {
                    LinkState.CONNECTING
                },
                error = null
            )
        }

        val forvoEnabled =
            prefs[KEY_ENABLE_FORVO] ?: false
        val searchEnabled =
            prefs[KEY_ENABLE_SEARCH] ?: false

        val dynamicTools =
            if (forvoEnabled) {
                buildJsonArray {
                    add(buildForvoToolDeclaration())
                }
            } else {
                null
            }

        try {
            client.connect(
                LiveConfig(
                    apiKey = apiKey,
                    model = liveModel,
                    systemInstruction = _state.value.activePrompt,
                    voiceName = voice,
                    speechLanguage = speechLang,
                    temperature = temperature,
                    mediaResolution = mediaResolution,
                    thinkingLevel = thinkingLevel,
                    inputTranscription = inputTx,
                    outputTranscription = outputTx,
                    realtimeInput = realtimeInput,
                    compression = compression,
                    sessionResumptionEnabled = resumptionEnabled,
                    resumptionHandle =
                        if (resume && resumptionEnabled) {
                            resumptionHandle
                        } else {
                            null
                        },
                    toolsJson = dynamicTools,
                    enableGoogleSearch = searchEnabled,
                    initialHistory =
                        if (resume) {
                            emptyList()
                        } else {
                            recentHistory(maxHistoryTurns)
                        }
                ),
                beforeOpen = {
                    activeSessionId = client.sessionId
                    interactionStatus = null
                    turnCompleteSeen = false
                    invalidateAndFlushAudio(
                        if (resume) "Live reconnect" else "Live session start"
                    )
                }
            )
        } catch (cancelled: CancellationException) {
            runCatching { client.disconnect() }
            if (startingFreshSession) {
                connectionDesired = false
                cancelReconnectWork()
                runCatching { invalidateAndFlushAudio("connect cancellation") }
                audioEngine.stop()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = "Подключение отменено"
                    )
                }
            }
            throw cancelled
        } catch (t: Throwable) {
            logger.e(
                "SessionManager: Live connection setup failed",
                t
            )

            runCatching { client.disconnect() }

            if (startingFreshSession) {
                runCatching { invalidateAndFlushAudio("connect cancellation") }
                audioEngine.stop()
                stopForegroundService()
                _state.update {
                    it.copy(
                        link = LinkState.IDLE,
                        isMicActive = false,
                        isAiSpeaking = false,
                        error = "Не удалось подключиться к Gemini: ${t.localizedMessage.orEmpty()}"
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        link = LinkState.RECONNECTING,
                        isMicActive = false,
                        isAiSpeaking = false
                    )
                }
            }

            throw t
        }
    }

    private suspend fun stopInternal(full: Boolean) {
        pendingGoAway = false
        resetTranscriptRuntime()

        cancelReconnectWork()
        cancelAllPendingToolJobs()

        withContext(NonCancellable) {
            try {
                micMutex.withLock {
                    stopMicLocked()
                }
            } catch (t: Throwable) {
                logger.e("SessionManager: microphone shutdown failed", t)
            }

            try {
                client.disconnect()
            } catch (t: Throwable) {
                logger.e("SessionManager: transport shutdown failed", t)
            }

            try {
                invalidateAndFlushAudio(
                    if (full) "session shutdown" else "session reset"
                )
            } catch (t: Throwable) {
                logger.e("SessionManager: playback flush during shutdown failed", t)
            }

            isManualActivityActive.set(false)

            if (full) {
                reconnectAttempts = 0
                resumptionHandle = null
                activeConnectUsedResumption = false
                resumptionPersistenceMutex.withLock {
                    dataStore.edit {
                        it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                        it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                    }
                }

                try {
                    audioEngine.stop()
                } catch (t: Throwable) {
                    logger.e("SessionManager: audio engine shutdown failed", t)
                }

                try {
                    stopForegroundService()
                } catch (t: Throwable) {
                    logger.e("SessionManager: foreground service shutdown failed", t)
                }
            }
        }

        _state.update {
            it.copy(
                link = LinkState.IDLE,
                isAiSpeaking = false,
                isMicActive = false
            )
        }
    }

    private fun cancelAllPendingToolJobs() {
        activeToolJobs.values.forEach { it.cancel() }
        activeToolJobs.clear()
        cancelledToolCallKeys.clear()
    }

    private fun cancelReconnectWork() {
        synchronized(reconnectGuard) {
            reconnectToken.incrementAndGet()
            goAwayToken.incrementAndGet()

            reconnectJob?.cancel()
            reconnectJob = null

            goAwayJob?.cancel()
            goAwayJob = null
        }

        pendingGoAway = false
    }

    private fun scheduleReconnect(
        reason: String,
        sourceEpoch: Long = client.epoch,
        immediate: Boolean = false
    ) {
        if (!connectionDesired) return

        val token: Long

        synchronized(reconnectGuard) {
            if (
                !connectionDesired ||
                _state.value.link == LinkState.IDLE ||
                client.epoch != sourceEpoch ||
                reconnectJob?.isActive == true
            ) {
                return
            }

            if (immediate) {
                reconnectAttempts = 0
            }

            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                val terminalToken = reconnectToken.incrementAndGet()

                reconnectJob?.cancel()
                reconnectJob = scope.launch {
                    try {
                        mutex.withLock {
                            if (
                                terminalToken != reconnectToken.get() ||
                                !connectionDesired ||
                                _state.value.link == LinkState.IDLE
                            ) {
                                return@withLock
                            }

                            connectionDesired = false
                            userMicDesired = false
                            stopInternal(full = true)

                            _state.update {
                                it.copy(
                                    error = "Соединение потеряно: $reason"
                                )
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logger.e(
                            "SessionManager: terminal reconnect shutdown failed",
                            t
                        )
                    } finally {
                        synchronized(reconnectGuard) {
                            if (reconnectJob == coroutineContext[Job]) {
                                reconnectJob = null
                            }
                        }
                    }
                }
                return
            }

            token = reconnectToken.incrementAndGet()

            reconnectJob = scope.launch {
                try {
                    val attempt = synchronized(reconnectGuard) {
                        if (
                            token != reconnectToken.get() ||
                            !connectionDesired
                        ) {
                            return@launch
                        }
                        if (immediate) 0 else ++reconnectAttempts
                    }

                    val stateAccepted = mutex.withLock {
                        if (
                            token != reconnectToken.get() ||
                            !connectionDesired ||
                            client.epoch != sourceEpoch ||
                            _state.value.link == LinkState.IDLE
                        ) {
                            false
                        } else {
                            _state.update {
                                it.copy(link = LinkState.RECONNECTING)
                            }
                            true
                        }
                    }
                    if (!stateAccepted) return@launch

                    if (!immediate) {
                        val baseDelay =
                            minOf(
                                400L *
                                    (
                                        1L shl
                                            (attempt - 1)
                                                .coerceAtMost(4)
                                    ),
                                6000L
                            )

                        val jitteredDelay =
                            (
                                baseDelay *
                                    (
                                        0.8 +
                                            Math.random() *
                                            0.4
                                    )
                                ).toLong()

                        delay(jitteredDelay)
                    }

                    if (
                        token != reconnectToken.get() ||
                        !connectionDesired ||
                        client.epoch != sourceEpoch ||
                        _state.value.link == LinkState.IDLE
                    ) {
                        return@launch
                    }

                    mutex.withLock {
                        if (
                            token == reconnectToken.get() &&
                            connectionDesired &&
                            client.epoch == sourceEpoch &&
                            _state.value.link != LinkState.IDLE
                        ) {
                            val useResume =
                                resumptionHandle != null

                            startInternal(
                                resume = useResume
                            )

                            synchronized(reconnectGuard) {
                                if (token == reconnectToken.get()) {
                                    reconnectJob = null
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    logger.e(
                        "SessionManager: reconnect attempt failed",
                        t
                    )

                    synchronized(reconnectGuard) {
                        if (token == reconnectToken.get()) {
                            reconnectJob = null
                        }
                    }

                    val retryEpoch = mutex.withLock {
                        if (
                            token != reconnectToken.get() ||
                            !connectionDesired ||
                            _state.value.link == LinkState.IDLE
                        ) {
                            return@withLock null
                        }

                        activeConnectUsedResumption = false

                        client.epoch
                    }

                    retryEpoch?.let { epoch ->
                        scheduleReconnect(
                            "ошибка попытки reconnect: ${t.localizedMessage}",
                            epoch
                        )
                    }
                } finally {
                    synchronized(reconnectGuard) {
                        if (token == reconnectToken.get()) {
                            reconnectJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun finalizeMicActivityBounded() {
        withTimeoutOrNull(500L) {
            if (currentAadEnabled) {
                client.sendAudioStreamEnd()
            } else {
                if (
                    isManualActivityActive.compareAndSet(
                        true,
                        false
                    ) &&
                    client.isReady
                ) {
                    client.sendActivityEnd()
                }
            }
        } ?: logger.w(
            "SessionManager: bounded mic activity finalization timed out"
        )
    }

    private suspend fun startMic() = mutex.withLock {
        micMutex.withLock {
            startMicLocked()
        }
    }

    private suspend fun startMicLocked() {
        if (
            !userMicDesired ||
            !connectionDesired ||
            _state.value.link != LinkState.LIVE ||
            !client.isReady
        ) {
            return
        }

        if (
            _state.value.isMicActive ||
            audioEngine.isCapturing.value
        ) {
            return
        }

        isManualActivityActive.set(false)

        if (!LiveSessionForegroundService.ensureMicrophoneForegroundType()) {
            _state.update {
                it.copy(error = "Не удалось активировать microphone foreground-service type")
            }
            return
        }

        if (!audioEngine.startCapture()) {
            _state.update {
                it.copy(error = "Микрофон недоступен")
            }
            return
        }

        _state.update { it.copy(isMicActive = true) }

        micJob?.cancel()
        micJob = scope.launch {
            try {
                for (event in audioEngine.micOutput) {
                    if (!isActive) break

                    when (event) {
                        is AudioStreamEvent.SpeechStart -> {
                            if (!currentAadEnabled && client.isReady) {
                                if (isManualActivityActive.compareAndSet(false, true)) {
                                    logger.d(
                                        "SessionManager: VAD SpeechStart (Manual VAD) -> sendActivityStart"
                                    )
                                    client.sendActivityStart()
                                }
                            }
                        }

                        is AudioStreamEvent.Audio -> {
                            try {
                                if (
                                    !forvoPlayer.isPlaying.value &&
                                    (currentAadEnabled || isManualActivityActive.get()) &&
                                    connectionDesired &&
                                    client.isReady
                                ) {
                                    client.sendAudioPcm(event.pcm)
                                }
                            } finally {
                                audioEngine.releaseCapturedBuffer(event.pcm)
                            }
                        }

                        is AudioStreamEvent.SpeechEnd -> {
                            if (!currentAadEnabled &&
                                isManualActivityActive.compareAndSet(true, false) &&
                                client.isReady
                            ) {
                                withTimeoutOrNull(500L) {
                                    client.sendActivityEnd()
                                } ?: logger.w(
                                    "SessionManager: SpeechEnd activityEnd timed out"
                                )
                            }
                        }

                        is AudioStreamEvent.StreamStop -> {
                            finalizeMicActivityBounded()
                            _state.update { it.copy(isMicActive = false) }
                            break
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e("SessionManager: mic consumer crashed", t)
                _state.update {
                    it.copy(
                        isMicActive = false,
                        error = "Ошибка обработки микрофона: ${t.localizedMessage}"
                    )
                }
            }
        }
    }

    private suspend fun stopMic(
        userInitiated: Boolean = false
    ) = mutex.withLock {
        micMutex.withLock {
            if (userInitiated) {
                userMicDesired = false
            }
            stopMicLocked()
        }
    }

    private suspend fun stopMicLocked() {
        val hasPhysicalCapture =
            _state.value.isMicActive || audioEngine.isCapturing.value

        if (hasPhysicalCapture) {
            val producerResult = audioEngine.stopCaptureGraceful(1500L)
            if (producerResult == CaptureShutdownResult.FORCED_TIMEOUT) {
                logger.w("SessionManager: producer shutdown forced")
            }
        }

        val consumerJob = micJob
        var consumerCompleted = true

        if (consumerJob != null) {
            consumerCompleted = withTimeoutOrNull(1500L) {
                consumerJob.join()
                true
            } ?: false

            if (!consumerCompleted) {
                logger.w(
                    "SessionManager: mic consumer timeout; forcing bounded cancellation"
                )
                consumerJob.cancel()
                consumerCompleted =
                    withTimeoutOrNull(200L) {
                        consumerJob.join()
                        true
                    } ?: false

                if (!consumerCompleted) {
                    logger.e(
                        "SessionManager: mic consumer did not terminate after cancellation"
                    )
                    finalizeMicActivityBounded()
                }
            }
        }

        micJob = null
        audioEngine.drainPendingMicOutput()
        isManualActivityActive.set(false)
        _state.update { it.copy(isMicActive = false) }
    }

    private fun observeAudio() = scope.launch {
        while (isActive) {
            try {
                for (frame in client.audio) {
                    if (!isActive) break

                    try {
                        if (frame.sessionId != client.sessionId) continue
                        if (frame.epoch != client.epoch) continue
                        if (frame.generation != audioEngine.currentPlaybackGeneration) continue

                        _state.update {
                            it.copy(isAiSpeaking = true)
                        }

                        audioEngine.enqueuePlayback(
                            frame.pcm,
                            frame.generation
                        )
                    } finally {
                        client.releaseAudio(frame)
                    }
                }

                if (!isActive) break
                delay(50L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e(
                    "SessionManager: audio observer crashed; restarting consumer",
                    t
                )
                val shouldRecover = mutex.withLock {
                    if (connectionDesired && _state.value.link != LinkState.IDLE) {
                        _state.update { it.copy(link = LinkState.RECONNECTING, isAiSpeaking = false) }
                        true
                    } else false
                }
                if (shouldRecover) runCatching { client.disconnect() }
                delay(100L)
            }
        }
    }

    private fun observeBargeIn() = scope.launch {
        audioEngine.bargeInEvents.collect {
            invalidateAndFlushAudio("local barge-in")
            audioEngine.triggerBargeInEarcon()

            _state.update {
                it.copy(isAiSpeaking = false)
            }

            resetTranscriptRuntime()
        }
    }

    private fun observeFocus() = scope.launch {
        audioEngine.focusEvents.collect { event ->
            when (event) {
                AudioFocusEvent.Gain -> {
                    mutex.withLock {
                        if (connectionDesired && _state.value.link == LinkState.LIVE) {
                            if (!audioEngine.isPlaying.value) {
                                runCatching { audioEngine.startPlayback() }
                                    .onFailure { logger.w("SessionManager: не удалось восстановить playback после AudioFocus gain: ${it.message}") }
                            }
                            if (userMicDesired && client.isReady) {
                                micMutex.withLock {
                                    if (!_state.value.isMicActive && !audioEngine.isCapturing.value) {
                                        startMicLocked()
                                    }
                                }
                            }
                            _state.update { it.copy(error = null) }
                        }
                    }
                }
                AudioFocusEvent.LossPermanent, AudioFocusEvent.LossTransient -> {
                    mutex.withLock {
                        micMutex.withLock {
                            stopMicLocked()
                        }
                        invalidateAndFlushAudio("audio focus loss")
                        _state.update {
                            it.copy(
                                isAiSpeaking = false,
                                error = if (event == AudioFocusEvent.LossTransient) {
                                    "Аудио временно прервано другим приложением или вызовом"
                                } else {
                                    "Аудио прервано потерей аудиофокуса"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun observeEvents() = scope.launch {
        while (isActive) {
            try {
                client.events.collect { envelope ->
                    val event = envelope.event
                    val eventEpoch = envelope.epoch
                    val eventSessionId = envelope.sessionId

                    if (
                        eventSessionId != client.sessionId ||
                        eventEpoch != client.epoch
                    ) {
                        return@collect
                    }

                    activeSessionId = eventSessionId

                    // Fast-path (Zero Lock Contention): Высокочастотные события печатного текста
                    // и транскрипций обрабатываются немедленно без захвата жизненного mutex.
                    // Целостность состояния гарантирована transcriptLock и атомарным Flow update.
                    when (event) {
                        is GeminiEvent.ModelText -> {
                            appendTranscript(
                                ClientRole.MODEL,
                                event.text,
                                false,
                                envelope.generationId
                            )
                        }

                        is GeminiEvent.InputTranscript -> {
                            appendTranscript(
                                ClientRole.USER,
                                event.text,
                                event.interim,
                                envelope.generationId
                            )
                        }

                        is GeminiEvent.OutputTranscript -> {
                            recordTranscriptGeneration(envelope.generationId)

                            appendTranscript(
                                ClientRole.MODEL,
                                event.text,
                                false,
                                envelope.generationId
                            )
                        }

                        is GeminiEvent.Usage -> {
                            _state.update {
                                it.copy(
                                    tokensUsed = event.totalTokens
                                )
                            }
                        }

                        else -> {
                            // Control Plane & Lifecycle: события управления сессией, требующие
                            // атомарной синхронизации с жизненным циклом через mutex.
                            mutex.withLock {
                                if (
                                    envelope.sessionId != client.sessionId ||
                                    envelope.epoch != client.epoch
                                ) {
                                    return@withLock
                                }

                                when (event) {
                                    is GeminiEvent.SetupComplete -> {
                                        reconnectAttempts = 0
                                        pendingGoAway = false
                                        goAwayToken.incrementAndGet()
                                        interactionStatus = null
                                        turnCompleteSeen = false

                                        synchronized(reconnectGuard) {
                                            goAwayJob?.cancel()
                                            goAwayJob = null
                                        }

                                        _state.update {
                                            it.copy(
                                                link = LinkState.LIVE,
                                                error = null
                                            )
                                        }

                                        if (
                                            userMicDesired &&
                                            connectionDesired
                                        ) {
                                            micMutex.withLock {
                                                startMicLocked()
                                            }
                                        }
                                    }

                                    is GeminiEvent.ResumptionHandle -> {
                                        val usableHandle =
                                            event.handle
                                                ?.trim()
                                                ?.takeIf {
                                                    event.resumable &&
                                                        it.isNotBlank()
                                                }

                                        if (usableHandle != null) {
                                            resumptionHandle = usableHandle
                                            resumptionPersistenceMutex.withLock {
                                                when (val encrypted = cryptoManager.encrypt(usableHandle)) {
                                                    is com.client.app.util.CryptoResult.Success -> {
                                                        dataStore.edit { prefs ->
                                                            prefs[KEY_SESSION_RESUMPTION_HANDLE] =
                                                                encrypted.value
                                                            prefs[KEY_SESSION_RESUMPTION_TIMESTAMP] =
                                                                System.currentTimeMillis()
                                                        }
                                                    }
                                                    com.client.app.util.CryptoResult.Missing -> {
                                                        logger.w("SessionManager: resumption handle unexpectedly encrypted as missing")
                                                    }
                                                    is com.client.app.util.CryptoResult.Failure -> {
                                                        logger.e(
                                                            "SessionManager: could not persist Live session resumption handle: ${encrypted.reason}",
                                                            encrypted.cause
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        activeConnectUsedResumption = false
                                    }

                                    is GeminiEvent.InteractionStatus -> {
                                        interactionStatus =
                                            event.status.trim().uppercase()

                                        if (
                                            interactionStatus ==
                                            "IN_PROGRESS"
                                        ) {
                                            _state.update {
                                                it.copy(isAiSpeaking = true)
                                            }
                                        } else if (
                                            interactionStatus == "IDLE" &&
                                            clientCapabilitiesForCurrentSession()
                                                .supportsInteractionStatus
                                        ) {
                                            finishInteractionAfterPlayback(
                                                sourceSessionId = eventSessionId,
                                                sourceEpoch = eventEpoch,
                                                shouldPlanGoAway = pendingGoAway,
                                                capabilities =
                                                    clientCapabilitiesForCurrentSession()
                                            )
                                        }
                                    }

                                    is GeminiEvent.GoAway -> {
                                        val sourceEpoch = eventEpoch
                                        val timerToken =
                                            goAwayToken.incrementAndGet()

                                        pendingGoAway = true

                                        synchronized(reconnectGuard) {
                                            goAwayJob?.cancel()

                                            goAwayJob = scope.launch {
                                                try {
                                                    val millisLeft = event.millisLeft ?: 0L
                                                    delay(millisLeft.coerceAtLeast(0L))

                                                    if (
                                                        timerToken ==
                                                            goAwayToken.get() &&
                                                        pendingGoAway &&
                                                        connectionDesired &&
                                                        client.epoch ==
                                                            sourceEpoch
                                                    ) {
                                                        pendingGoAway = false
                                                        goAwayToken.incrementAndGet()

                                                        scheduleReconnect(
                                                            "дедлайн goAway",
                                                            sourceEpoch
                                                        )
                                                    }
                                                } catch (cancelled: CancellationException) {
                                                    throw cancelled
                                                } catch (t: Throwable) {
                                                    logger.e(
                                                        "SessionManager: GoAway scheduler failed",
                                                        t
                                                    )
                                                } finally {
                                                    synchronized(reconnectGuard) {
                                                        if (
                                                            goAwayJob ==
                                                            coroutineContext[Job]
                                                        ) {
                                                            goAwayJob = null
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    is GeminiEvent.Interrupted -> {
                                        invalidateAndFlushAudio(
                                            "server interrupted"
                                        )

                                        audioEngine.resetBargeInState()

                                        _state.update {
                                            it.copy(isAiSpeaking = false)
                                        }

                                        resetTranscriptRuntime()
                                        turnCompleteSeen = false
                                        interactionStatus = null
                                    }

                                    is GeminiEvent.GenerationComplete -> {
                                    }

                                    is GeminiEvent.TurnComplete -> {
                                        turnCompleteSeen = true
                                        val capabilities =
                                            clientCapabilitiesForCurrentSession()

                                        if (
                                            !capabilities.supportsInteractionStatus ||
                                            interactionStatus == "IDLE"
                                        ) {
                                            finishInteractionAfterPlayback(
                                                sourceSessionId =
                                                    eventSessionId,
                                                sourceEpoch = eventEpoch,
                                                shouldPlanGoAway =
                                                    pendingGoAway,
                                                capabilities = capabilities
                                            )
                                        }
                                    }

                                    is GeminiEvent.ToolCall -> {
                                        handleToolCall(
                                            calls = event.calls,
                                            sourceSessionId = eventSessionId,
                                            sourceEpoch = eventEpoch
                                        )
                                    }

                                    is GeminiEvent.ToolCallCancelled -> {
                                        event.ids.forEach { id ->
                                            val key =
                                                ToolCallKey(
                                                    sessionId =
                                                        eventSessionId,
                                                    epoch =
                                                        eventEpoch,
                                                    callId = id
                                                )

                                            toolResponseMutex.withLock {
                                                cancelledToolCallKeys.add(key)
                                                activeToolJobs
                                                    .remove(key)
                                                    ?.cancel()
                                            }
                                        }
                                    }

                                    is GeminiEvent.Error -> {
                                        _state.update {
                                            it.copy(
                                                error =
                                                    event.message
                                            )
                                        }

                                        if (event.fatal) {
                                            activeConnectUsedResumption =
                                                false
                                            connectionDesired = false
                                            userMicDesired = false
                                            cancelReconnectWork()
                                            stopInternal(full = true)
                                        }
                                    }

                                    is GeminiEvent.Disconnected -> {
                                        if (
                                            event.sessionId !=
                                            client.sessionId ||
                                            event.epoch !=
                                            client.epoch
                                        ) {
                                            return@withLock
                                        }

                                        cancelAllPendingToolJobs()

                                        val resumeRejected =
                                            activeConnectUsedResumption &&
                                                (
                                                    event.code == 400 ||
                                                    event.code == 403 ||
                                                    event.code == 404
                                                )

                                        if (resumeRejected) {
                                            logger.w(
                                                "SessionManager: Сервер отклонил сессионный токен (${event.code}). Сбрасываем хэндл и переключаемся на чистую сессию."
                                            )
                                            resumptionHandle = null
                                            activeConnectUsedResumption = false
                                            scope.launch {
                                                resumptionPersistenceMutex.withLock {
                                                    dataStore.edit { prefs ->
                                                        prefs.remove(KEY_SESSION_RESUMPTION_HANDLE)
                                                        prefs.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                                                    }
                                                }
                                            }
                                        }

                                        val authOrClientFatal = if (resumeRejected) {
                                            false
                                        } else {
                                            event.code == 400 ||
                                                event.code == 401 ||
                                                event.code == 403 ||
                                                event.code == 404
                                        }

                                        if (authOrClientFatal) {
                                            logger.e(
                                                "SessionManager: Фатальная ошибка клиента (${event.code}). Остановка сессии."
                                            )
                                            connectionDesired = false
                                            userMicDesired = false
                                            cancelReconnectWork()
                                            stopInternal(full = true)
                                            return@withLock
                                        }

                                        if (connectionDesired) {
                                            if (
                                                _state.value.isMicActive ||
                                                audioEngine.isCapturing.value
                                            ) {
                                                micMutex.withLock {
                                                    stopMicLocked()
                                                }
                                            }

                                            scheduleReconnect(
                                                reason =
                                                    if (resumeRejected) {
                                                        "resumption отклонён сервером (код ${event.code}), запуск чистой сессии"
                                                    } else {
                                                        "код ${event.code}"
                                                    },
                                                sourceEpoch =
                                                    event.epoch,
                                                immediate = resumeRejected
                                            )
                                        }
                                    }

                                    is GeminiEvent.Connected -> Unit

                                    is GeminiEvent.GroundingMetadata,
                                    is GeminiEvent.UrlContextMetadata -> Unit

                                    else -> Unit
                                }
                            }
                        }
                    }
                }

                if (!isActive) break
                delay(50L)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                logger.e(
                    "SessionManager: event observer crashed; restarting consumer",
                    t
                )
                val shouldRecover = mutex.withLock {
                    if (connectionDesired && _state.value.link != LinkState.IDLE) {
                        _state.update {
                            it.copy(
                                link = LinkState.RECONNECTING,
                                isAiSpeaking = false
                            )
                        }
                        true
                    } else false
                }
                if (shouldRecover) {
                    runCatching { client.disconnect() }
                }
                delay(100L)
            }
        }
    }

    private fun clientCapabilitiesForCurrentSession(): LiveModelCapabilities =
        activeLiveCapabilities

    private fun finishInteractionAfterPlayback(
        sourceSessionId: Long,
        sourceEpoch: Long,
        shouldPlanGoAway: Boolean,
        capabilities: LiveModelCapabilities
    ) {
        scope.launch {
            val generation = audioEngine.currentPlaybackGeneration

            // 1. Ожидаем, пока корутина observeAudio() перельёт остатки аудиопакетов сокета в C++ буфер
            val ingressDeadline = SystemClock.elapsedRealtime() + 1500L
            while (client.hasPendingAudioFrames && SystemClock.elapsedRealtime() < ingressDeadline) {
                if (client.sessionId != sourceSessionId || client.epoch != sourceEpoch || !connectionDesired) {
                    return@launch
                }
                delay(15L)
            }

            // 2. Адаптивное аппаратное ожидание опустошения очереди ЦАП
            val drained = audioEngine.awaitPlaybackDrained(generation = generation)
            if (!drained && audioEngine.currentPlaybackGeneration == generation) {
                logger.w("SessionManager: playback drain watchdog detected hardware stall; clearing stalled queue")
                invalidateAndFlushAudio("playback stall recovery")
            }

            // 3. Атомарный перевод состояния интерфейса в режим ожидания
            val shouldReconnect =
                mutex.withLock {
                    val lifecycleComplete =
                        if (capabilities.supportsInteractionStatus) {
                            interactionStatus == "IDLE"
                        } else {
                            turnCompleteSeen
                        }

                    if (
                        !connectionDesired ||
                        activeSessionId != sourceSessionId ||
                        client.sessionId != sourceSessionId ||
                        client.epoch != sourceEpoch ||
                        _state.value.link == LinkState.IDLE ||
                        !lifecycleComplete
                    ) {
                        return@withLock false
                    }

                    turnCompleteSeen = false
                    interactionStatus = null
                    resetTranscriptRuntime()
                    audioEngine.resetBargeInState()

                    _state.update {
                        it.copy(isAiSpeaking = false)
                    }

                    pendingGoAway = false
                    goAwayToken.incrementAndGet()

                    synchronized(reconnectGuard) {
                        goAwayJob?.cancel()
                        goAwayJob = null
                    }

                    shouldPlanGoAway
                }

            if (shouldReconnect) {
                scheduleReconnect(
                    "плановый переход goAway",
                    sourceEpoch
                )
            }
        }
    }

    private fun handleToolCall(
        calls: List<FunctionCall>,
        sourceSessionId: Long,
        sourceEpoch: Long
    ) {
        if (
            sourceSessionId != client.sessionId ||
            sourceEpoch != client.epoch
        ) return

        val currentSessionId = sourceSessionId
        val currentEpoch = sourceEpoch

        for (call in calls) {
            val callId = call.id

            if (callId.isNullOrBlank()) {
                logger.w("SessionManager: игнорирован tool call без id: ${call.name}")
                continue
            }

            val key = ToolCallKey(
                currentSessionId,
                currentEpoch,
                callId
            )

            if (call.name != "lookup_pronunciation") {
                client.sendToolResponses(
                    listOf(
                        ToolResponse(
                            name = call.name,
                            id = callId,
                            response = buildJsonObject {
                                put(
                                    "error",
                                    "unknown_tool"
                                )
                            },
                            scheduling =
                                FunctionResponseScheduling.SILENT
                        )
                    )
                )

                continue
            }

            val list = call.getStringList("words")
                .ifEmpty {
                    call.getString("words")
                        .split(",", ";")
                        .map { it.trim() }
                }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(40)

            val lang = call.getString(
                "language",
                default = "de"
            ).ifBlank {
                "de"
            }

            val job =
                scope.launch(
                    Dispatchers.IO,
                    start = CoroutineStart.LAZY
                ) {
                    try {
                        if (list.isNotEmpty()) {
                            val existing =
                                _state.value.forvoWords
                                    .map {
                                        it.query.lowercase()
                                    }
                                    .toSet()

                            val fresh =
                                list.filter {
                                    it.lowercase() !in existing
                                }

                            if (fresh.isNotEmpty()) {
                                _state.update { s ->
                                    s.copy(
                                        forvoWords =
                                            s.forvoWords +
                                                fresh.map {
                                                    ForvoWord(
                                                        word = it,
                                                        query = it,
                                                        language = lang
                                                    )
                                                }
                                    )
                                }

                                forvoRepo.lookupBatch(
                                    fresh,
                                    lang
                                ) { q, res ->
                                    if (
                                        currentEpoch != client.epoch ||
                                        cancelledToolCallKeys.contains(key) ||
                                        !coroutineContext.isActive
                                    ) {
                                        return@lookupBatch
                                    }

                                    _state.update { s ->
                                        s.copy(
                                            forvoWords =
                                                s.forvoWords.map { w ->
                                                    if (
                                                        !w.query.equals(
                                                            q,
                                                            true
                                                        )
                                                    ) {
                                                        w
                                                    } else {
                                                        when (res) {
                                                            is ForvoResult.Found ->
                                                                w.copy(
                                                                    audioUrl =
                                                                        res.pronunciation.mp3Url,
                                                                    isLoading = false,
                                                                    notFound = false
                                                                )

                                                            else ->
                                                                w.copy(
                                                                    isLoading = false,
                                                                    notFound = true
                                                                )
                                                        }
                                                    }
                                                }
                                        )
                                    }
                                }
                            }
                        }

                        val sent = toolResponseMutex.withLock {
                            if (
                                currentSessionId != client.sessionId ||
                                currentEpoch != client.epoch ||
                                cancelledToolCallKeys.contains(key) ||
                                !isActive
                            ) {
                                return@withLock false
                            }

                            val respPayload =
                                buildJsonObject {
                                    put("status", "ok")
                                    put(
                                        "accepted_words_count",
                                        list.size
                                    )
                                }

                            client.sendToolResponses(
                                listOf(
                                    ToolResponse(
                                        name = call.name,
                                        id = callId,
                                        response = respPayload,
                                        scheduling =
                                            if (
                                                clientCapabilitiesForCurrentSession()
                                                    .supportsFunctionScheduling
                                            ) {
                                                FunctionResponseScheduling.WHEN_IDLE
                                            } else {
                                                null
                                            },
                                        willContinue = false
                                    )
                                )
                            )
                        }

                        if (!sent) {
                            logger.w(
                                "SessionManager: FunctionResponse не отправлен для callId=$callId"
                            )
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        logger.e("SessionManager: tool $callId failed", t)
                        if (currentSessionId == client.sessionId && currentEpoch == client.epoch) {
                            client.sendToolResponses(
                                listOf(
                                    ToolResponse(
                                        name = call.name,
                                        id = callId,
                                        response = buildJsonObject {
                                            put("error", t.message ?: "tool_execution_failed")
                                        },
                                        scheduling = if (clientCapabilitiesForCurrentSession().supportsFunctionScheduling) FunctionResponseScheduling.WHEN_IDLE else null
                                    )
                                )
                            )
                        }
                    } finally {
                        activeToolJobs.remove(key)
                    }
                }

            val existing =
                activeToolJobs.putIfAbsent(
                    key,
                    job
                )

            if (existing == null) {
                if (
                    currentEpoch == client.epoch &&
                    !cancelledToolCallKeys.contains(key)
                ) {
                    job.start()
                } else {
                    job.cancel()
                    activeToolJobs.remove(
                        key,
                        job
                    )
                }
            } else {
                logger.w("SessionManager: duplicate active tool call ignored: $key")
            }
        }
    }

    private fun appendTranscript(
        role: ClientRole,
        text: String,
        interim: Boolean,
        generationId: Long
    ) {
        synchronized(transcriptLock) {
            _state.update { state ->
                val list = state.messages.toMutableList()

                if (interim) {
                    val idx = list.indexOfLast {
                        it.role == role && it.interim
                    }

                    val message = ChatMessage(
                        role = role,
                        text = text,
                        interim = true
                    )

                    if (idx >= 0) {
                        list[idx] = list[idx].copy(text = text)
                    } else {
                        list.add(message)
                    }

                    state.copy(messages = list.takeLast(MAX_MESSAGES))
                } else {
                    list.removeAll {
                        it.role == role && it.interim
                    }

                    val last = list.lastOrNull()
                    if (
                        last != null &&
                        last.role == role &&
                        !last.interim &&
                        transcriptStreamGenerationId == generationId
                    ) {
                        val previous = last.text
                        val incoming = text
                        val previousComparable =
                            normalizeTranscriptForComparison(previous)
                        val incomingComparable =
                            normalizeTranscriptForComparison(incoming)

                        val mergedText = when {
                            incomingComparable.isBlank() -> previous
                            previousComparable.isBlank() -> incoming
                            previousComparable == incomingComparable -> previous
                            incomingComparable.startsWith(previousComparable) -> incoming
                            previousComparable.startsWith(incomingComparable) -> previous
                            else -> joinTranscriptChunks(previous, incoming)
                        }

                        list[list.lastIndex] = last.copy(text = mergedText)
                    } else {
                        list.add(
                            ChatMessage(
                                role = role,
                                text = text
                            )
                        )
                    }

                    state.copy(messages = list.takeLast(MAX_MESSAGES))
                }
            }

            transcriptStreamGenerationId = generationId
        }
    }

    private fun normalizeTranscriptForComparison(text: String): String =
        text
            .trim()
            .replace(Regex("\\s+"), " ")
            .trimEnd('.', ',', '!', '?', ';', ':', '…')
            .lowercase()

    private fun joinTranscriptChunks(
        previous: String,
        incoming: String
    ): String {
        if (previous.isEmpty()) return incoming
        if (incoming.isEmpty()) return previous
        if (previous.last().isWhitespace() || incoming.first().isWhitespace()) {
            return previous + incoming
        }

        val noLeadingSpace = incoming.first() in
            ".,!?;:%)]}\"»”’…-–—"
        val noTrailingSpace = previous.last() in
            "([{\"«“‘"

        return if (noLeadingSpace || noTrailingSpace) {
            previous + incoming
        } else {
            "$previous $incoming"
        }
    }

    private fun resetTranscriptRuntime() {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = -1L
        }
    }

    private fun recordTranscriptGeneration(generationId: Long) {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = generationId
        }
    }

    private fun addMessage(
        msg: ChatMessage
    ) {
        synchronized(transcriptLock) {
            transcriptStreamGenerationId = -1L
            _state.update {
                it.copy(
                    messages =
                        (it.messages + msg).takeLast(MAX_MESSAGES)
                )
            }
        }
    }

    private fun decryptStoredValue(
        encryptedValue: String,
        label: String
    ): String? = when (val result = cryptoManager.decrypt(encryptedValue)) {
        is com.client.app.util.CryptoResult.Success -> result.value
        com.client.app.util.CryptoResult.Missing -> ""
        is com.client.app.util.CryptoResult.Failure -> {
            logger.e(
                "SessionManager: failed to decrypt $label: ${result.reason}",
                result.cause
            )
            null
        }
    }

    private fun observeSettings() = scope.launch {
        dataStore.data.collect { prefs ->
            audioEngine.setVolume(prefs[KEY_VOLUME] ?: 1.0f)
            audioEngine.setMicGain(prefs[KEY_MIC_GAIN] ?: 1.0f)
            if (prefs[KEY_SESSION_RESUMPTION_ENABLED] == false) {
                val hasStoredHandle =
                    !prefs[KEY_SESSION_RESUMPTION_HANDLE]
                        .isNullOrBlank()
                val hasStoredTimestamp =
                    prefs[KEY_SESSION_RESUMPTION_TIMESTAMP] != null

                if (hasStoredHandle || hasStoredTimestamp) {
                    resumptionPersistenceMutex.withLock {
                        dataStore.edit {
                            it.remove(KEY_SESSION_RESUMPTION_HANDLE)
                            it.remove(KEY_SESSION_RESUMPTION_TIMESTAMP)
                        }
                    }
                }
                resumptionHandle = null
            }
        }
    }

    private fun startForegroundService(): String? {
        LiveSessionForegroundService.prepareForStart()
        val intent = Intent(context, LiveSessionForegroundService::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            null
        }.getOrElse { throwable ->
            logger.e("SessionManager: Failed to request foreground service", throwable)
            throwable.localizedMessage ?: throwable.javaClass.simpleName
        }
    }

    private suspend fun ensureForegroundServiceActive(): String? {
        if (LiveSessionForegroundService.isServiceActive.value) {
            return null
        }

        startForegroundService()?.let { return it }

        val activated = withTimeoutOrNull(3000L) {
            while (!LiveSessionForegroundService.isServiceActive.value) {
                LiveSessionForegroundService.serviceError.value?.let {
                    return@withTimeoutOrNull false
                }
                delay(25L)
            }
            true
        } == true

        if (activated) return null

        return LiveSessionForegroundService.serviceError.value
            ?: "Foreground service не перешёл в активное состояние за 3000 мс"
    }

    private fun stopForegroundService() {
        runCatching {
            context.stopService(
                Intent(
                    context,
                    LiveSessionForegroundService::class.java
                )
            )
        }
    }
}
