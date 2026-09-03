package com.client.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            TopAppBar(
                title = { Text("Настройки", color = Color(0xFFFAFAFA), fontSize = 18.sp) },
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
            SettingsCard(title = "КЛЮЧИ И СЕРВЕР") {
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = viewModel::setApiKey,
                    label = { Text("Gemini API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = viewModel::setModel,
                    label = { Text("Имя модели") },
                    placeholder = { Text("models/gemini-3.1-flash-live-preview") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            SettingsCard(title = "СИСТЕМНЫЙ ПРОМПТ ПО УМОЛЧАНИЮ") {
                OutlinedTextField(
                    value = settings.systemPrompt,
                    onValueChange = viewModel::setSystemPrompt,
                    label = { Text("Базовая инструкция ассистента") },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            SettingsCard(title = "АУДИО СИСТЕМА S23 ULTRA") {
                Text("Голос модели: Charon (Научный/Академический баритон)", color = Color(0xFFE4E4E7), fontSize = 13.sp)
                Spacer(Modifier.height(10.dp))
                Text("Громкость динамика: ${(settings.volume * 100).toInt()}%", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Slider(
                    value = settings.volume,
                    onValueChange = viewModel::setVolume,
                    valueRange = 0.5f..1.0f
                )
                Spacer(Modifier.height(6.dp))
                Text("Чувствительность микрофона: ${(settings.micGain * 100).toInt()}%", color = Color(0xFFA1A1AA), fontSize = 12.sp)
                Slider(
                    value = settings.micGain,
                    onValueChange = viewModel::setMicGain,
                    valueRange = 0.8f..2.0f
                )
            }

            SettingsCard(title = "ИНТЕГРАЦИЯ С FORVO") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Включить Forvo Tool", color = Color(0xFFFAFAFA), fontSize = 14.sp)
                        Text("Озвучка слов носителями языка через вызовы функций", color = Color(0xFF71717A), fontSize = 12.sp)
                    }
                    Switch(checked = settings.enableForvo, onCheckedChange = viewModel::setEnableForvo)
                }
                if (settings.enableForvo) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = settings.forvoApiKey,
                        onValueChange = viewModel::setForvoApiKey,
                        label = { Text("Forvo API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    )
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