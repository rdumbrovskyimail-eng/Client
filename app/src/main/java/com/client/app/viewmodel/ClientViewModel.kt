package com.client.app.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import com.client.app.session.ForvoWord
import com.client.app.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ClientViewModel @Inject constructor(
    private val sessionManager: SessionManager
) : ViewModel() {
    val state = sessionManager.state
    val amplitude = sessionManager.amplitude

    fun toggleConnection() = sessionManager.toggleConnection()
    fun toggleMic() = sessionManager.toggleMic()
    fun applyPrompt(p: String) = sessionManager.updatePrompt(p)
    fun sendText(text: String, uris: List<Uri>) = sessionManager.sendText(text, uris)
    fun playForvo(word: ForvoWord) = sessionManager.playForvo(word)
    fun clearForvo() = sessionManager.clearForvo()
    fun clearError() = sessionManager.clearError()
}