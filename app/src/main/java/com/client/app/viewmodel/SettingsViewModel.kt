package com.client.app.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.client.app.api.LiveModelCapabilitiesRegistry
import com.client.app.attach.VocabularyExtractor
import com.client.app.forvo.ForvoRepository
import com.client.app.session.SessionManager
import com.client.app.util.AppLogger
import com.client.app.util.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettingsState(
    val apiKey: String = "",
    val liveModel: String = SessionManager.DEFAULT_LIVE_MODEL,
    val thinkingLevel: String = "low",
    val analyzerModel: String = VocabularyExtractor.DEFAULT_MODEL,
    val voice: String = "Charon",
    val speechLanguage: String = "",
    val temperature: Float = 0.5f,
    val mediaResolution: String = "MEDIA_RESOLUTION_HIGH",
    val systemPrompt: String = SessionManager.DEFAULT_SYSTEM_PROMPT,
    val volume: Float = 1.0f,
    val micGain: Float = 1.0f,

    val inputTxEnabled: Boolean = true,
    val inputTxLanguages: String = "",
    val inputTxVocab: String = "",
    val inputTxMode: String = "VERBATIM",

    val outputTxEnabled: Boolean = true,
    val outputTxLanguages: String = "",
    val outputTxVocab: String = "",
    val outputTxMode: String = "VERBATIM",

    val aadEnabled: Boolean = true,
    val aadStartSensitivity: String = "START_SENSITIVITY_HIGH",
    val aadEndSensitivity: String = "END_SENSITIVITY_LOW",
    val prefixPaddingMs: Int = 60,
    val silenceDurationMs: Int = 600,
    val activityHandling: String = "START_OF_ACTIVITY_INTERRUPTS",
    val turnCoverage: String = "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO",

    val compressionEnabled: Boolean = true,
    val compressionTriggerTokens: Int = 0,
    val compressionTargetTokens: Int = 0,

    val sessionResumptionEnabled: Boolean = true,
    val initialHistoryTurns: Int = 20,
    val enableForvo: Boolean = false,
    val forvoApiKey: String = "",
    val enableGoogleSearch: Boolean = false
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cryptoManager: CryptoManager,
    private val logger: AppLogger
) : ViewModel() {

    companion object {
        val LIVE_MODEL_OPTIONS = listOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking",
            "gemini-3.1-flash-live-preview",
            "gemini-2.5-flash-native-audio-preview-12-2025"
        )

        val VALID_SPEECH_LANGUAGES = setOf(
            "ar-SA", "bg-BG", "bn-BD", "cs-CZ", "da-DK", "de-DE", "el-GR", "en-AU",
            "en-GB", "en-IN", "en-US", "es-ES", "es-US", "fi-FI", "fil-PH", "fr-CA",
            "fr-FR", "he-IL", "hi-IN", "hr-HR", "hu-HU", "id-ID", "it-IT", "ja-JP",
            "ko-KR", "lt-LT", "lv-LV", "mr-IN", "ms-MY", "nb-NO", "nl-NL", "pl-PL",
            "pt-BR", "pt-PT", "ro-RO", "ru-RU", "sk-SK", "sl-SI", "sv-SE", "ta-IN",
            "te-IN", "th-TH", "tr-TR", "uk-UA", "vi-VN", "zh-CN", "zh-TW"
        )

        val THINKING_LEVELS = setOf("low", "medium", "high")
    }

    private val _settings = MutableStateFlow(AppSettingsState())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    private val apiKeyDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val promptDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val forvoKeyDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val inputVocabDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val outputVocabDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private fun decryptSetting(
        encrypted: String,
        label: String
    ): String = when (val result = cryptoManager.decrypt(encrypted)) {
        is com.client.app.util.CryptoResult.Success -> result.value
        com.client.app.util.CryptoResult.Missing -> ""
        is com.client.app.util.CryptoResult.Failure -> {
            logger.w(
                "SettingsViewModel: failed to decrypt $label: ${result.reason}"
            )
            ""
        }
    }

    init {
        viewModelScope.launch {
            dataStore.data.collect { p ->
                val modelId = p[SessionManager.KEY_LIVE_MODEL]?.trim()?.takeIf { it in LIVE_MODEL_OPTIONS }
                    ?: SessionManager.DEFAULT_LIVE_MODEL
                val normalizedThinking =
                    p[SessionManager.KEY_THINKING_LEVEL]
                        ?.trim()
                        ?.lowercase()
                        ?.takeIf { it in THINKING_LEVELS }
                        ?: "low"

                val capabilities = LiveModelCapabilitiesRegistry.forModel(modelId)

                _settings.update {
                    it.copy(
                        apiKey = decryptSetting(
                            p[SessionManager.KEY_API].orEmpty(),
                            "Gemini API key"
                        ),
                        liveModel = modelId,
                        thinkingLevel = if (capabilities.supportsThinkingConfig) normalizedThinking else "low",
                        analyzerModel = p[SessionManager.KEY_ANALYZER_MODEL] ?: VocabularyExtractor.DEFAULT_MODEL,
                        voice = p[SessionManager.KEY_VOICE] ?: "Charon",
                        speechLanguage = p[SessionManager.KEY_SPEECH_LANGUAGE].orEmpty(),
                        temperature = p[SessionManager.KEY_TEMPERATURE] ?: 0.5f,
                        mediaResolution = p[SessionManager.KEY_MEDIA_RESOLUTION] ?: "MEDIA_RESOLUTION_HIGH",
                        systemPrompt = p[SessionManager.KEY_SYSTEM_PROMPT] ?: SessionManager.DEFAULT_SYSTEM_PROMPT,
                        volume = p[SessionManager.KEY_VOLUME] ?: 1.0f,
                        micGain = p[SessionManager.KEY_MIC_GAIN] ?: 1.0f,

                        inputTxEnabled = p[SessionManager.KEY_INPUT_TRANSCRIPTION_ENABLED] ?: true,
                        inputTxLanguages = p[SessionManager.KEY_INPUT_TRANSCRIPTION_LANGUAGES].orEmpty(),
                        inputTxVocab = p[SessionManager.KEY_INPUT_TRANSCRIPTION_VOCAB].orEmpty(),
                        inputTxMode = p[SessionManager.KEY_INPUT_TRANSCRIPTION_MODE] ?: "VERBATIM",

                        outputTxEnabled = p[SessionManager.KEY_OUTPUT_TRANSCRIPTION_ENABLED] ?: true,
                        outputTxLanguages = p[SessionManager.KEY_OUTPUT_TRANSCRIPTION_LANGUAGES].orEmpty(),
                        outputTxVocab = p[SessionManager.KEY_OUTPUT_TRANSCRIPTION_VOCAB].orEmpty(),
                        outputTxMode = p[SessionManager.KEY_OUTPUT_TRANSCRIPTION_MODE] ?: "VERBATIM",

                        aadEnabled = p[SessionManager.KEY_AAD_ENABLED] ?: true,
                        aadStartSensitivity = p[SessionManager.KEY_AAD_START_SENSITIVITY] ?: "START_SENSITIVITY_HIGH",
                        aadEndSensitivity = p[SessionManager.KEY_AAD_END_SENSITIVITY] ?: "END_SENSITIVITY_LOW",
                        prefixPaddingMs = p[SessionManager.KEY_PREFIX_PADDING_MS] ?: 60,
                        silenceDurationMs = p[SessionManager.KEY_SILENCE_DURATION_MS] ?: 600,
                        activityHandling = p[SessionManager.KEY_ACTIVITY_HANDLING] ?: "START_OF_ACTIVITY_INTERRUPTS",
                        turnCoverage = p[SessionManager.KEY_TURN_COVERAGE] ?: "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO",

                        compressionEnabled = (p[SessionManager.KEY_COMPRESSION_ENABLED] ?: true) && true,
                        compressionTriggerTokens = p[SessionManager.KEY_COMPRESSION_TRIGGER_TOKENS] ?: 0,
                        compressionTargetTokens = p[SessionManager.KEY_COMPRESSION_TARGET_TOKENS] ?: 0,

                        sessionResumptionEnabled = (p[SessionManager.KEY_SESSION_RESUMPTION_ENABLED] ?: true) && true,
                        initialHistoryTurns = p[SessionManager.KEY_INITIAL_HISTORY_TURNS] ?: 20,

                        enableForvo = p[SessionManager.KEY_ENABLE_FORVO] ?: false,
                        forvoApiKey = decryptSetting(
                            p[ForvoRepository.KEY_FORVO_API].orEmpty(),
                            "Forvo API key"
                        ),
                        enableGoogleSearch = (p[SessionManager.KEY_ENABLE_SEARCH] ?: false) && true
                    )
                }
            }
        }

        viewModelScope.launch {
            apiKeyDebounce.debounce(350).collect { k ->
                when (val encrypted = cryptoManager.encrypt(k.trim())) {
                    is com.client.app.util.CryptoResult.Success -> {
                        dataStore.edit { it[SessionManager.KEY_API] = encrypted.value }
                    }
                    com.client.app.util.CryptoResult.Missing -> {
                        dataStore.edit { it.remove(SessionManager.KEY_API) }
                    }
                    is com.client.app.util.CryptoResult.Failure -> {
                        logger.e(
                            "SettingsViewModel: could not save Gemini API key: ${encrypted.reason}",
                            encrypted.cause
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            promptDebounce.debounce(350).collect { sp ->
                dataStore.edit { it[SessionManager.KEY_SYSTEM_PROMPT] = sp }
            }
        }
        viewModelScope.launch {
            forvoKeyDebounce.debounce(350).collect { k ->
                when (val encrypted = cryptoManager.encrypt(k.trim())) {
                    is com.client.app.util.CryptoResult.Success -> {
                        dataStore.edit { it[ForvoRepository.KEY_FORVO_API] = encrypted.value }
                    }
                    com.client.app.util.CryptoResult.Missing -> {
                        dataStore.edit { it.remove(ForvoRepository.KEY_FORVO_API) }
                    }
                    is com.client.app.util.CryptoResult.Failure -> {
                        logger.e(
                            "SettingsViewModel: could not save Forvo API key: ${encrypted.reason}",
                            encrypted.cause
                        )
                    }
                }
            }
        }
        viewModelScope.launch {
            inputVocabDebounce.debounce(400).collect { v ->
                dataStore.edit { it[SessionManager.KEY_INPUT_TRANSCRIPTION_VOCAB] = v }
            }
        }
        viewModelScope.launch {
            outputVocabDebounce.debounce(400).collect { v ->
                dataStore.edit { it[SessionManager.KEY_OUTPUT_TRANSCRIPTION_VOCAB] = v }
            }
        }
    }

    fun setApiKey(k: String) {
        _settings.update { it.copy(apiKey = k) }
        apiKeyDebounce.tryEmit(k)
    }

    fun setThinkingLevel(level: String) {
        val normalized = level.trim().lowercase()
        if (normalized !in setOf("low", "medium", "high")) return

        _settings.update { it.copy(thinkingLevel = normalized) }
        viewModelScope.launch {
            dataStore.edit {
                it[SessionManager.KEY_THINKING_LEVEL] = normalized
            }
        }
    }

    fun setLiveModel(model: String) {
        val normalized = model.trim()
        if (normalized !in LIVE_MODEL_OPTIONS) return

        _settings.update { it.copy(liveModel = normalized) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_LIVE_MODEL] = normalized }
        }
    }

    fun setVoice(v: String) {
        val trimmed = v.trim()
        _settings.update { it.copy(voice = trimmed) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_VOICE] = trimmed } }
    }

    fun setSpeechLanguage(lang: String) {
        val trimmed = lang.trim()
        val validated = if (trimmed.isBlank() || VALID_SPEECH_LANGUAGES.contains(trimmed)) trimmed else ""
        _settings.update { it.copy(speechLanguage = validated) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_SPEECH_LANGUAGE] = validated } }
    }

    fun setTemperature(t: Float) {
        val clamped = t.coerceIn(0.0f, 2.0f)
        _settings.update { it.copy(temperature = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_TEMPERATURE] = clamped } }
    }

    fun setMediaResolution(res: String) {
        _settings.update { it.copy(mediaResolution = res) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_MEDIA_RESOLUTION] = res } }
    }

    fun setSystemPrompt(sp: String) {
        _settings.update { it.copy(systemPrompt = sp) }
        promptDebounce.tryEmit(sp)
    }

    fun setVolume(v: Float) {
        val clamped = v.coerceIn(0.0f, 1.0f)
        _settings.update { it.copy(volume = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_VOLUME] = clamped } }
    }

    fun setMicGain(g: Float) {
        val clamped = g.coerceIn(0.5f, 2.0f)
        _settings.update { it.copy(micGain = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_MIC_GAIN] = clamped } }
    }

    fun setInputTxEnabled(enabled: Boolean) {
        _settings.update { it.copy(inputTxEnabled = enabled) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_INPUT_TRANSCRIPTION_ENABLED] = enabled } }
    }

    fun setInputTxLanguages(langs: String) {
        _settings.update { it.copy(inputTxLanguages = langs) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_INPUT_TRANSCRIPTION_LANGUAGES] = langs.trim() } }
    }

    fun setInputTxVocab(vocab: String) {
        _settings.update { it.copy(inputTxVocab = vocab) }
        inputVocabDebounce.tryEmit(vocab)
    }

    fun setInputTxMode(mode: String) {
        _settings.update { it.copy(inputTxMode = mode) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_INPUT_TRANSCRIPTION_MODE] = mode } }
    }

    fun setOutputTxEnabled(enabled: Boolean) {
        _settings.update { it.copy(outputTxEnabled = enabled) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_OUTPUT_TRANSCRIPTION_ENABLED] = enabled } }
    }

    fun setOutputTxLanguages(langs: String) {
        _settings.update { it.copy(outputTxLanguages = langs) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_OUTPUT_TRANSCRIPTION_LANGUAGES] = langs.trim() } }
    }

    fun setOutputTxVocab(vocab: String) {
        _settings.update { it.copy(outputTxVocab = vocab) }
        outputVocabDebounce.tryEmit(vocab)
    }

    fun setOutputTxMode(mode: String) {
        _settings.update { it.copy(outputTxMode = mode) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_OUTPUT_TRANSCRIPTION_MODE] = mode } }
    }

    fun setAadEnabled(enabled: Boolean) {
        _settings.update { it.copy(aadEnabled = enabled) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_AAD_ENABLED] = enabled } }
    }

    fun setAadStartSensitivity(s: String) {
        _settings.update { it.copy(aadStartSensitivity = s) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_AAD_START_SENSITIVITY] = s } }
    }

    fun setAadEndSensitivity(s: String) {
        _settings.update { it.copy(aadEndSensitivity = s) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_AAD_END_SENSITIVITY] = s } }
    }

    fun setPrefixPaddingMs(padding: Int) {
        val clamped = padding.coerceIn(0, 500)
        _settings.update { it.copy(prefixPaddingMs = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_PREFIX_PADDING_MS] = clamped } }
    }

    fun setSilenceDurationMs(duration: Int) {
        val clamped = duration.coerceIn(100, 3000)
        _settings.update { it.copy(silenceDurationMs = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_SILENCE_DURATION_MS] = clamped } }
    }

    fun setActivityHandling(h: String) {
        _settings.update { it.copy(activityHandling = h) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_ACTIVITY_HANDLING] = h } }
    }

    fun setTurnCoverage(tc: String) {
        _settings.update { it.copy(turnCoverage = tc) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_TURN_COVERAGE] = tc } }
    }

    fun setCompressionEnabled(enabled: Boolean) {
        _settings.update { it.copy(compressionEnabled = enabled) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_COMPRESSION_ENABLED] = enabled } }
    }

    fun setCompressionTokens(trigger: Int, target: Int) {
        val safeTrigger = trigger.coerceAtLeast(0)
        val safeTarget = target.coerceAtLeast(0)
        val validPair = when {
            safeTarget > 0 -> 0 to safeTarget
            safeTrigger > 0 -> safeTrigger to 0
            else -> 0 to 0
        }
        _settings.update {
            it.copy(
                compressionTriggerTokens = validPair.first,
                compressionTargetTokens = validPair.second
            )
        }
        viewModelScope.launch {
            dataStore.edit {
                it[SessionManager.KEY_COMPRESSION_TRIGGER_TOKENS] = validPair.first
                it[SessionManager.KEY_COMPRESSION_TARGET_TOKENS] = validPair.second
            }
        }
    }

    fun setSessionResumptionEnabled(enabled: Boolean) {
        _settings.update {
            it.copy(sessionResumptionEnabled = enabled)
        }
        viewModelScope.launch {
            dataStore.edit { prefs ->
                prefs[SessionManager.KEY_SESSION_RESUMPTION_ENABLED] =
                    enabled
                if (!enabled) {
                    prefs.remove(
                        SessionManager.KEY_SESSION_RESUMPTION_HANDLE
                    )
                    prefs.remove(
                        SessionManager.KEY_SESSION_RESUMPTION_TIMESTAMP
                    )
                }
            }
        }
    }

    fun setInitialHistoryTurns(turns: Int) {
        val clamped = turns.coerceIn(1, 100)
        _settings.update { it.copy(initialHistoryTurns = clamped) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_INITIAL_HISTORY_TURNS] = clamped } }
    }

    fun setEnableForvo(e: Boolean) {
        _settings.update { it.copy(enableForvo = e) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_ENABLE_FORVO] = e } }
    }

    fun setForvoApiKey(k: String) {
        _settings.update { it.copy(forvoApiKey = k) }
        forvoKeyDebounce.tryEmit(k)
    }

    fun setEnableGoogleSearch(e: Boolean) {
        _settings.update { it.copy(enableGoogleSearch = e) }
        viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_ENABLE_SEARCH] = e } }
    }
}