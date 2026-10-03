package com.client.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.session.LinkState
import com.client.app.session.SessionState
import com.client.app.ui.components.FmStripAudioVisualizer
import com.client.app.ui.components.PromptEditorSheet
import com.client.app.ui.components.PromptPill
import com.client.app.ui.components.SessionControlPill
import com.client.app.ui.components.SettingsGearButton
import com.client.app.viewmodel.ClientViewModel

// Чёрная тема главного экрана. FM-анимация намеренно остаётся белой.
private val ColorCanvas = Color(0xFF000000)
private val ColorGlow = Color(0x17FFFFFF)
private val ColorErrorCard = Color(0xFF141416)
private val ColorErrorBorder = Color(0xFF3B1F1D)
private val ColorErrorLed = Color(0xFFEA4335)
private val ColorTextPrimary = Color(0xFFF4F4F5)
private val ColorTextSecondary = Color(0xFF8E8E96)

/**
 * Главный экран (сверху вниз):
 * - верхняя панель: капсула Prompt с первой строкой роли, справа — логи и настройки;
 * - баннер ошибки (выезжает под панелью и не перекрывает кнопки);
 * - крупный статус сессии по центру;
 * - белая FM-лента голоса;
 * - кнопка Start Session у нижнего края.
 * Редактор промпта открывается поверх экрана и всегда помещается над клавиатурой.
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

    var promptOpen by rememberSaveable { mutableStateOf(false) }

    // Последний текст ошибки нужен, чтобы баннер не опустел во время анимации скрытия
    var lastError by remember { mutableStateOf("") }
    LaunchedEffect(state.error) { state.error?.let { lastError = it } }

    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        val audioGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (audioGranted && viewModel.state.value.link == LinkState.IDLE) {
            viewModel.toggleConnection()
        }
    }

    fun handleSessionClick() {
        // Остановка сессии разрешений не требует
        if (viewModel.state.value.link != LinkState.IDLE) {
            viewModel.toggleConnection()
            return
        }

        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (missing.isEmpty()) {
            viewModel.toggleConnection()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ColorCanvas)
            .drawBehind {
                // Едва заметное свечение за FM-лентой: глубина без лишних деталей
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(ColorGlow, Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.70f),
                        radius = size.width * 0.95f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // =================================================================
            // 1. ВЕРХНЯЯ ПАНЕЛЬ: PROMPT + ЛОГИ + НАСТРОЙКИ
            // =================================================================
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PromptPill(
                    prompt = state.activePrompt,
                    onClick = { promptOpen = true },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(10.dp))
                SettingsGearButton(
                    onOpenSettings = onNavigateSettings,
                    onOpenLogs = onNavigateLogs,
                    errorCount = errorCount
                )
            }

            // =================================================================
            // 2. БАННЕР ОШИБКИ
            // =================================================================
            AnimatedVisibility(
                visible = state.error != null,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(160))
            ) {
                ErrorBanner(
                    message = state.error ?: lastError,
                    onDismiss = { viewModel.clearError() },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
            }

            // =================================================================
            // 3. СТАТУС СЕССИИ
            // =================================================================
            Spacer(Modifier.weight(1f))
            SessionStatusHero(
                state = state,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.weight(1.15f))

            // =================================================================
            // 4. FM-ЛЕНТА ГОЛОСА (БЕЛАЯ, БЕЗ ИЗМЕНЕНИЙ)
            // =================================================================
            FmStripAudioVisualizer(
                nativeEngine = viewModel.nativeAudioEngine,
                state = state,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // =================================================================
            // 5. START SESSION — НИЖЕ, У НИЖНЕГО КРАЯ
            // =================================================================
            Spacer(Modifier.height(28.dp))
            SessionControlPill(
                linkState = state.link,
                onClick = { handleSessionClick() },
                modifier = Modifier.fillMaxWidth(0.68f)
            )
            Spacer(Modifier.height(28.dp))
        }

        // =====================================================================
        // 6. РЕДАКТОР ПРОМПТА ПОВЕРХ ЭКРАНА
        // =====================================================================
        PromptEditorSheet(
            visible = promptOpen,
            currentPrompt = state.activePrompt,
            onApply = { viewModel.applyPrompt(it) },
            onDismiss = { promptOpen = false }
        )
    }
}

@Composable
private fun SessionStatusHero(
    state: SessionState,
    modifier: Modifier = Modifier
) {
    val status = when {
        state.link == LinkState.CONNECTING ->
            "Подключение" to "Открываю защищённый канал с Gemini"
        state.link == LinkState.RECONNECTING ->
            "Восстановление связи" to "Сеть прервалась — переподключаюсь без потери разговора"
        state.link == LinkState.LIVE && state.isAiSpeaking ->
            "Gemini отвечает" to "Перебейте голосом в любой момент"
        state.link == LinkState.LIVE && state.isMicActive ->
            "Слушаю" to "Говорите — ответ начнётся сразу после паузы"
        state.link == LinkState.LIVE ->
            "На связи" to "Включаю микрофон…"
        else ->
            "Готов к разговору" to "Нажмите Start Session, чтобы начать"
    }

    Crossfade(
        targetState = status,
        modifier = modifier,
        animationSpec = tween(durationMillis = 260),
        label = "session_status"
    ) { (title, hint) ->
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = ColorTextPrimary,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-0.4).sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = hint,
                color = ColorTextSecondary,
                fontSize = 13.5.sp,
                lineHeight = 19.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ColorErrorCard)
            .border(1.dp, ColorErrorBorder, shape)
            .clickable(onClick = onDismiss)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(ColorErrorLed)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = message,
            color = ColorTextPrimary,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Скрыть ошибку",
            tint = ColorTextSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}