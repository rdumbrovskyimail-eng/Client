package com.client.app.session

import android.content.Context import android.content.Intent import
android.net.Uri import android.os.Build import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences import
androidx.datastore.preferences.core.booleanPreferencesKey import
androidx.datastore.preferences.core.floatPreferencesKey import
androidx.datastore.preferences.core.stringPreferencesKey import
com.client.app.api.* import com.client.app.attach.AnalysisResult import
com.client.app.attach.VocabItem import com.client.app.attach.VocabularyExtractor
import com.client.app.audio.AndroidAudioEngine import
com.client.app.audio.PronunciationPlayer import
com.client.app.forvo.ForvoRepository import com.client.app.forvo.ForvoResult
import com.client.app.service.LiveSessionForegroundService import
com.client.app.util.AppLogger import com.client.app.util.AttachmentProcessor
import com.client.app.viewmodel.SettingsViewModel import
dagger.hilt.android.qualifiers.ApplicationContext import kotlinx.coroutines.*
import kotlinx.coroutines.flow.* import kotlinx.coroutines.sync.Mutex import
kotlinx.coroutines.sync.withLock import kotlinx.serialization.json.* import
java.util.concurrent.atomic.AtomicLong import javax.inject.Inject import
javax.inject.Singleton

/* ═══════════════════════════════════════════════════════════════════════════

  - МОДЕЛИ СОСТОЯНИЯ СЕССИИ
  - ═══════════════════════════════════════════════════════════════════════════
    */

private val idGen = AtomicLong(0)

data class ChatMessage( val id: Long = idGen.incrementAndGet(), val role:
String, val text: String, val attachmentNames: List = emptyList(), val interim:
Boolean = false, val timestamp: Long = System.currentTimeMillis() )

data class ForvoWord( val word: String, val query: String = word, val language:
String = "de", val translation: String? = null, val audioUrl: String? = null,
val isLoading: Boolean = true, val notFound: Boolean = false )

enum class LinkState { IDLE, CONNECTING, LIVE, RECONNECTING }

data class SessionState( val link: LinkState = LinkState.IDLE, val isMicActive:
Boolean = false, val isAiSpeaking: Boolean = false, val isAnalyzing: Boolean =
false, val activePrompt: String = "", val error: String? = null, val messages:
List = emptyList(), val forvoWords: List = emptyList(), val forvoUsed: Int = 0,
val forvoLimit: Int = 500, val tokensUsed: Int = 0 ) { val isConnected: Boolean
get() = link == LinkState.LIVE val isConnecting: Boolean get() = link ==
LinkState.CONNECTING || link == LinkState.RECONNECTING }

