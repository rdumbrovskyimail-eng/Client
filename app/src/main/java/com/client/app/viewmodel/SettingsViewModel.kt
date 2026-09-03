package com.client.app.viewmodel

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.client.app.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppSettingsState(
    val apiKey: String = "",
    val model: String = "gemini-3.1-flash-live-preview",
    val systemPrompt: String = SessionManager.DEFAULT_SYSTEM_PROMPT,
    val volume: Float = 1.0f,
    val micGain: Float = 1.25f,
    val enableForvo: Boolean = false,
    val forvoApiKey: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val sessionManager: SessionManager
) : ViewModel() {
    companion object {
        val KEY_VOLUME = floatPreferencesKey("audio_volume")
        val KEY_MIC_GAIN = floatPreferencesKey("audio_mic_gain")
        val KEY_FORVO_API = stringPreferencesKey("forvo_api_key")
    }

    val settings = dataStore.data.map { p ->
        AppSettingsState(
            apiKey = p[SessionManager.KEY_API].orEmpty(),
            model = p[SessionManager.KEY_MODEL] ?: "gemini-3.1-flash-live-preview",
            systemPrompt = p[SessionManager.KEY_SYSTEM_PROMPT] ?: SessionManager.DEFAULT_SYSTEM_PROMPT,
            volume = p[KEY_VOLUME] ?: 1.0f,
            micGain = p[KEY_MIC_GAIN] ?: 1.25f,
            enableForvo = p[SessionManager.KEY_ENABLE_FORVO] ?: false,
            forvoApiKey = p[KEY_FORVO_API].orEmpty()
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettingsState())

    fun setApiKey(k: String) = viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_API] = k.trim() } }
    fun setModel(m: String) = viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_MODEL] = m.trim() } }
    fun setSystemPrompt(sp: String) = viewModelScope.launch {
        dataStore.edit { it[SessionManager.KEY_SYSTEM_PROMPT] = sp }
        sessionManager.updatePrompt(sp)
    }
    fun setVolume(v: Float) = viewModelScope.launch { dataStore.edit { it[KEY_VOLUME] = v } }
    fun setMicGain(g: Float) = viewModelScope.launch { dataStore.edit { it[KEY_MIC_GAIN] = g } }
    fun setEnableForvo(e: Boolean) = viewModelScope.launch { dataStore.edit { it[SessionManager.KEY_ENABLE_FORVO] = e } }
    fun setForvoApiKey(k: String) = viewModelScope.launch { dataStore.edit { it[KEY_FORVO_API] = k.trim() } }
}