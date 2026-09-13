package com.client.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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

private enum class LogFilter(val label: String) {
    ALL("Все"),
    ERRORS("Ошибки"),
    NETWORK("Сеть"),
    AUDIO("Аудио"),
    VAD("VAD"),
    WARN("Предупреждения")
}

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

    // Автоматический скролл вниз только если включён флаг и пользователь не прокручивает лог вручную
    LaunchedEffect(filteredLogs.size, autoScroll) {
        if (autoScroll && filteredLogs.isNotEmpty()) {
            listState.animateScrollToItem(filteredLogs.lastIndex)
        }
    }

    // Если пользователь потянул список вверх, временно отключаем автоскролл
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            val isAtBottom = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == filteredLogs.lastIndex
            if (!isAtBottom) autoScroll = false
        }
    }

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Системный лог", color = Color(0xFFFAFAFA), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            if (errorCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0xFFEF4444))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text("$errorCount ERR", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text("${filteredLogs.size} из ${logs.size} записей", color = Color(0xFFA1A1AA), fontSize = 11.sp)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад", tint = Color(0xFFFAFAFA))
                    }
                },
                actions = {
                    // Переключатель автоскролла
                    IconButton(onClick = { autoScroll = !autoScroll }) {
                        Icon(
                            imageVector = if (autoScroll) Icons.Filled.VerticalAlignBottom else Icons.Filled.PauseCircle,
                            contentDescription = "Автоскролл",
                            tint = if (autoScroll) Color(0xFF34D399) else Color(0xFFA1A1AA)
                        )
                    }
                    // Экспорт в текстовый файл
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val uri = logManager.exportLogsToFile()
                            if (uri != null) {
                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(sendIntent, "Поделиться логами"))
                            } else {
                                Toast.makeText(context, "Логи пусты", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Поделиться", tint = Color(0xFFFAFAFA))
                    }
                    // Очистка логов
                    IconButton(onClick = { logManager.clear() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Очистить", tint = Color(0xFFEF4444))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF141416))
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Поле поиска и фильтры
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141416))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Поиск по тексту, тегу или JSON...", color = Color(0xFF71717A), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "Сброс", tint = Color(0xFFA1A1AA), modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color(0xFF27272A),
                        focusedContainerColor = Color(0xFF09090B),
                        unfocusedContainerColor = Color(0xFF09090B),
                        focusedTextColor = Color(0xFFFAFAFA),
                        unfocusedTextColor = Color(0xFFFAFAFA)
                    )
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LogFilter.values().forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter.label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF2563EB),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF18181B),
                                labelColor = Color(0xFFA1A1AA)
                            )
                        )
                    }
                }
            }

            // Список логов
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                        contentPadding = PaddingValues(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(items = filteredLogs, key = { it.id }) { entry ->
                            LogItemRow(entry = entry, onCopy = {
                                clipboard.setText(AnnotatedString("[${entry.timeFormatted}] [${entry.tag}] ${entry.message}"))
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            })
                        }
                    }
                }

                // Плавающая кнопка возврата вниз к свежим логам
                if (!autoScroll) {
                    SmallFloatingActionButton(
                        onClick = {
                            autoScroll = true
                            coroutineScope.launch {
                                if (filteredLogs.isNotEmpty()) listState.animateScrollToItem(filteredLogs.lastIndex)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = Color(0xFF2563EB),
                        contentColor = Color.White
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Вниз")
                    }
                }
            }
        }
    }
}

@Composable
private fun LogItemRow(entry: LogEntry, onCopy: () -> Unit) {
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFEF4444)
        LogLevel.WARN -> Color(0xFFF59E0B)
        LogLevel.NETWORK -> Color(0xFF60A5FA)
        LogLevel.AUDIO -> Color(0xFF34D399)
        LogLevel.VAD -> Color(0xFFA78BFA)
        LogLevel.INFO -> Color(0xFF38BDF8)
        else -> Color(0xFFA1A1AA)
    }

    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF141416))
            .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(6.dp))
            .clickable {
                if (entry.payload != null) expanded = !expanded else onCopy()
            }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.timeFormatted,
                color = Color(0xFF71717A),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(levelColor.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = entry.level.tag,
                    color = levelColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.tag,
                color = Color(0xFFE4E4E7),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = entry.threadName,
                color = Color(0xFF52525B),
                fontSize = 10.sp,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(3.dp))

        Text(
            text = entry.message,
            color = if (entry.level == LogLevel.ERROR) Color(0xFFFCA5A5) else Color(0xFFFAFAFA),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            lineHeight = 16.sp
        )

        if (entry.payload != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (expanded) "▼ Скрыть JSON / Payload" else "▶ Показать Payload (${entry.payload.length} симв.)",
                color = Color(0xFF60A5FA),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF09090B))
                        .padding(6.dp)
                ) {
                    Text(
                        text = entry.payload,
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}