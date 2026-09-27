package com.client.app.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.logging.AppLogManager
import com.client.app.logging.LogEntry
import com.client.app.logging.LogLevel
import kotlinx.coroutines.launch

// Цветовые константы белого минимализма для журнала логов
private val ColorCanvasWhite = Color(0xFFFFFFFF)
private val ColorCardBackground = Color(0xFFFAFAFA)
private val ColorFieldBackground = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFE2E8F0)
private val ColorTextPrimary = Color(0xFF09090B)
private val ColorTextSecondary = Color(0xFF71717A)
private val ColorTextMuted = Color(0xFFA1A1AA)
private val ColorPayloadBackground = Color(0xFFF1F5F9)
private val ColorPayloadText = Color(0xFF0F172A)

private val ColorButtonBlack = Color(0xFF09090B)
private val ColorButtonTextWhite = Color(0xFFFFFFFF)

private enum class LogFilter(val label: String) {
    ALL("Все"),
    ERRORS("Ошибки"),
    NETWORK("Сеть"),
    AUDIO("Аудио"),
    VAD("VAD"),
    WARN("Предупреждения")
}

/**
 * Тактильный импульс для действий в журнале логов.
 */
private fun performLogActionHaptic(context: Context, intensity: Float = 0.65f) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, intensity, 0)
                        .compose()
                )
            }
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (vibrator?.hasVibrator() == true) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                } else {
                    vibrator.vibrate(12L)
                }
            }
        }
    }
}

/**
 * Экран системного журнала логов, выполненный в эстетике белого минимализма.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    logManager: AppLogManager
) {
    val logs by logManager.logsFlow.collectAsStateWithLifecycle()
    val errorCount by logManager.errorCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(LogFilter.ALL) }
    var autoScroll by remember { mutableStateOf(true) }

    // Фильтрация записей журнала по поисковому запросу и категории
    val filteredLogs = remember(logs, searchQuery, selectedFilter) {
        logs.filter { entry ->
            val matchesFilter = when (selectedFilter) {
                LogFilter.ALL -> true
                LogFilter.ERRORS -> entry.level == LogLevel.ERROR
                LogFilter.NETWORK -> entry.level == LogLevel.NETWORK
                LogFilter.AUDIO -> entry.level == LogLevel.AUDIO
                LogFilter.VAD -> entry.level == LogLevel.VAD
                LogFilter.WARN -> entry.level == LogLevel.WARN || entry.level == LogLevel.ERROR
            }
            val matchesSearch = if (searchQuery.isBlank()) true else {
                entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.tag.contains(searchQuery, ignoreCase = true) ||
                    (entry.payload?.contains(searchQuery, ignoreCase = true) == true)
            }
            matchesFilter && matchesSearch
        }
    }

    val listState = rememberLazyListState()

    // Автоматический скролл вниз к свежим записям
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    // Отключение автоскролла, если пользователь вручную листает лог вверх
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == filteredLogs.lastIndex
            if (!isAtBottom) autoScroll = false
        }
    }

    Scaffold(
        containerColor = ColorCanvasWhite,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "СИСТЕМНЫЙ ЖУРНАЛ",
                                color = ColorTextPrimary,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            if (errorCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFFFEE2E2))
                                        .border(1.dp, Color(0xFFEF4444).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "$errorCount ERR",
                                        color = Color(0xFFDC2626),
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${filteredLogs.size} из ${logs.size} записей",
                            color = ColorTextSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
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
                actions = {
                    // Переключатель режима автоскролла
                    IconButton(onClick = {
                        performLogActionHaptic(context, 0.5f)
                        autoScroll = !autoScroll
                    }) {
                        Icon(
                            imageVector = if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.PauseCircle,
                            contentDescription = "Автоскролл",
                            tint = if (autoScroll) Color(0xFF10B981) else ColorTextMuted
                        )
                    }

                    // Экспорт логов в файл
                    IconButton(onClick = {
                        performLogActionHaptic(context)
                        coroutineScope.launch {
                            val uri = logManager.exportLogsToFile()
                            if (uri != null) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Экспорт журнала логов"))
                            } else {
                                Toast.makeText(context, "Журнал логов пуст", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Поделиться",
                            tint = ColorTextPrimary
                        )
                    }

                    // Очистка журнала
                    IconButton(onClick = {
                        performLogActionHaptic(context, 0.9f)
                        logManager.clear()
                    }) {
                        Icon(
                            imageVector = Icons.Filled.DeleteSweep,
                            contentDescription = "Очистить",
                            tint = Color(0xFFDC2626)
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
        ) {
            // Панель поиска и фильтрации
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ColorCanvasWhite)
                    .border(
                        width = 1.dp,
                        color = ColorHairline,
                        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // Поле ввода поискового запроса
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(ColorFieldBackground)
                        .border(1.dp, ColorHairline, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = ColorTextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 12.5.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            cursorBrush = SolidColor(ColorTextPrimary),
                            decorationBox = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = "Поиск по тегу, тексту сообщения или JSON...",
                                        color = ColorTextMuted,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.SansSerif
                                    )
                                }
                                innerTextField()
                            }
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Очистить поиск",
                                tint = ColorTextSecondary,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable {
                                        performLogActionHaptic(context, 0.4f)
                                        searchQuery = ""
                                    }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // Горизонтальная карусель категориальных фильтров
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LogFilter.entries.forEach { filter ->
                        val isSelected = selectedFilter == filter
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .shadow(
                                    elevation = if (isSelected) 2.dp else 0.dp,
                                    shape = RoundedCornerShape(16.dp),
                                    ambientColor = Color(0x0A000000),
                                    spotColor = Color(0x14000000)
                                )
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isSelected) ColorButtonBlack else ColorFieldBackground)
                                .border(1.dp, if (isSelected) ColorButtonBlack else ColorHairline, RoundedCornerShape(16.dp))
                                .clickable {
                                    performLogActionHaptic(context, 0.4f)
                                    selectedFilter = filter
                                }
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = filter.label,
                                color = if (isSelected) ColorButtonTextWhite else ColorTextSecondary,
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // Список записей системного журнала
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 10.dp),
                        contentPadding = PaddingValues(vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(items = filteredLogs, key = { it.id }) { entry ->
                            WhiteLogItemRow(
                                entry = entry,
                                onCopy = {
                                    performLogActionHaptic(context)
                                    clipboard.setText(
                                        AnnotatedString("[${entry.timeFormatted}] [${entry.tag}] ${entry.message}")
                                    )
                                    Toast.makeText(context, "Запись скопирована", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }

                // Плавающая кнопка возврата вниз к свежим логам
                if (!autoScroll) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(44.dp)
                            .shadow(
                                elevation = 6.dp,
                                shape = CircleShape,
                                ambientColor = Color(0x14000000),
                                spotColor = Color(0x28000000)
                            )
                            .clip(CircleShape)
                            .background(ColorButtonBlack)
                            .clickable {
                                performLogActionHaptic(context)
                                autoScroll = true
                                coroutineScope.launch {
                                    if (filteredLogs.isNotEmpty()) {
                                        listState.animateScrollToItem(filteredLogs.lastIndex)
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Прокрутить вниз",
                            tint = ColorButtonTextWhite,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Строка отдельной записи лога, оформленная как карточка швейцарского технического формуляра.
 */
