// >>> FILE: app/src/main/java/com/client/app/ui/screens/SettingsScreen.kt
package com.client.app.ui.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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

    val liveModels = SettingsViewModel.LIVE_MODEL_OPTIONS

    var volumeDraft by remember(settings.volume) { mutableFloatStateOf(settings.volume) }
    var micGainDraft by remember(settings.micGain) { mutableFloatStateOf(settings.micGain) }
    var tempDraft by remember(settings.temperature) { mutableFloatStateOf(settings.temperature) }
    var prefixPaddingDraft by remember(settings.prefixPaddingMs) { mutableIntStateOf(settings.prefixPaddingMs) }
    var silenceDurationDraft by remember(settings.silenceDurationMs) { mutableIntStateOf(settings.silenceDurationMs) }
    var historyTurnsDraft by remember(settings.initialHistoryTurns) { mutableIntStateOf(settings.initialHistoryTurns) }

    val coreVoices = listOf("Charon", "Puck", "Kore", "Fenrir", "Aoede")
    val resolutions = listOf("MEDIA_RESOLUTION_HIGH", "MEDIA_RESOLUTION_MEDIUM", "MEDIA_RESOLUTION_LOW")
    val txModes = listOf("VERBATIM", "SMART")
    val sensitivities = listOf("START_SENSITIVITY_HIGH", "START_SENSITIVITY_LOW")
    val endSensitivities = listOf("END_SENSITIVITY_LOW", "END_SENSITIVITY_HIGH")
    val activityHandlings = listOf("START_OF_ACTIVITY_INTERRUPTS", "NO_INTERRUPTION")
    val turnCoverages = listOf(
        "TURN_INCLUDES_AUDIO_ACTIVITY_AND_ALL_VIDEO",
        "TURN_INCLUDES_ONLY_ACTIVITY",
        "TURN_INCLUDES_ALL_INPUT"
    )

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            TopAppBar(
                title = { Text("Параметры Gemini 3.8 Live", color = Color(0xFFFAFAFA), fontSize = 18.sp, fontWeight = FontWeight.SemiBold) },
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF1E293B))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Info, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Изменения параметров сессии применяются при следующем подключении.",
                        color = Color(0xFFCBD5E1),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            // 1. API & Model
            SettingsCard(title = "1. API КЛЮЧ И МОДЕЛЬ") {
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
                                contentDescription = null,
                                tint = Color(0xFFA1A1AA)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors()
                )

                Spacer(Modifier.height(10.dp))
                Text("Live модель:", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    liveModels.forEach { model ->
                        FilterChip(
                            selected = settings.liveModel == model,
                            onClick = { viewModel.setLiveModel(model) },
                            label = {
                                Text(
                                    model,
                                    fontSize = 10.sp
                                )
                            },
                            colors = chipColors()
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Анализатор (OCR/Vision):", color = Color(0xFFA1A1AA), fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(settings.analyzerModel, color = Color(0xFF60A5FA), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // 2. Generation & Media
            SettingsCard(title = "2. ПАРАМЕТРЫ ГЕНЕРАЦИИ И МЕДИА") {
                Text("Температура декодера: ${"%.2f".format(tempDraft)}", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Slider(
                    value = tempDraft,
                    onValueChange = { tempDraft = it },
                    onValueChangeFinished = { viewModel.setTemperature(tempDraft) },
                    valueRange = 0.0f..1.5f,
                    colors = sliderColors()
                )

                Spacer(Modifier.height(8.dp))
                Text("Разрешение медиа / видео (mediaResolution):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    resolutions.forEach { res ->
                        FilterChip(
                            selected = settings.mediaResolution == res,
                            onClick = { viewModel.setMediaResolution(res) },
                            label = { Text(res.removePrefix("MEDIA_RESOLUTION_"), fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }
            }

            // 3. Voice & Speech
            SettingsCard(title = "3. ГОЛОС И ЯЗЫК СИНТЕЗА") {
                Text("Голос модели (voiceName):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
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
                            colors = chipColors()
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.speechLanguage,
                    onValueChange = viewModel::setSpeechLanguage,
                    label = { Text("BCP-47 язык синтеза (например: ru-RU, en-US, de-DE; пусто = автоопределение)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors()
                )
            }

            // 4. Input Audio Transcription
            ExpandableSettingsCard(title = "4. ТРАНСКРИПЦИЯ ВХОДА (INPUT ASR)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Включить распознавание речи пользователя", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                    Switch(checked = settings.inputTxEnabled, onCheckedChange = viewModel::setInputTxEnabled)
                }

                if (settings.inputTxEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text("Режим распознавания (mode):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        txModes.forEach { mode ->
                            FilterChip(
                                selected = settings.inputTxMode == mode,
                                onClick = { viewModel.setInputTxMode(mode) },
                                label = { Text(mode, fontSize = 11.sp) },
                                colors = chipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.inputTxLanguages,
                        onValueChange = viewModel::setInputTxLanguages,
                        label = { Text("BCP-47 языки через запятую (например: ru-RU, en-US)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = darkFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.inputTxVocab,
                        onValueChange = viewModel::setInputTxVocab,
                        label = { Text("Кастомный словарь терминов (до 1000 слов через запятую)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = darkFieldColors()
                    )
                }
            }

            // 5. Output Audio Transcription
            ExpandableSettingsCard(title = "5. ТРАНСКРИПЦИЯ ВЫВОДА (OUTPUT ASR)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Генерировать текст речи модели", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                    Switch(checked = settings.outputTxEnabled, onCheckedChange = viewModel::setOutputTxEnabled)
                }

                if (settings.outputTxEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text("Режим вывода (mode):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        txModes.forEach { mode ->
                            FilterChip(
                                selected = settings.outputTxMode == mode,
                                onClick = { viewModel.setOutputTxMode(mode) },
                                label = { Text(mode, fontSize = 11.sp) },
                                colors = chipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.outputTxLanguages,
                        onValueChange = viewModel::setOutputTxLanguages,
                        label = { Text("BCP-47 языки через запятую (пусто = авто)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = darkFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.outputTxVocab,
                        onValueChange = viewModel::setOutputTxVocab,
                        label = { Text("Кастомный словарь вывода (до 1000 слов через запятую)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = darkFieldColors()
                    )
                }
            }

            // 6. Realtime Input / VAD / Turn Detection
            ExpandableSettingsCard(title = "6. ДЕТЕКЦИЯ РЕЧИ (СЕРВЕРНЫЙ VAD / AAD)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Автоматическая детекция речи (AAD)", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                        Text("При выключении используется ручной режим activityStart/End", color = Color(0xFF71717A), fontSize = 11.sp)
                    }
                    Switch(checked = settings.aadEnabled, onCheckedChange = viewModel::setAadEnabled)
                }

                if (settings.aadEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("Чувствительность старта речи (startOfSpeechSensitivity):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sensitivities.forEach { s ->
                            FilterChip(
                                selected = settings.aadStartSensitivity == s,
                                onClick = { viewModel::setAadStartSensitivity(s) },
                                label = { Text(s.removePrefix("START_SENSITIVITY_"), fontSize = 11.sp) },
                                colors = chipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Чувствительность конца речи (endOfSpeechSensitivity):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        endSensitivities.forEach { s ->
                            FilterChip(
                                selected = settings.aadEndSensitivity == s,
                                onClick = { viewModel.setAadEndSensitivity(s) },
                                label = { Text(s.removePrefix("END_SENSITIVITY_"), fontSize = 11.sp) },
                                colors = chipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    Text("Префиксный буфер тишины: ${prefixPaddingDraft} мс", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                    Slider(
                        value = prefixPaddingDraft.toFloat(),
                        onValueChange = { prefixPaddingDraft = it.toInt() },
                        onValueChangeFinished = { viewModel.setPrefixPaddingMs(prefixPaddingDraft) },
                        valueRange = 0f..300f,
                        colors = sliderColors()
                    )

                    Text("Тишина для закрытия хода: ${silenceDurationDraft} мс", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                    Slider(
                        value = silenceDurationDraft.toFloat(),
                        onValueChange = { silenceDurationDraft = it.toInt() },
                        onValueChangeFinished = { viewModel.setSilenceDurationMs(silenceDurationDraft) },
                        valueRange = 200f..1500f,
                        colors = sliderColors()
                    )
                }

                Spacer(Modifier.height(10.dp))
                Text("Политика перебивания (activityHandling):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activityHandlings.forEach { h ->
                        FilterChip(
                            selected = settings.activityHandling == h,
                            onClick = { viewModel.setActivityHandling(h) },
                            label = { Text(if (h.contains("INTERRUPTS")) "Перебивать" else "Без прерывания", fontSize = 11.sp) },
                            colors = chipColors()
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text("Покрытие данных в ходе (turnCoverage):", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    turnCoverages.forEach { tc ->
                        FilterChip(
                            selected = settings.turnCoverage == tc,
                            onClick = { viewModel.setTurnCoverage(tc) },
                            label = { Text(tc, fontSize = 10.sp) },
                            colors = chipColors()
                        )
                    }
                }
            }

            // 7. Context & Resumption
            ExpandableSettingsCard(title = "7. УПРАВЛЕНИЕ КОНТЕКСТОМ И СЕССИЕЙ") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Сжатие контекста (slidingWindow)", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                        Text("0/0 = серверные пороги; trigger > target > 0 для кастомных", color = Color(0xFF71717A), fontSize = 11.sp)
                    }
                    Switch(checked = settings.compressionEnabled, onCheckedChange = viewModel::setCompressionEnabled)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Восстановление сессии (sessionResumption)", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                        Text("Бесшовный реконнект по токену handle при сбоях сети", color = Color(0xFF71717A), fontSize = 11.sp)
                    }
                    Switch(checked = settings.sessionResumptionEnabled, onCheckedChange = viewModel::setSessionResumptionEnabled)
                }

                Spacer(Modifier.height(14.dp))
                Text("Загружаемая начальная история: ${historyTurnsDraft} ходов", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Slider(
                    value = historyTurnsDraft.toFloat(),
                    onValueChange = { historyTurnsDraft = it.toInt() },
                    onValueChangeFinished = { viewModel.setInitialHistoryTurns(historyTurnsDraft) },
                    valueRange = 5f..50f,
                    colors = sliderColors()
                )
            }

            // 8. Tools & Grounding
            SettingsCard(title = "8. ИНСТРУМЕНТЫ (TOOLS & GROUNDING)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Search Grounding", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                        Text("Поиск актуальной информации в интернете во время речи", color = Color(0xFF71717A), fontSize = 11.sp)
                    }
                    Switch(checked = settings.enableGoogleSearch, onCheckedChange = viewModel::setEnableGoogleSearch)
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Интеграция с Forvo (Произношение)", color = Color(0xFFFAFAFA), fontSize = 13.sp)
                        Text("Асинхронный инструмент (NON_BLOCKING + WHEN_IDLE)", color = Color(0xFF71717A), fontSize = 11.sp)
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
                                    contentDescription = null,
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

            // 9. Hardware Audio
            SettingsCard(title = "9. АППАРАТНЫЙ ТРАКТ GALAXY S23 ULTRA") {
                Text("Громкость ЦАП WCD9385: ${(volumeDraft * 100).toInt()}%", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Slider(
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it },
                    onValueChangeFinished = { viewModel.setVolume(volumeDraft) },
                    valueRange = 0.3f..1.0f,
                    colors = sliderColors()
                )

                Spacer(Modifier.height(8.dp))
                Text("Чувствительность АЦП микрофона: ${(micGainDraft * 100).toInt()}%", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Slider(
                    value = micGainDraft,
                    onValueChange = { micGainDraft = it },
                    onValueChangeFinished = { viewModel.setMicGain(micGainDraft) },
                    valueRange = 0.5f..2.0f,
                    colors = sliderColors()
                )
            }

            // 10. System Prompt
            SettingsCard(title = "10. СИСТЕМНАЯ ИНСТРУКЦИЯ РОЛИ") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = viewModel::setSystemPrompt,
                    label = { Text("Системный промпт (setup.systemInstruction)") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp),
                    maxLines = 8,
                    shape = RoundedCornerShape(10.dp),
                    colors = darkFieldColors()
                )
            }

            // 11. System Information
            SettingsCard(title = "11. СИСТЕМНАЯ ИНФОРМАЦИЯ") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Proactive Audio: Всегда активно (Gemini 3.8 Live)", color = Color(0xFFE4E4E7), fontSize = 12.sp)
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Рассуждения: Interleaved Reasoning (Нативно)", color = Color(0xFFE4E4E7), fontSize = 12.sp)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Аппаратный дуплекс защищен системной MediaSessionCompat (STATE_PLAYING), Partial WakeLock и Low Latency WiFi Lock. Для предотвращения засыпания микрофона отключите оптимизацию батареи.",
                    color = Color(0xFFA1A1AA),
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "Откройте Настройки -> Приложения -> Батарея -> Без ограничений", Toast.LENGTH_LONG).show()
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
private fun ExpandableSettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF141416))
            .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, color = Color(0xFF71717A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Color(0xFFA1A1AA)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                Spacer(Modifier.height(10.dp))
                content()
            }
        }
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

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Color(0xFF60A5FA),
    activeTrackColor = Color(0xFF3B82F6),
    inactiveTrackColor = Color(0xFF27272A)
)

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color(0xFF064E3B),
    selectedLabelColor = Color(0xFF6EE7B7),
    containerColor = Color(0xFF18181B),
    labelColor = Color(0xFFA1A1AA)
)