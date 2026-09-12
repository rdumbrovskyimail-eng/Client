// >>> FILE: app/src/main/java/com/client/app/ui/components/NeoVoiceVisualizer.kt
package com.client.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Устаревший 2D Canvas визуализатор.
 * Заменен на аппаратный однопроходный 120 FPS AGSL-шейдер AgslVoiceVisualizer.
 */
@Deprecated("Заменен на AgslVoiceVisualizer", ReplaceWith("AgslVoiceVisualizer"))
@Composable
fun NeoVoiceVisualizer(
    amplitude: Float,
    isConnected: Boolean,
    isConnecting: Boolean,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    hasError: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 170.dp
) {
    // Сохранено для исключения ошибок старых ссылок
}