// >>> FILE: app/src/main/java/com/client/app/ui/screens/ClientScreen.kt
package com.client.app.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.session.ChatMessage
import com.client.app.session.ForvoWord
import com.client.app.ui.components.NeoVoiceVisualizer
import com.client.app.viewmodel.ClientViewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    onNavigateSettings: () -> Unit,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val amplitude by viewModel.amplitude.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var chatInput by remember { mutableStateOf("") }
    var attachedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isSheetOpen by remember { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> attachedUris = (attachedUris + uris).distinct().take(8) }

    // Деликатный запрос только обязательных разрешений
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true) {
            viewModel.toggleConnection()
        }
    }

    fun handleConnectClick() {
        val missing = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (missing.isEmpty()) {
            viewModel.toggleConnection()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    fun hasRecordPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    Scaffold(
        containerColor = Color(0xFF09090B),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onNavigateSettings,
                    modifier = Modifier.size(42.dp).clip(CircleShape).background(Color(0xFF18181B))
                ) {
                    Icon(Icons.Filled.Tune, "Настройки", tint = Color(0xFFFAFAFA), modifier = Modifier.size(20.dp))
                }

                Spacer(Modifier.width(8.dp))

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF18181B))
                        .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(24.dp))
                        .clickable { isSheetOpen = true }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.activePrompt.ifBlank { "Задать системную роль..." },
                        color = Color(0xFFE4E4E7),
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(18.dp))
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = { handleConnectClick() },
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (state.isConnected) Color(0xFF064E3B) else Color(0xFF18181B))
                ) {
                    Icon(
                        imageVector = if (state.isConnected) Icons.Filled.PowerSettingsNew else Icons.Filled.PlayArrow,
                        contentDescription = "Статус сессии",
                        tint = if (state.isConnected) Color(0xFF34D399) else Color(0xFFFAFAFA),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            // Плашка ошибки
            AnimatedVisibility(visible = state.error != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF450A0A))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(state.error.orEmpty(), color = Color(0xFFFCA5A5), fontSize = 13.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Filled.Close, null, tint = Color(0xFFFCA5A5), modifier = Modifier.size(14.dp))
                    }
                }
            }

            // Индикатор разбора материала через Vision
            AnimatedVisibility(visible = state.isAnalyzing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF172554))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = Color(0xFF60A5FA))
                    Spacer(Modifier.width(10.dp))
                    Text("Распознаём текст и лексику учебника...", color = Color(0xFF93C5FD), fontSize = 12.sp)
                }
            }

            // Панель произношений Forvo с атрибуцией и защитой ключей
            AnimatedVisibility(visible = state.forvoWords.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Произношение", color = Color(0xFFA1A1AA), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "от Forvo.com",
                            color = Color(0xFF60A5FA),
                            fontSize = 11.sp,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://forvo.com")))
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${state.forvoUsed}/${state.forvoLimit})",
                            color = Color(0xFF71717A),
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearForvo() }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Filled.Close, null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(
                            items = state.forvoWords,
                            key = { "${it.query.lowercase().trim()}_${it.language}" }
                        ) { item ->
                            ForvoWordChip(item = item, onClick = { viewModel.playForvo(item) })
                        }
                    }
                }
            }

            // Центральная зона: Визуализатор или Чат
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth()
            ) {
                if (state.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        NeoVoiceVisualizer(
                            amplitude = amplitude,
                            isConnected = state.isConnected,
                            isConnecting = state.isConnecting,
                            isAiSpeaking = state.isAiSpeaking,
                            isMicActive = state.isMicActive,
                            hasError = state.error != null && !state.isConnected,
                            onClick = {
                                if (hasRecordPermission()) {
                                    if (!state.isConnected && !state.isConnecting) {
                                        viewModel.toggleConnection()
                                    } else {
                                        viewModel.toggleMic()
                                    }
                                } else {
                                    handleConnectClick()
                                }
                            }
                        )
                    }
                } else {
                    val listState = rememberLazyListState()

                    val isAtBottom by remember {
                        derivedStateOf {
                            val layoutInfo = listState.layoutInfo
                            val visibleItems = layoutInfo.visibleItemsInfo
                            if (visibleItems.isEmpty()) true
                            else {
                                val lastVisible = visibleItems.last()
                                lastVisible.index >= layoutInfo.totalItemsCount - 2
                            }
                        }
                    }

                    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
                        if (state.messages.isNotEmpty() && isAtBottom) {
                            listState.animateScrollToItem(state.messages.lastIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(
                            items = state.messages,
                            key = { it.id }
                        ) { msg ->
                            val isLast = msg.id == state.messages.lastOrNull()?.id
                            ChatBubble(
                                msg = msg,
                                isStreaming = isLast && state.isAiSpeaking
                            )
                        }
                    }
                }
            }

            // Нижняя панель ввода и вложений
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF09090B))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (attachedUris.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(bottom = 6.dp)
                    ) {
                        items(attachedUris) { uri ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF18181B))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.AttachFile, null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(12.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(getUriDisplayName(context, uri), fontSize = 11.sp, color = Color(0xFFFAFAFA))
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Filled.Close, null, tint = Color(0xFFA1A1AA), modifier = Modifier.size(13.dp).clickable {
                                    attachedUris = attachedUris - uri
                                })
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF18181B))
                        .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(26.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Filled.Add, "Прикрепить документ или фото", tint = Color(0xFFA1A1AA), modifier = Modifier.size(20.dp))
                    }

                    BasicTextField(
                        value = chatInput,
                        onValueChange = { chatInput = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 8.dp),
                        textStyle = LocalTextStyle.current.copy(color = Color(0xFFFAFAFA), fontSize = 15.sp),
                        cursorBrush = SolidColor(Color(0xFF60A5FA)),
                        decorationBox = { inner ->
                            if (chatInput.isEmpty()) Text("Спросить или дать команду...", color = Color(0xFF71717A), fontSize = 15.sp)
                            inner()
                        }
                    )

                    if (chatInput.isNotBlank() || attachedUris.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFAFAFA))
                                .clickable {
                                    viewModel.sendText(chatInput, attachedUris)
                                    chatInput = ""
                                    attachedUris = emptyList()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.ArrowUpward, "Отправить", tint = Color(0xFF09090B), modifier = Modifier.size(18.dp))
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (hasRecordPermission()) {
                                    viewModel.toggleMic()
                                } else {
                                    handleConnectClick()
                                }
                            },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (state.isMicActive) Color(0xFF1E3A8A) else Color.Transparent)
                        ) {
                            Icon(
                                imageVector = if (state.isMicActive) Icons.Filled.Mic else Icons.Outlined.Mic,
                                contentDescription = "Микрофон",
                                tint = if (state.isMicActive) Color(0xFF60A5FA) else Color(0xFFA1A1AA),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // Шторка системного промпта
        if (isSheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { isSheetOpen = false },
                containerColor = Color(0xFF18181B),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF3F3F46)) }
            ) {
                var tempPrompt by remember { mutableStateOf(state.activePrompt) }
                LaunchedEffect(isSheetOpen) {
                    tempPrompt = state.activePrompt
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Системный промпт сессии", color = Color(0xFFFAFAFA), fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { tempPrompt = "" }) {
                            Text("Очистить", color = Color(0xFFEF4444), fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = tempPrompt,
                        onValueChange = { tempPrompt = it },
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                        placeholder = { Text("Опишите роль ассистента или тему урока...", color = Color(0xFF71717A), fontSize = 13.sp) },
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF60A5FA),
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedContainerColor = Color(0xFF09090B),
                            unfocusedContainerColor = Color(0xFF09090B),
                            focusedTextColor = Color(0xFFFAFAFA),
                            unfocusedTextColor = Color(0xFFFAFAFA)
                        )
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.applyPrompt(tempPrompt)
                            isSheetOpen = false
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFAFAFA), contentColor = Color(0xFF09090B))
                    ) {
                        Text("Применить к модели", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

private fun getUriDisplayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty().takeLast(14)
}

@Composable
private fun ForvoWordChip(
    item: ForvoWord,
    onClick: () -> Unit
) {
    val isAvailable = item.audioUrl != null && !item.isLoading
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (item.notFound) Color(0xFF141416) else Color(0xFF18181B))
            .border(
                0.5.dp,
                if (item.notFound) Color(0xFF27272A) else Color(0xFF3F3F46),
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = isAvailable, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        if (item.isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Color(0xFF60A5FA))
            Spacer(Modifier.width(6.dp))
        } else if (isAvailable) {
            Icon(Icons.Filled.VolumeUp, null, tint = Color(0xFF60A5FA), modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }

        Text(
            text = item.word,
            color = if (item.notFound) Color(0xFF71717A) else Color(0xFFF4F4F5),
            fontSize = 13.sp
        )

        if (item.translation != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "· ${item.translation}",
                color = Color(0xFFA1A1AA),
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun ChatBubble(
    msg: ChatMessage,
    isStreaming: Boolean
) {
    if (msg.text.isBlank()) return

    val isUser = msg.role == "user"
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 0.95f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp, topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 2.dp,
                        bottomEnd = if (isUser) 2.dp else 16.dp
                    )
                )
                .background(
                    when {
                        msg.interim -> Color(0xFF18181B).copy(alpha = 0.65f)
                        isUser -> Color(0xFF27272A)
                        else -> Color(0xFF141416)
                    }
                )
                .border(
                    0.5.dp,
                    if (isUser) Color(0xFF3F3F46) else Color(0xFF27272A),
                    RoundedCornerShape(16.dp)
                )
                .clickable { clipboard.setText(AnnotatedString(msg.text)) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (msg.attachmentNames.isNotEmpty()) {
                    Text(
                        text = "📎 ${msg.attachmentNames.joinToString()}",
                        color = Color(0xFF60A5FA),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }

                if (isUser) {
                    Text(
                        text = msg.text,
                        color = if (msg.interim) Color(0xFFA1A1AA) else Color(0xFFFAFAFA),
                        fontStyle = if (msg.interim) FontStyle.Italic else FontStyle.Normal,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                } else if (isStreaming) {
                    // Высокопроизводительный нативный текст во время стриминга (120 FPS)
                    Text(
                        text = msg.text,
                        color = Color(0xFFFAFAFA),
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                } else {
                    // Форматированный Markdown рендеринг для завершённого ответа
                    Markdown(
                        content = msg.text,
                        colors = markdownColor(
                            text = Color(0xFFFAFAFA),
                            codeBackground = Color(0xFF18181B)
                        ),
                        typography = markdownTypography(
                            text = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFFAFAFA))
                        )
                    )
                }
            }
        }
    }
}