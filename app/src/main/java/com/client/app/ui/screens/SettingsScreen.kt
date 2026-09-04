// >>> FILE: app/src/main/java/com/client/app/ui/screens/SettingsScreen.kt
package com.client.app.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.attach.VocabularyExtractor
import com.client.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showGeminiKey by remember { mutableStateOf(false) }
    var showForvoKey by remember { mutableStateOf(false) }

    // Локальные состояния слайдеров для ультраплавного отклика без дискового флуда
    var volumeDraft by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
    var micGainDraft by remember(settings.micGain) { mutableFloatStateOf(settings.micGain) }

    val coreVoices = listOf("Charon", "Puck", "Kore", "Fenrir", "Aoede")
    val liveModels = listOf("gemini-3.1-flash-live-preview", "gemini-2.5-flash-native-audio-latest")
    val visionModels = listOf(VocabularyExtractor.DEFAULT_MODEL, "gemini-2.5-flash")

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = Color(0xFFFAFAFA), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color(0xFFFAFAFA))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF09090B))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // КЛЮЧИ И СЕРВЕРНЫЕ МОДЕЛИ
            SettingsCard(title = "КЛЮЧИ И СЕРВЕР GEMINI") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text("Gemini API Key") },
                    singleLine = true,
                    visualTransformation = if (showGeminiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showGeminiKey = !showGeminiKey }) {
                            Icon(
                                imageVector = if (showGeminiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = "Показать ключ",
                                tint = Color(0xFFA1A1AA)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors()
                )

                Spacer(Modifier.height(12.dp))

                Text("Голосовая модель Gemini Live (дуплекс):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    liveModels.forEach { m ->
                        FilterChip(
                            selected = settings.model == m,
                            onClick = { viewModel.setModel(m) },
                            label = { Text(m, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF064E3B),
                                selectedLabelColor = Color(0xFF6EE7B7),
                                containerColor = Color(0xFF18181B),
                                labelColor = Color(0xFFA1A1AA)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                Text("Модель разбора материалов (Vision OCR):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    visionModels.forEach { m ->
                        FilterChip(
                            selected = settings.analyzerModel == m,
                            onClick = { viewModel.setAnalyzerModel(m) },
                            label = { Text(m, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1E3A8A),
                                selectedLabelColor = Color(0xFF93C5FD),
                                containerColor = Color(0xFF18181B),
                                labelColor = Color(0xFFA1A1AA)
                            )
                        )
                    }
                }
            }

            // ГОЛОС И АУДИОСТЕК S23 ULTRA
            SettingsCard(title = "ГОЛОС И ЗВУКОВАЯ СИСТЕМА S23 ULTRA") {
                Text("Голос модели (TTS):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    coreVoices.forEach { v ->
                        FilterChip(
                            selected = settings.voice == v,
                            onClick = { viewModel.setVoice(v) },
                            label = { Text(v, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF064E3B),
                                selectedLabelColor = Color(0xFF6EE7B7),
                                containerColor = Color(0xFF18181B),
                                labelColor = Color(0xFFA1A1AA)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                Text("Громкость динамика: ${(volumeDraft * 100).toInt()}%", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Slider(
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it },
                    onValueChangeFinished = { viewModel.setVolume(volumeDraft) },
                    valueRange = 0.3f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF60A5FA),
                        activeTrackColor = Color(0xFF3B82F6),
                        inactiveTrackColor = Color(0xFF27272A)
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Чувствительность микрофона: ${(micGainDraft * 100).toInt()}%" +
                            if (micGainDraft > 1.0f) " (Усиление для тихой речи)" else "",
                    color = Color(0xFFE4E4E7),
                    fontSize = 13.sp
                )
                Slider(
                    value = micGainDraft,
                    onValueChange = { micGainDraft = it },
                    onValueChangeFinished = { viewModel.setMicGain(micGainDraft) },
                    valueRange = 0.5f..1.5f,
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF60A5FA),
                        activeTrackColor = Color(0xFF3B82F6),
                        inactiveTrackColor = Color(0xFF27272A)
                    )
                )

                Text(
                    text = "* Смена голоса и моделей применится при следующем подключении",
                    color = Color(0xFF71717A),
                    fontSize = 11.sp
                )
            }

            // СИСТЕМНЫЙ ПРОМПТ ПО УМОЛЧАНИЮ
            SettingsCard(title = "СИСТЕМНЫЙ ПРОМПТ ПО УМОЛЧАНИЮ") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = viewModel::setSystemPrompt,
                    label = { Text("Базовая инструкция ассистента") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                    maxLines = 8,
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors()
                )
            }

            // ИНТЕГРАЦИЯ С FORVO
            SettingsCard(title = "ИНТЕГРАЦИЯ С FORVO") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Включить Forvo Tool", color = Color(0xFFFAFAFA), fontSize = 14.sp)
                        Text("Озвучка слов носителями языка с Forvo.com", color = Color(0xFF71717A), fontSize = 12.sp)
                    }
                    Switch(checked = settings.enableForvo, onCheckedChange = viewModel::setEnableForvo)
                }

                if (settings.enableForvo) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.forvoApiKey,
                        onValueChange = viewModel::setForvoApiKey,
                        label = { Text("Forvo API Key") },
                        singleLine = true,
                        visualTransformation = if (showForvoKey) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showForvoKey = !showForvoKey }) {
                                Icon(
                                    imageVector = if (showForvoKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Показать Forvo ключ",
                                    tint = Color(0xFFA1A1AA)
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = darkFieldColors()
                    )
                }
            }

            // ОПТИМИЗАЦИЯ ПИТАНИЯ SAMSUNG ONE UI
            SettingsCard(title = "ФОНОВАЯ РАБОТА НА SAMSUNG ONE UI") {
                Text(
                    text = "Samsung One UI может усыплять микрофонный сервис через 10–15 минут сна экрана. " +
                            "Нажмите кнопку ниже, найдите в системном списке «Gemini Live Ultra» и выберите «Не оптимизировать» (Работа без ограничений).",
                    color = Color(0xFFA1A1AA),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "Откройте: Настройки -> Приложения -> Батарея -> Без ограничений", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF60A5FA))
                ) {
                    Icon(Icons.Filled.BatteryChargingFull, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Настройки батареи устройства", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141416))
            .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Color(0xFF71717A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun darkFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Color(0xFF60A5FA),
    unfocusedBorderColor = Color(0xFF27272A),
    focusedContainerColor = Color(0xFF09090B),
    unfocusedContainerColor = Color(0xFF09090B),
    focusedTextColor = Color(0xFFFAFAFA),
    unfocusedTextColor = Color(0xFFFAFAFA),
    focusedLabelColor = Color(0xFF60A5FA),
    unfocusedLabelColor = Color(0xFF71717A)
)