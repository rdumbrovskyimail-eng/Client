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
 * Главный экран приложения с обновлённой пространственной геометрией:
 * - В самом верху: горизонтальный блок Prompt на всю ширину экрана.
 * - Чуть ниже Prompt: горизонтальная кнопка Materials.
 * - Под Materials справа: контроллер шестерёнки настроек и терминала логов.
 * - Внизу экрана: увеличенная в 2 раза FM-лента визуализации речи (168.dp),
 *   расположенная на симметричном расстоянии к низу экрана.
 * - Прямо под визуализатором: горизонтальная кнопка Start Session (~70% ширины визуализатора).
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

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (audioGranted && viewModel.state.value.link == LinkState.IDLE) {
            viewModel.toggleConnection()
        }
    }

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

        if (viewModel.state.value.link != LinkState.IDLE) {
            viewModel.toggleConnection()
            return
        }
        val allMissing = requiredMissing + optionalMissing
        if (allMissing.isEmpty()) {
            viewModel.toggleConnection()
        } else {
            permissionsLauncher.launch(allMissing.toTypedArray())
        }
    }

    // =========================================================================
    // БАЗОВЫЙ ХОЛСТ: ЧИСТЫЙ БЕЛЫЙ ЛИСТ
    // =========================================================================
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorCanvasWhite)
    ) {
        val screenHeight = maxHeight
        // Симметричный отступ снизу: 19% высоты экрана
        val fmBottomOffset = screenHeight * 0.19f

        // =====================================================================
        // 1. САМЫЙ ВЕРХ: ГОРИЗОНТАЛЬНЫЙ БЛОК "PROMPT" НА ВСЮ ШИРИНУ ЭКРАНА
        // =====================================================================
        PromptConsoleDrawer(
            currentPrompt = state.activePrompt,
            onApplyPrompt = { newPrompt ->
                viewModel.applyPrompt(newPrompt)
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // =====================================================================
        // 2. ЧУТЬ НИЖЕ PROMPT: ГОРИЗОНТАЛЬНАЯ КНОПКА "MATERIALS"
        // =====================================================================
        MaterialsConsoleDrawer(
            onSendMaterials = { text, uris ->
                viewModel.sendText(text, uris)
            },
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
        )

        // =====================================================================
        // 3. ПОД ПАНЕЛЬЮ МАТЕРИАЛОВ СПРАВА: ШЕСТЕРЁНКА НАСТРОЕК И ТЕРМИНАЛ
        // =====================================================================
        SettingsGearButton(
            onOpenSettings = onNavigateSettings,
            onOpenLogs = onNavigateLogs,
            errorCount = errorCount,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 112.dp, end = 20.dp)
        )

        // =====================================================================
        // 4. НИЖНЯЯ ЗОНА: УВЕЛИЧЕННАЯ В 2 РАЗА FM-ПОЛОСКА (168.dp)
        // =====================================================================
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(bottom = fmBottomOffset)
                .padding(horizontal = 24.dp)
        ) {
            FmStripAudioVisualizer(
                nativeEngine = viewModel.nativeAudioEngine,
                state = state
            )
        }

        // =====================================================================
        // 5. НЕМНОЖКО НИЖЕ ВИЗУАЛИЗАТОРА: ГОРИЗОНТАЛЬНАЯ КНОПКА START SESSION
        // (~30% меньше по длине, чем визуализатор: 68% ширины экрана, по центру)
        // =====================================================================
        SessionControlPill(
            linkState = state.link,
            onClick = { handleSessionClick() },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = (fmBottomOffset - 66.dp).coerceAtLeast(16.dp))
                .fillMaxWidth(0.68f)
                .windowInsetsPadding(WindowInsets.navigationBars)
        )

        // =====================================================================
        // 6. СИСТЕМНЫЙ БЕЙДЖ ОШИБКИ (ПОЯВЛЯЕТСЯ ПРИ СБОЕ)
        // =====================================================================
        AnimatedVisibility(
            visible = state.error != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 12.dp, start = 40.dp, end = 40.dp)
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