package com.client.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.session.LinkState
import com.client.app.ui.components.FmStripAudioVisualizer
import com.client.app.ui.components.MaterialsConsoleDrawer
import com.client.app.ui.components.PromptConsoleDrawer
import com.client.app.ui.components.SessionControlPill
import com.client.app.ui.components.SettingsGearButton
import com.client.app.viewmodel.ClientViewModel

// Константы белого минимализма для главного холста
private val ColorCanvasWhite = Color(0xFFFFFFFF)
private val ColorErrorCard = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFEBEBEB)
private val ColorErrorLed = Color(0xFFEA4335) // красный Gemini
private val ColorTextPrimary = Color(0xFF09090B)
private val ColorTextSecondary = Color(0xFF71717A)

/**
 * Главный экран приложения: «Чистый белый лист» флагманского уровня на базе Samsung Galaxy S23 Ultra.
 * Полностью исключает чат и сферу, концентрируя внимание на аналоговой FM-полосе звука,
 * поворотной черной шестеренке и трех физических выдвижных органах управления.
 */
@Composable
fun ClientScreen(
    onNavigateSettings: () -> Unit,
    onNavigateLogs: () -> Unit,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Обработчик системных разрешений на микрофон и сопутствующие модули
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (audioGranted) {
            viewModel.toggleConnection()
        }
    }

    // Запуск сессии с предварительной проверкой разрешений
    fun handleSessionClick() {
        val requiredMissing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requiredMissing.add(Manifest.permission.RECORD_AUDIO)
        }

        val optionalMissing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            optionalMissing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            optionalMissing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (requiredMissing.isEmpty()) {
            viewModel.toggleConnection()
            if (optionalMissing.isNotEmpty()) {
                permissionsLauncher.launch(optionalMissing.toTypedArray())
            }
        } else {
            permissionsLauncher.launch((requiredMissing + optionalMissing).toTypedArray())
        }
    }

    // =========================================================================
    // БАЗОВЫЙ ХОЛСТ: БЕЛЫЙ ЛИСТ (Screen Canvas)
    // =========================================================================
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorCanvasWhite)
    ) {
        val screenHeight = maxHeight
        // Высота FM-полоски строго на отметке ~1/5 (19-20%) высоты экрана
        val fmTopOffset = screenHeight * 0.19f

        // =====================================================================
        // 1. АКУСТИЧЕСКАЯ FM-ПОЛОСКА ВИЗУАЛИЗАЦИИ ЗВУКА (Спикер Gemini Live)
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = fmTopOffset)
                .padding(horizontal = 24.dp)
        ) {
            FmStripAudioVisualizer(
                nativeEngine = viewModel.nativeAudioEngine,
                state = state
            )
        }

        // =====================================================================
        // 2. ВЕРХНИЙ ПРАВЫЙ УГОЛ: ЧЕРНАЯ ШЕСТЕРЕНКА НАСТРОЕК С ПРУЖИННЫМ КРУЧЕНИЕМ
        // =====================================================================
        SettingsGearButton(
            onOpenSettings = onNavigateSettings,
            onOpenLogs = onNavigateLogs,
            errorCount = errorCount,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 16.dp, end = 20.dp)
        )

        // =====================================================================
        // 3. НИЖНЯЯ ПРАВАЯ ПЛАШКА: "START A SESSION" (Черный -> Изумрудный)
        // =====================================================================
        SessionControlPill(
            linkState = state.link,
            onClick = { handleSessionClick() },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.navigationBars)
        )

        // =====================================================================
        // 4. ЛЕВАЯ НИЖНЯЯ ПЛАШКА И ШУХЛЯДКА ВЛОЖЕНИЙ: "MATERIALS" (Черный -> Лазурный)
        // =====================================================================
        MaterialsConsoleDrawer(
            onSendMaterials = { text, uris ->
                viewModel.sendText(text, uris)
            },
            modifier = Modifier.fillMaxSize()
        )

        // =====================================================================
        // 5. ЛЕВАЯ ЦЕНТРАЛЬНАЯ ПЛАШКА И ШУХЛЯДКА РОЛИ: "PROMPT" (Радуга "веселка")
        // =====================================================================
        PromptConsoleDrawer(
            currentPrompt = state.activePrompt,
            onApplyPrompt = { newPrompt ->
                viewModel.applyPrompt(newPrompt)
            },
            modifier = Modifier.fillMaxSize()
        )

        // =====================================================================
        // 6. ДЕЛИКАТНЫЙ СИСТЕМНЫЙ БЕЙДЖ ОШИБОК (Появляется только при сбое)
        // =====================================================================
        AnimatedVisibility(
            visible = state.error != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp, start = 60.dp, end = 60.dp)
        ) {
            state.error?.let { errorMessage ->
                Box(
                    modifier = Modifier
                        .shadow(
                            elevation = 8.dp,
                            shape = RoundedCornerShape(16.dp),
                            ambientColor = Color(0x10000000),
                            spotColor = Color(0x1A000000)
                        )
                        .clip(RoundedCornerShape(16.dp))
                        .background(ColorErrorCard)
                        .border(1.dp, ColorHairline, RoundedCornerShape(16.dp))
                        .clickable { viewModel.clearError() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Микросветодиод статуса ошибки
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(ColorErrorLed)
                        )

                        Text(
                            text = errorMessage,
                            color = ColorTextPrimary,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.SansSerif,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Закрыть ошибку",
                            tint = ColorTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}