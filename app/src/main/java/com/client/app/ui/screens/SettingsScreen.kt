package com.client.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.viewmodel.SettingsViewModel

// Палитра белого минимализма для экрана настроек
private val ColorCanvasWhite = Color(0xFFFFFFFF)
private val ColorCardBackground = Color(0xFFFAFAFA)
private val ColorFieldBackground = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFE2E8F0)
private val ColorTextPrimary = Color(0xFF09090B)
private val ColorTextSecondary = Color(0xFF71717A)
private val ColorTextMuted = Color(0xFFA1A1AA)

private val ColorControlBlack = Color(0xFF09090B)
private val ColorControlWhite = Color(0xFFFFFFFF)

/**
 * Тактильный микроотклик для переключателей и чипов настроек.
 */
private fun performSettingsHaptic(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.60f, 0)
                        .compose()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                } else {
                    vibrator.vibrate(10L)
                }
            }
        }
    }
}

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
        containerColor = ColorCanvasWhite,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "ПАРАМЕТРЫ GEMINI 3.8 LIVE",
                        color = ColorTextPrimary,
                        fontSize = 14.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = ColorTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ColorCanvasWhite)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            // Информационный баннер архитектуры белого листа
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF8FAFC))
                    .border(1.dp, ColorHairline, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = null,
                        tint = ColorTextPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "Изменения параметров применяются при следующем цикле запуска сессии.",
                        color = ColorTextSecondary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.SansSerif,
                        lineHeight = 16.sp
                    )
                }
            }

            // 1. API КЛЮЧ И МОДЕЛЬ
            SettingsCard(title = "1. API КЛЮЧ И МОДЕЛЬ ДУПЛЕКСА") {
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
                                tint = ColorTextMuted
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = whiteFieldColors()
                )

                Spacer(Modifier.height(10.dp))
                Text("Голосовая модель Gemini Live:", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    liveModels.forEach { model ->
                        FilterChip(
                            selected = settings.liveModel == model,
                            onClick = {
                                performSettingsHaptic(context)
                                viewModel.setLiveModel(model)
                            },
                            label = { Text(model, fontSize = 11.sp, fontFamily = FontFamily.Monospace) },
                            colors = whiteChipColors()
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vision OCR анализатор:", color = ColorTextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = settings.analyzerModel,
                        color = ColorTextPrimary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. ПАРАМЕТРЫ ГЕНЕРАЦИИ И МЕДИА
            SettingsCard(title = "2. ПАРАМЕТРЫ ГЕНЕРАЦИИ И МЕДИА") {
                Text(
                    text = "Температура декодера: ${"%.2f".format(tempDraft)}",
                    color = ColorTextPrimary,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = tempDraft,
                    onValueChange = { tempDraft = it },
                    onValueChangeFinished = { viewModel.setTemperature(tempDraft) },
                    valueRange = 0.0f..1.5f,
                    colors = whiteSliderColors()
                )

                Spacer(Modifier.height(6.dp))
                Text("Разрешение медиа (mediaResolution):", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    resolutions.forEach { res ->
                        FilterChip(
                            selected = settings.mediaResolution == res,
                            onClick = {
                                performSettingsHaptic(context)
                                viewModel.setMediaResolution(res)
                            },
                            label = { Text(res.removePrefix("MEDIA_RESOLUTION_"), fontSize = 11.sp) },
                            colors = whiteChipColors()
                        )
                    }
                }
            }

            // 3. ГОЛОС И ЯЗЫК СИНТЕЗА
            SettingsCard(title = "3. ГОЛОС И ЯЗЫК СИНТЕЗА") {
                Text("Голос ассистента (voiceName):", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    coreVoices.forEach { v ->
                        FilterChip(
                            selected = settings.voice == v,
                            onClick = {
                                performSettingsHaptic(context)
                                viewModel.setVoice(v)
                            },
                            label = { Text(v, fontSize = 11.5.sp) },
                            colors = whiteChipColors()
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.speechLanguage,
                    onValueChange = viewModel::setSpeechLanguage,
                    label = { Text("Языковой код BCP-47 (например: ru-RU, en-US, de-DE)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = whiteFieldColors()
                )
            }

            // 4. ТРАНСКРИПЦИЯ ВХОДА (INPUT ASR)
            ExpandableSettingsCard(title = "4. ТРАНСКРИПЦИЯ ВХОДА (INPUT ASR)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Распознавание речи пользователя", color = ColorTextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = settings.inputTxEnabled,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setInputTxEnabled(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                if (settings.inputTxEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text("Режим распознавания:", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        txModes.forEach { mode ->
                            FilterChip(
                                selected = settings.inputTxMode == mode,
                                onClick = {
                                    performSettingsHaptic(context)
                                    viewModel.setInputTxMode(mode)
                                },
                                label = { Text(mode, fontSize = 11.sp) },
                                colors = whiteChipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.inputTxLanguages,
                        onValueChange = viewModel::setInputTxLanguages,
                        label = { Text("BCP-47 коды языков через запятую") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = whiteFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.inputTxVocab,
                        onValueChange = viewModel::setInputTxVocab,
                        label = { Text("Кастомный словарь терминов (до 1000 слов)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = whiteFieldColors()
                    )
                }
            }

            // 5. ТРАНСКРИПЦИЯ ВЫВОДА (OUTPUT ASR)
            ExpandableSettingsCard(title = "5. ТРАНСКРИПЦИЯ ВЫВОДА (OUTPUT ASR)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Генерация субтитров речи модели", color = ColorTextPrimary, fontSize = 13.sp)
                    Switch(
                        checked = settings.outputTxEnabled,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setOutputTxEnabled(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                if (settings.outputTxEnabled) {
                    Spacer(Modifier.height(10.dp))
                    Text("Режим вывода:", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        txModes.forEach { mode ->
                            FilterChip(
                                selected = settings.outputTxMode == mode,
                                onClick = {
                                    performSettingsHaptic(context)
                                    viewModel.setOutputTxMode(mode)
                                },
                                label = { Text(mode, fontSize = 11.sp) },
                                colors = whiteChipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.outputTxLanguages,
                        onValueChange = viewModel::setOutputTxLanguages,
                        label = { Text("BCP-47 языки субтитров (пусто = авто)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = whiteFieldColors()
                    )

                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.outputTxVocab,
                        onValueChange = viewModel::setOutputTxVocab,
                        label = { Text("Кастомный словарь вывода через запятую") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = whiteFieldColors()
                    )
                }
            }

            // BLUETOOTH-НАУШНИКИ: Hi-Fi (A2DP + микрофон телефона) или гарнитура (HFP)
            ExpandableSettingsCard(title = "BLUETOOTH-НАУШНИКИ") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Hi-Fi звук (A2DP) + микрофон телефона", color = ColorTextPrimary, fontSize = 13.sp)
                        Text(
                            if (settings.btHiFiMode) {
                                "Стерео 48 кГц, тёплый полный голос. Держите телефон рядом: слушает его микрофон"
                            } else {
                                "Гарнитура HFP: микрофон наушников, звук ограничен полосой 8 кГц (моно)"
                            },
                            color = ColorTextMuted,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = settings.btHiFiMode,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setBtHiFiMode(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }
            }

            // 6. ДЕТЕКЦИЯ РЕЧИ (СЕРВЕРНЫЙ VAD / AAD)
            ExpandableSettingsCard(title = "6. ДЕТЕКЦИЯ РЕЧИ (СЕРВЕРНЫЙ VAD / AAD)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Автоматическая детекция речи (AAD)", color = ColorTextPrimary, fontSize = 13.sp)
                        Text("При отключении используется ручной режим активности", color = ColorTextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.aadEnabled,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setAadEnabled(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                if (settings.aadEnabled) {
                    Spacer(Modifier.height(12.dp))
                    Text("Чувствительность начала речи:", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        sensitivities.forEach { s ->
                            FilterChip(
                                selected = settings.aadStartSensitivity == s,
                                onClick = {
                                    performSettingsHaptic(context)
                                    viewModel.setAadStartSensitivity(s)
                                },
                                label = { Text(s.removePrefix("START_SENSITIVITY_"), fontSize = 11.sp) },
                                colors = whiteChipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Text("Чувствительность окончания речи:", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        endSensitivities.forEach { s ->
                            FilterChip(
                                selected = settings.aadEndSensitivity == s,
                                onClick = {
                                    performSettingsHaptic(context)
                                    viewModel.setAadEndSensitivity(s)
                                },
                                label = { Text(s.removePrefix("END_SENSITIVITY_"), fontSize = 11.sp) },
                                colors = whiteChipColors()
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text("Префиксный буфер тишины: ${prefixPaddingDraft} мс", color = ColorTextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    Slider(
                        value = prefixPaddingDraft.toFloat(),
                        onValueChange = { prefixPaddingDraft = it.toInt() },
                        onValueChangeFinished = { viewModel.setPrefixPaddingMs(prefixPaddingDraft) },
                        valueRange = 0f..300f,
                        colors = whiteSliderColors()
                    )

                    Text("Пауза конца фразы: ${silenceDurationDraft} мс", color = ColorTextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                    Slider(
                        value = silenceDurationDraft.toFloat(),
                        onValueChange = { silenceDurationDraft = it.toInt() },
                        onValueChangeFinished = { viewModel.setSilenceDurationMs(silenceDurationDraft) },
                        valueRange = 200f..1500f,
                        colors = whiteSliderColors()
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("Политика перебивания (activityHandling):", color = ColorTextSecondary, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    activityHandlings.forEach { h ->
                        FilterChip(
                            selected = settings.activityHandling == h,
                            onClick = {
                                performSettingsHaptic(context)
                                viewModel.setActivityHandling(h)
                            },
                            label = { Text(if (h.contains("INTERRUPTS")) "Перебивать" else "Без прерывания", fontSize = 11.sp) },
                            colors = whiteChipColors()
                        )
                    }
                }
            }

            // 7. УПРАВЛЕНИЕ КОНТЕКСТОМ И СЕССИЕЙ
            ExpandableSettingsCard(title = "7. УПРАВЛЕНИЕ КОНТЕКСТОМ И СЕССИЕЙ") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Сжатие контекста (slidingWindow)", color = ColorTextPrimary, fontSize = 13.sp)
                        Text("Автоматическое скольжение окна токенов", color = ColorTextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.compressionEnabled,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setCompressionEnabled(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Восстановление сессии (sessionResumption)", color = ColorTextPrimary, fontSize = 13.sp)
                        Text("Бесшовный реконнект по токену handle при сбоях сети", color = ColorTextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.sessionResumptionEnabled,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setSessionResumptionEnabled(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Загружаемая история диалога: ${historyTurnsDraft} ходов", color = ColorTextPrimary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
                Slider(
                    value = historyTurnsDraft.toFloat(),
                    onValueChange = { historyTurnsDraft = it.toInt() },
                    onValueChangeFinished = { viewModel.setInitialHistoryTurns(historyTurnsDraft) },
                    valueRange = 5f..50f,
                    colors = whiteSliderColors()
                )
            }

            // 8. ИНСТРУМЕНТЫ (TOOLS & GROUNDING)
            SettingsCard(title = "8. ИНСТРУМЕНТЫ (TOOLS & GROUNDING)") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Google Search Grounding", color = ColorTextPrimary, fontSize = 13.sp)
                        Text("Поиск актуальных фактов в веб-сети в реальном времени", color = ColorTextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.enableGoogleSearch,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setEnableGoogleSearch(it)
                        },
                        colors = whiteSwitchColors()
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Интеграция с Forvo (Произношение)", color = ColorTextPrimary, fontSize = 13.sp)
                        Text("Асинхронный инструмент произношения носителей", color = ColorTextMuted, fontSize = 11.sp)
                    }
                    Switch(
                        checked = settings.enableForvo,
                        onCheckedChange = {
                            performSettingsHaptic(context)
                            viewModel.setEnableForvo(it)
                        },
                        colors = whiteSwitchColors()
                    )
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
                                    tint = ColorTextMuted
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = whiteFieldColors()
                    )
                }
            }

            // 9. АППАРАТНЫЙ ТРАКТ GALAXY S23 ULTRA
            SettingsCard(title = "9. АППАРАТНЫЙ АУДИОТРАКТ S23 ULTRA") {
                Text(
                    text = "Громкость ЦАП Qualcomm WCD9385: ${(volumeDraft * 100).toInt()}%",
                    color = ColorTextPrimary,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = volumeDraft,
                    onValueChange = { volumeDraft = it },
                    onValueChangeFinished = { viewModel.setVolume(volumeDraft) },
                    valueRange = 0.3f..1.0f,
                    colors = whiteSliderColors()
                )

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Чувствительность АЦП микрофона: ${(micGainDraft * 100).toInt()}%",
                    color = ColorTextPrimary,
                    fontSize = 12.5.sp,
                    fontFamily = FontFamily.Monospace
                )
                Slider(
                    value = micGainDraft,
                    onValueChange = { micGainDraft = it },
                    onValueChangeFinished = { viewModel.setMicGain(micGainDraft) },
                    valueRange = 0.5f..2.0f,
                    colors = whiteSliderColors()
                )
            }

            // 10. СИСТЕМНАЯ ИНФОРМАЦИЯ И БАТАРЕЯ
            SettingsCard(title = "10. СИСТЕМНЫЙ СТАТУС И ЭНЕРГОПОТРЕБЛЕНИЕ") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Bolt, null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Proactive Audio Engine: AAudio MMAP / Shared", color = ColorTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Psychology, null, tint = ColorTextPrimary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Interleaved Reasoning: Включено нативно", color = ColorTextPrimary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Для предотвращения засыпания микрофона при отключенном экране выберите режим работы батареи «Без ограничений».",
                    color = ColorTextSecondary,
                    fontSize = 11.5.sp,
                    lineHeight = 15.sp
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        performSettingsHaptic(context)
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }.onFailure {
                            Toast.makeText(context, "Настройки -> Приложения -> Батарея -> Без ограничений", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ColorTextPrimary),
                    border = androidx.compose.foundation.BorderStroke(1.dp, ColorHairline)
                ) {
                    Icon(Icons.Filled.BatteryChargingFull, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Оптимизация питания One UI",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * Базовая белая карточка настроек в немецком функциональном стиле.
 */
@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x0A000000), spotColor = Color(0x10000000))
            .clip(RoundedCornerShape(14.dp))
            .background(ColorCardBackground)
            .border(1.dp, ColorHairline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title,
            color = ColorTextSecondary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp
        )
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/**
 * Раскрывающаяся белая карточка настроек с плавной стрелкой-индикатором.
 */
@Composable
private fun ExpandableSettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x0A000000), spotColor = Color(0x10000000))
            .clip(RoundedCornerShape(14.dp))
            .background(ColorCardBackground)
            .border(1.dp, ColorHairline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    performSettingsHaptic(context)
                    expanded = !expanded
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = ColorTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ColorTextPrimary,
                modifier = Modifier.size(18.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = androidx.compose.animation.expandVertically(animationSpec = tween(250, easing = FastOutSlowInEasing)),
            exit = androidx.compose.animation.shrinkVertically(animationSpec = tween(200))
        ) {
            Column {
                Spacer(Modifier.height(14.dp))
                content()
            }
        }
    }
}

@Composable
private fun whiteFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = ColorControlBlack,
    unfocusedBorderColor = ColorHairline,
    focusedContainerColor = ColorFieldBackground,
    unfocusedContainerColor = ColorFieldBackground,
    focusedTextColor = ColorTextPrimary,
    unfocusedTextColor = ColorTextPrimary,
    focusedLabelColor = ColorControlBlack,
    unfocusedLabelColor = ColorTextSecondary,
    cursorColor = ColorControlBlack
)

@Composable
private fun whiteSliderColors() = SliderDefaults.colors(
    thumbColor = ColorControlBlack,
    activeTrackColor = ColorControlBlack,
    inactiveTrackColor = ColorHairline
)

@Composable
private fun whiteChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = ColorControlBlack,
    selectedLabelColor = ColorControlWhite,
    containerColor = ColorFieldBackground,
    labelColor = ColorTextSecondary
)

@Composable
private fun whiteSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = ColorControlWhite,
    checkedTrackColor = ColorControlBlack,
    uncheckedThumbColor = ColorControlWhite,
    uncheckedTrackColor = ColorHairline,
    uncheckedBorderColor = ColorHairline
)