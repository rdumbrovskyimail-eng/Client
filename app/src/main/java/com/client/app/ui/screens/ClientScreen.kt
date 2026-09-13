// >>> FILE: app/src/main/java/com/client/app/ui/screens/ClientScreen.kt
package com.client.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.session.ChatMessage
import com.client.app.ui.components.AgslVoiceVisualizer
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
    val context = LocalContext.current

    var chatInput by remember { mutableStateOf("") }
    var attachedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var isSheetOpen by remember { mutableStateOf(false) }

    // E-31: Ограничение пикера поддерживаемыми типами
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> attachedUris = (attachedUris + uris).distinct().take(8) }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        if (missing.isEmpty()) {
            viewModel.toggleConnection()
        } else {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = Color(0xFF09090B),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
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
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (state.messages.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AgslVoiceVisualizer(
                            nativeEngine = viewModel.nativeAudioEngine,
                            state = state,
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    if (!state.isConnected && !state.isConnecting) {
                                        viewModel.toggleConnection()
                                    } else {
                                        viewModel.toggleMic()
                                    }
                                } else {
                                    handleConnectClick()
                                }
                            },
                            size = 230.dp
                        )
                    }
                } else {
                    val listState = rememberLazyListState()

                    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
                        if (state.messages.isNotEmpty()) {
                            listState.animateScrollToItem(state.messages.lastIndex)
                        }
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        items(items = state.messages, key = { it.id }) { msg ->
                            val isLast = msg.id == state.messages.lastOrNull()?.id
                            ChatBubble(msg = msg, isStreaming = isLast && state.isAiSpeaking)
                        }
                    }
                }
            }

            // E-24: Исключение двойного отступа
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF09090B))
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(Color(0xFF18181B))
                        .border(0.5.dp, Color(0xFF27272A), RoundedCornerShape(26.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { filePicker.launch(arrayOf("application/pdf", "image/*")) },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(Icons.Filled.Add, "Прикрепить", tint = Color(0xFFA1A1AA), modifier = Modifier.size(20.dp))
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
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
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
    }

    // E-21: Полноценный рабочий ModalBottomSheet
    if (isSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isSheetOpen = false },
            containerColor = Color(0xFF141416),
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF3F3F46)) }
        ) {
            var tempPrompt by remember { mutableStateOf(state.activePrompt) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 32.dp)
                    .windowInsetsPadding(WindowInsets.ime)
            ) {
                Text("Системная инструкция роли", color = Color(0xFFFAFAFA), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = tempPrompt,
                    onValueChange = { tempPrompt = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 240.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF60A5FA),
                        unfocusedBorderColor = Color(0xFF27272A),
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("Применить роль", color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, isStreaming: Boolean) {
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
                .clip(RoundedCornerShape(16.dp))
                .background(if (isUser) Color(0xFF27272A) else Color(0xFF141416))
                .border(0.5.dp, if (isUser) Color(0xFF3F3F46) else Color(0xFF27272A), RoundedCornerShape(16.dp))
                .clickable { clipboard.setText(AnnotatedString(msg.text)) }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (isUser || isStreaming) {
                    Text(text = msg.text, color = Color(0xFFFAFAFA), fontSize = 14.sp, lineHeight = 20.sp)
                } else {
                    Markdown(
                        content = msg.text,
                        colors = markdownColor(text = Color(0xFFFAFAFA), codeBackground = Color(0xFF18181B)),
                        typography = markdownTypography(text = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, color = Color(0xFFFAFAFA)))
                    )
                }
            }
        }
    }
}