@Composable
private fun WhiteLogItemRow(
    entry: LogEntry,
    onCopy: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val (badgeBg, badgeText) = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFFEE2E2) to Color(0xFFDC2626)
        LogLevel.WARN -> Color(0xFFFEF3C7) to Color(0xFFD97706)
        LogLevel.NETWORK -> Color(0xFFDBEAFE) to Color(0xFF2563EB)
        LogLevel.AUDIO -> Color(0xFFD1FAE5) to Color(0xFF059669)
        LogLevel.VAD -> Color(0xFFEDE9FE) to Color(0xFF7C3AED)
        LogLevel.INFO -> Color(0xFFE0F2FE) to Color(0xFF0284C7)
        else -> Color(0xFFF1F5F9) to Color(0xFF475569)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(8.dp), ambientColor = Color(0x06000000), spotColor = Color(0x0A000000))
            .clip(RoundedCornerShape(8.dp))
            .background(ColorCardBackground)
            .border(1.dp, ColorHairline, RoundedCornerShape(8.dp))
            .clickable {
                if (entry.payload != null) {
                    isExpanded = !isExpanded
                } else {
                    onCopy()
                }
            }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        // Метаданные: время, бейдж уровня, тег компонента и имя потока
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.timeFormatted,
                color = ColorTextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium
            )

            Spacer(Modifier.width(8.dp))

            // Пастельный бейдж уровня логирования
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeBg)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = entry.level.tag,
                    color = badgeText,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.width(8.dp))

            Text(
                text = entry.tag,
                color = ColorTextPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.weight(1f))

            Text(
                text = entry.threadName,
                color = ColorTextMuted,
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(4.dp))

        // Основной текст сообщения
        Text(
            text = entry.message,
            color = if (entry.level == LogLevel.ERROR) Color(0xFFDC2626) else ColorTextPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )

        // Индикатор и блок инспекции полезной нагрузки (JSON Payload)
        if (entry.payload != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (isExpanded) "▼ Свернуть Payload" else "▶ Развернуть Payload (${entry.payload.length} байт)",
                color = Color(0xFF2563EB),
                fontSize = 10.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )

            if (isExpanded) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(ColorPayloadBackground)
                        .border(1.dp, ColorHairline, RoundedCornerShape(6.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = entry.payload,
                        color = ColorPayloadText,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }
}