@Singleton class SessionManager @Inject constructor( @ApplicationContext private
val context: Context, private val client: GeminiLiveClient, private val
audioEngine: AndroidAudioEngine, private val forvoRepo: ForvoRepository, private
val forvoPlayer: PronunciationPlayer, private val attachmentProcessor:
AttachmentProcessor, private val extractor: VocabularyExtractor, private val
dataStore: DataStore, private val logger: AppLogger ) { companion object { val
KEY_API = stringPreferencesKey("gemini_api_key") val KEY_MODEL =
stringPreferencesKey("gemini_model") val KEY_ANALYZER_MODEL =
stringPreferencesKey("analyzer_model") val KEY_SYSTEM_PROMPT =
stringPreferencesKey("gemini_system_prompt") val KEY_ENABLE_FORVO =
booleanPreferencesKey("enable_forvo") val KEY_VOICE =
stringPreferencesKey("gemini_voice") val KEY_VOLUME =
floatPreferencesKey("audio_volume") val KEY_MIC_GAIN =
floatPreferencesKey("audio_mic_gain")

    const val DEFAULT_SYSTEM_PROMPT =
        "Ты — интеллектуальный персональный ассистент с академической культурой речи. " +
        "Отвечай лаконично, точно и структурированно, без шаблонных вводных слов. " +
        "Помогай изучать любые дисциплины, научные концепции, языки и решать прикладные задачи. " +
        "Говори естественным, уверенным тоном."

    private const val MAX_MESSAGES = 200
    private const val MAX_RECONNECT_ATTEMPTS = 5
}

private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
private val mutex = Mutex()
private val micMutex = Mutex()

private val _state = MutableStateFlow(SessionState(activePrompt = DEFAULT_SYSTEM_PROMPT))
val state: StateFlow<SessionState> = _state.asStateFlow()

/** Единый уровень звука для анимации визуализатора */
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

/** Запоминает намерение пользователя: включён ли микрофон вручную */
@Volatile private var userMicDesired = true

@Volatile private var activeVocabulary: List<VocabItem> = emptyList()
@Volatile private var materialLanguage: String? = null

init {
    observeSettings()
    observeEvents()
    observeAudio()
    observeBargeIn()
    observeFocus()
}

/* ═════════════════════════ ПУБЛИЧНОЕ УПРАВЛЕНИЕ ═════════════════════════ */

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

fun updateDefaultPromptSilently(prompt: String) {
    if (_state.value.link == LinkState.IDLE) {
        _state.update { it.copy(activePrompt = prompt) }
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

    // suspend-вызов: приостанавливает выполнение строго на время звучания
    forvoPlayer.play(url)
    syncQuota()

    if (wasMic && userMicDesired && !userStopped) {
        delay(200)
        startMic()
    }
}

fun refetchAllForvo() {
    val words = _state.value.forvoWords
    if (words.isEmpty()) return
    scope.launch {
        resolveForvo(
            words.map { VocabItem(it.word, it.query, it.translation) },
            words.first().language
        )
    }
}

fun clearForvo() = _state.update { it.copy(forvoWords = emptyList()) }
fun clearError() = _state.update { it.copy(error = null) }
fun clearChat() = _state.update { it.copy(messages = emptyList()) }

/* ═════════════════════════ ВЛОЖЕНИЯ ═════════════════════════ */

private suspend fun handleAttachments(text: String, uris: List<Uri>) {
    _state.update { it.copy(isAnalyzing = true, error = null) }
    try {
        val processed = attachmentProcessor.process(uris)
        val prefs = dataStore.data.first()
        val apiKey = prefs[KEY_API]?.trim().orEmpty()
        val forvoOn = prefs[KEY_ENABLE_FORVO] ?: false
        val analyzerModel = prefs[KEY_ANALYZER_MODEL]?.ifBlank { null }
            ?: VocabularyExtractor.DEFAULT_MODEL

        addMessage(
            ChatMessage(
                role = "user",
                text = text.ifEmpty { "Изучи приложенные материалы." },
                attachmentNames = processed.accepted
            )
        )

        val result = extractor.analyze(
            apiKey = apiKey,
            images = processed.images,
            plainText = processed.extractedText,
            forLanguageLearning = forvoOn,
            model = analyzerModel
        )

        when (result) {
            is AnalysisResult.Failure -> {
                _state.update { it.copy(error = "Разбор материала: ${result.reason}") }
                return
            }
            is AnalysisResult.Success -> {
                val a = result.analysis
                activeVocabulary = a.vocabulary
                materialLanguage = a.language

                if (forvoOn && a.vocabulary.isNotEmpty()) {
                    scope.launch { resolveForvo(a.vocabulary, a.language) }
                }

                if (!ensureLive()) {
                    _state.update { it.copy(error = "Материал обработан, но нет соединения с Gemini") }
                    return
                }

                val briefing = buildString {
                    append("[Материал: ")
                    append(a.title ?: processed.accepted.joinToString())
                    append(", язык: ").append(a.language).append("]\n\n")
                    append(a.fullText.take(30_000))
                    if (a.vocabulary.isNotEmpty()) {
                        append("\n\nКлючевая лексика (")
                        append(a.vocabulary.size).append("): ")
                        append(a.vocabulary.joinToString(", ") { it.lemma })
                    }
                    if (text.isNotBlank()) append("\n\nЗадача пользователя: ").append(text)
                }
                client.sendRealtimeText(briefing)
            }
        }
    } catch (e: Exception) {
        logger.e("Attachment pipeline failed", e)
        _state.update { it.copy(error = e.localizedMessage ?: "Сбой разбора вложения") }
    } finally {
        _state.update { it.copy(isAnalyzing = false) }
    }
}

/* ═════════════════════════ FORVO ═════════════════════════ */

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
    syncQuota()

    forvoRepo.lookupBatch(items.map { it.forvoQuery }, lang) { query, res ->
        _state.update { s ->
            s.copy(forvoWords = s.forvoWords.map { w ->
                if (!w.query.equals(query, ignoreCase = true)) w
                else when (res) {
                    is ForvoResult.Found -> w.copy(
                        audioUrl = res.pronunciation.mp3Url,
                        isLoading = false, notFound = false
                    )
                    is ForvoResult.NotFound -> w.copy(isLoading = false, notFound = true)
                    else -> w.copy(isLoading = false, notFound = true)
                }
            })
        }
        if (res is ForvoResult.QuotaExceeded) {
            _state.update { it.copy(error = "Дневной лимит Forvo исчерпан (сброс в 22:00 UTC)") }
        }
        if (res is ForvoResult.NoApiKey) {
            _state.update { it.copy(error = "Укажите Forvo API Key в настройках") }
        }
    }
    syncQuota()
}

private fun syncQuota() {
    val q = forvoRepo.quota.value
    _state.update { it.copy(forvoUsed = q.used, forvoLimit = q.limit) }
}

/* ═════════════════════════ ЖИЗНЕННЫЙ ЦИКЛ ═════════════════════════ */

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
    val prefs = dataStore.data.first()
    val apiKey = prefs[KEY_API]?.trim().orEmpty()
    if (apiKey.isEmpty()) {
        _state.update { it.copy(error = "Укажите Gemini API Key в Настройках", link = LinkState.IDLE) }
        return
    }

    val model = prefs[KEY_MODEL]?.ifBlank { null } ?: "gemini-3.1-flash-live-preview"
    val voice = prefs[KEY_VOICE]?.ifBlank { null } ?: "Charon"
    val enableForvo = prefs[KEY_ENABLE_FORVO] ?: false

    audioEngine.playbackVolume = prefs[KEY_VOLUME] ?: 1.0f
    audioEngine.micGain = prefs[KEY_MIC_GAIN] ?: 1.0f

    _state.update {
        it.copy(
            link = if (resume) LinkState.RECONNECTING else LinkState.CONNECTING,
            error = null
        )
    }

    audioEngine.initPlayback()
    startForegroundService()

    client.connect(
        LiveConfig(
            apiKey = apiKey,
            model = model,
            systemInstruction = buildEffectivePrompt(_state.value.activePrompt, enableForvo),
            voiceName = voice,
            toolsJson = if (enableForvo) buildForvoToolsSchema() else null,
            resumptionHandle = if (resume) resumptionHandle else null,
            transcriptionLanguages = buildLanguageHints(),
            customVocabulary = activeVocabulary.map { it.forvoQuery },
            initialHistory = if (resume) emptyList() else recentHistory(),
            conservative = conservativeSetup
        )
    )
}

