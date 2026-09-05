// >>> FILE: app/src/main/java/com/client/app/viewmodel/SettingsViewModel.kt
package com.client.app.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.client.app.attach.VocabularyExtractor
import com.client.app.forvo.ForvoRepository
import com.client.app.session.SessionManager
import com.client.app.util.CryptoManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettingsState(
    val apiKey: String = "",
    val model: String = "gemini-3.1-flash-live-preview",
    val analyzerModel: String = VocabularyExtractor.DEFAULT_MODEL,
    val voice: String = "Charon",
    val systemPrompt: String = SessionManager.DEFAULT_SYSTEM_PROMPT,
    val volume: Float = 1.0f,
    val micGain: Float = 1.0f,
    val enableForvo: Boolean = false,
    val forvoApiKey: String = ""
)

@OptIn(FlowPreview::class)
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cryptoManager: CryptoManager
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettingsState())
    val settings: StateFlow<AppSettingsState> = _settings.asStateFlow()

    private val apiKeyDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val promptDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private val forvoKeyDebounce = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        viewModelScope.launch {
            dataStore.data.first().let { p ->
                _settings.value = AppSettingsState(
                    apiKey = cryptoManager.decrypt(p[SessionManager.KEY_API].orEmpty()),
                    model = p[SessionManager.KEY_MODEL] ?: "gemini-3.1-flash-live-preview",
                    analyzerModel = p[SessionManager.KEY_ANALYZER_MODEL] ?: VocabularyExtractor.DEFAULT_MODEL,
                    voice = p[SessionManager.KEY_VOICE] ?: "Charon",
                    systemPrompt = p[SessionManager.KEY_SYSTEM_PROMPT] ?: SessionManager.DEFAULT_SYSTEM_PROMPT,
                    volume = p[SessionManager.KEY_VOLUME] ?: 1.0f,
                    micGain = p[SessionManager.KEY_MIC_GAIN] ?: 1.0f,
                    enableForvo = p[SessionManager.KEY_ENABLE_FORVO] ?: false,
                    forvoApiKey = cryptoManager.decrypt(p[ForvoRepository.KEY_FORVO_API].orEmpty())
                )
            }
        }

        viewModelScope.launch {
            apiKeyDebounce.debounce(350).collect { k ->
                val encrypted = cryptoManager.encrypt(k.trim())
                dataStore.edit { it[SessionManager.KEY_API] = encrypted }
            }
        }
        viewModelScope.launch {
            promptDebounce.debounce(350).collect { sp ->
                dataStore.edit { it[SessionManager.KEY_SYSTEM_PROMPT] = sp }
            }
        }
        viewModelScope.launch {
            forvoKeyDebounce.debounce(350).collect { k ->
                val encrypted = cryptoManager.encrypt(k.trim())
                dataStore.edit { it[ForvoRepository.KEY_FORVO_API] = encrypted }
            }
        }
    }

    fun setApiKey(k: String) {
        _settings.update { it.copy(apiKey = k) }
        apiKeyDebounce.tryEmit(k)
    }

    fun setModel(m: String) {
        val trimmed = m.trim()
        _settings.update { it.copy(model = trimmed) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_MODEL] = trimmed }
        }
    }

    fun setAnalyzerModel(m: String) {
        val trimmed = m.trim()
        _settings.update { it.copy(analyzerModel = trimmed) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_ANALYZER_MODEL] = trimmed }
        }
    }

    fun setVoice(v: String) {
        val trimmed = v.trim()
        _settings.update { it.copy(voice = trimmed) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_VOICE] = trimmed }
        }
    }

    fun setSystemPrompt(sp: String) {
        _settings.update { it.copy(systemPrompt = sp) }
        promptDebounce.tryEmit(sp)
    }

    /** Вызывается из SettingsScreen строго при отпускании пальца (onValueChangeFinished) */
    fun setVolume(v: Float) {
        _settings.update { it.copy(volume = v) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_VOLUME] = v }
        }
    }

    /** Вызывается из SettingsScreen строго при отпускании пальца (onValueChangeFinished) */
    fun setMicGain(g: Float) {
        _settings.update { it.copy(micGain = g) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_MIC_GAIN] = g }
        }
    }

    fun setEnableForvo(e: Boolean) {
        _settings.update { it.copy(enableForvo = e) }
        viewModelScope.launch {
            dataStore.edit { it[SessionManager.KEY_ENABLE_FORVO] = e }
        }
    }

    fun setForvoApiKey(k: String) {
        _settings.update { it.copy(forvoApiKey = k) }
        forvoKeyDebounce.tryEmit(k)
    }
}