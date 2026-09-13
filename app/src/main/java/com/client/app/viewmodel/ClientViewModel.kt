// >>> FILE: app/src/main/java/com/client/app/viewmodel/ClientViewModel.kt
package com.client.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.client.app.audio.NativeAudioEngine
import com.client.app.logging.AppLogManager
import com.client.app.session.ForvoWord
import com.client.app.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val sessionManager: SessionManager,
    val nativeAudioEngine: NativeAudioEngine,
    val logManager: AppLogManager
) : ViewModel() {

    val state = sessionManager.state
    val amplitude = sessionManager.amplitude
    val errorCount: StateFlow<Int> = logManager.errorCount

    fun toggleConnection() = sessionManager.toggleConnection()
    fun stopSession() = sessionManager.stopSession()
    fun toggleMic() = sessionManager.toggleMic()
    fun applyPrompt(p: String) = sessionManager.applyPrompt(p)
    fun sendText(text: String, uris: List<Uri>) = sessionManager.sendText(text, uris)
    fun playForvo(word: ForvoWord) = sessionManager.playForvo(word)
    fun refetchAllForvo() = sessionManager.refetchAllForvo()
    fun clearForvo() = sessionManager.clearForvo()
    fun clearError() = sessionManager.clearError()
    fun clearChat() = sessionManager.clearChat()
}