private fun recentHistory(): List<Pair<String, String>> =
    _state.value.messages
        .filter { !it.interim && it.text.isNotBlank() }
        .takeLast(20)
        .map { it.role to it.text }

private fun buildLanguageHints(): List<String> =
    listOfNotNull("ru", materialLanguage?.takeIf { it != "ru" }).distinct()

private suspend fun stopInternal(full: Boolean) {
    reconnectJob?.cancel()
    stopMic(userInitiated = false)
    client.disconnect()
    if (full) {
        audioEngine.releaseAll()
        stopForegroundService()
    } else {
        audioEngine.flushPlayback()
    }
    _state.update {
        it.copy(
            link = LinkState.IDLE,
            isAiSpeaking = false,
            isMicActive = false,
            forvoWords = if (full) emptyList() else it.forvoWords
        )
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

/* ═════════════════════════ СЕРИАЛИЗОВАННЫЙ МИКРОФОН ═════════════════════════ */

private suspend fun startMic() = micMutex.withLock {
    if (_state.value.isMicActive) return@withLock
    userMicDesired = true

    if (!audioEngine.startCapture()) {
        _state.update { it.copy(error = "Микрофон недоступен") }
        return@withLock
    }
    _state.update { it.copy(isMicActive = true) }

    micJob = scope.launch {
        for (chunk in audioEngine.micOutput) {
            if (!isActive) break
            // Во время речи Forvo блокируем микрофон во избежание эха
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
    audioEngine.stopCapture()
    client.sendAudioStreamEnd()
    _state.update { it.copy(isMicActive = false) }
}

/* ═════════════════════════ ПОДПИСКИ ═════════════════════════ */

private fun observeAudio() = scope.launch {
    for (frame in client.audio) {
        if (frame.epoch != client.epoch) continue
        if (!_state.value.isAiSpeaking) {
            _state.update { it.copy(isAiSpeaking = true) }
        }
        audioEngine.enqueuePlayback(frame.pcm)
    }
}

private fun observeBargeIn() = scope.launch {
    audioEngine.bargeIn.collect {
        _state.update { it.copy(isAiSpeaking = false) }
        streamingRole = null
    }
}

private fun observeFocus() = scope.launch {
    audioEngine.focusLost.collect { lost ->
        if (lost && _state.value.isMicActive) {
            stopMic(userInitiated = false)
            _state.update { it.copy(error = "Аудио прервано другим приложением") }
        }
    }
}

private fun observeEvents() = scope.launch {
    client.events.collect { event ->
        when (event) {
            is GeminiEvent.SetupComplete -> {
                reconnectAttempts = 0
                _state.update { it.copy(link = LinkState.LIVE, error = null) }
                // Включаем микрофон только если пользователь явно не заглушил его
                if (userMicDesired) {
                    scope.launch { startMic() }
                }
            }

            is GeminiEvent.ResumptionHandle -> resumptionHandle = event.handle

            is GeminiEvent.GoAway -> {
                logger.w("goAway через ${event.millisLeft} мс — упреждающий реконнект")
                scheduleReconnect("плановый разрыв")
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
                    delay(audioEngine.pendingPlaybackMs() + 60)
                    if (!audioEngine.isRenderingAudio()) {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }
                }
            }

            is GeminiEvent.InputTranscript ->
                appendTranscript("user", event.text, event.interim)

            is GeminiEvent.OutputTranscript ->
                appendTranscript("model", event.text, false)

            is GeminiEvent.ModelText ->
                appendTranscript("model", event.text, false)

            is GeminiEvent.Usage ->
                _state.update { it.copy(tokensUsed = event.totalTokens) }

            is GeminiEvent.ToolCall -> handleToolCall(event.calls)

            is GeminiEvent.Error -> {
                _state.update { it.copy(error = event.message) }
                if (event.fatal) {
                    userStopped = true
                    scope.launch { mutex.withLock { stopInternal(full = true) } }
                }
            }

            is GeminiEvent.Disconnected -> {
                if (event.epoch != client.epoch) return@collect

                if (event.code == 1007 && !conservativeSetup) {
                    conservativeSetup = true
                    logger.w("Setup отклонен (1007) — перезапуск в консервативном режиме")
                    _state.update {
                        it.copy(error = "Упрощённый режим совместимости активен")
                    }
                    resumptionHandle = null
                    if (!userStopped) scheduleReconnect("1007")
                    return@collect
                }

                client.explainCloseCode(event.code)?.let { msg ->
                    _state.update { it.copy(error = msg) }
                }
                if (!userStopped) scheduleReconnect("код ${event.code}")
            }

            else -> Unit
        }
    }
}

private fun handleToolCall(calls: List<FunctionCall>) = scope.launch {
    val responses = calls.map { call ->
        if (call.name == "lookup_pronunciation") {
            val raw = call.args["words"].orEmpty()
            val lang = call.args["language"]?.ifBlank { null }
                ?: materialLanguage ?: "de"

            val list = runCatching {
                Json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
            }.getOrElse { raw.split(",").map { it.trim() } }
                .filter { it.isNotBlank() }
                .distinctBy { it.lowercase() }
                .take(40)

            if (list.isNotEmpty()) {
                val existing = _state.value.forvoWords.map { it.query.lowercase() }.toSet()
                val fresh = list.filter { it.lowercase() !in existing }
                if (fresh.isNotEmpty()) {
                    _state.update { s ->
                        s.copy(forvoWords = s.forvoWords + fresh.map {
                            ForvoWord(word = it, query = it, language = lang)
                        })
                    }
                    scope.launch {
                        forvoRepo.lookupBatch(fresh, lang) { q, res ->
                            _state.update { s ->
                                s.copy(forvoWords = s.forvoWords.map { w ->
                                    if (!w.query.equals(q, true)) w
                                    else when (res) {
                                        is ForvoResult.Found -> w.copy(
                                            audioUrl = res.pronunciation.mp3Url,
                                            isLoading = false
                                        )
                                        else -> w.copy(isLoading = false, notFound = true)
                                    }
                                })
                            }
                        }
                        syncQuota()
                    }
                }
            }
            ToolResponse(call.name, call.id, """{"status":"ok","accepted":${list.size}}""")
        } else {
            ToolResponse(call.name, call.id, """{"error":"unknown_tool"}""")
        }
    }
    client.sendToolResponses(responses)
}

/* ═════════════════════════ ТРАНСКРИПЦИИ ═════════════════════════ */

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

/* ═════════════════════════ СИСТЕМНЫЙ ПРОМПТ ═════════════════════════ */

private fun buildEffectivePrompt(base: String, enableForvo: Boolean): String {
    if (!enableForvo) return base
    return base + "\n\n" + """
        ИНСТРУМЕНТ ПРОИЗНОШЕНИЯ.
        Тебе доступен `lookup_pronunciation`. Приложение уже самостоятельно
        озвучивает всю лексику из загруженных материалов через Forvo.
        Вызывай инструмент только для слов, которые возникают в живом разговоре
        и которых не было в материале: когда пользователь спрашивает, как
        что-то произносится, или ты вводишь новое редкое слово.
        Передавай слова в базовой словарной форме БЕЗ артикля и указывай двухбуквенный код языка.
    """.trimIndent()
}

private fun buildForvoToolsSchema(): JsonArray = buildJsonArray {
    add(buildJsonObject {
        put("functionDeclarations", buildJsonArray {
            add(buildJsonObject {
                put("name", "lookup_pronunciation")
                put("description",
                    "Показывает пользователю аудиокарточки с эталонным произношением слов от носителей языка.")
                put("parameters", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("words", buildJsonObject {
                            put("type", "ARRAY")
                            put("description", "Слова в начальной форме, без артиклей")
                            put("items", buildJsonObject { put("type", "STRING") })
                        })
                        put("language", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Код языка ISO 639-1: de, en, fr, es, it")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("words")); add(JsonPrimitive("language"))
                    })
                })
            })
        })
    })
}

/* ═════════════════════════ НАСТРОЙКИ ═════════════════════════ */

private fun observeSettings() = scope.launch {
    dataStore.data
        .map { prefs ->
            Triple(
                prefs[KEY_SYSTEM_PROMPT],
                prefs[KEY_VOLUME] ?: 1.0f,
                prefs[KEY_MIC_GAIN] ?: 1.0f
            )
        }
        .distinctUntilChanged()
        .collect { (prompt, vol, gain) ->
            audioEngine.playbackVolume = vol
            audioEngine.micGain = gain
            if (!prompt.isNullOrBlank() && _state.value.link == LinkState.IDLE) {
                _state.update { it.copy(activePrompt = prompt) }
            }
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
    }.onFailure { logger.e("FGS start failed", it) }
}

private fun stopForegroundService() {
    runCatching {
        context.stopService(Intent(context, LiveSessionForegroundService::class.java))
    }
}

}
