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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.client.app.R
import com.client.app.api.ClientRole
import com.client.app.session.ChatMessage
import com.client.app.session.ForvoWord
import com.client.app.session.LinkState
import com.client.app.ui.components.AgslVoiceVisualizer
import com.client.app.viewmodel.ClientViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientScreen(
    onNavigateSettings: () -> Unit,
    onNavigateLogs: () -> Unit,
    viewModel: ClientViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val errorCount by viewModel.errorCount.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var isSheetOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        selectedUris.clear()
        selectedUris.addAll(uris)
    }

    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleConnection()
        }
    }

    fun handleConnectClick() {
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

    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.lastIndex)
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
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF18181B))
                ) {
                    Icon(
                        imageVector = Icons.Filled.Tune,
                        contentDescription = stringResource(R.string.settings_title),
                        tint = Color(0xFFFAFAFA),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(8.dp))

                IconButton(
                    onClick = onNavigateLogs,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF18181B))
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Terminal,
                            contentDescription = "Системный лог",
                            tint = if (errorCount > 0) Color(0xFFF87171) else Color(0xFF60A5FA),
                            modifier = Modifier.size(20.dp)
                        )
                        if (errorCount > 0) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 2.dp, y = (-2).dp)
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFFEF4444))
                            )
                        }
                    }
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
                        text = state.activePrompt.ifBlank { stringResource(R.string.default_role_placeholder) },
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
                        .minimumInteractiveComponentSize()
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(if (state.isConnected) Color(0xFF064E3B) else Color(0xFF18181B))
                ) {
                    Icon(
                        imageVector = if (state.isConnected) Icons.Filled.PowerSettingsNew else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.session_status_desc),
                        tint = if (state.isConnected) Color(0xFF34D399) else Color(0xFFFAFAFA),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .windowInsetsPadding(WindowInsets.ime)
                    .background(Color(0xFF09090B))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // Горизонтальный список слов Forvo
                if (state.forvoWords.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.forvoWords.forEach { item ->
                            ForvoWordChip(
                                word = item,
                                onClick = { viewModel.playForvo(item) }
                            )
                        }
                    }
                }

                // Список выбранных файлов перед отправкой
                if (selectedUris.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        selectedUris.forEachIndexed { index, uri ->
                            SuggestionChip(
                                onClick = { selectedUris.removeAt(index) },
                                label = {
                                    Text(
                                        uri.lastPathSegment ?: "файл",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 11.sp
                                    )
                                },
                                trailingIcon = {
                                    Icon(Icons.Filled.Close, null, modifier = Modifier.size(14.dp))
                                }
                            )
                        }
                    }
                }

                // Строка ввода, кнопка вложений и микрофон
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { filePickerLauncher.launch("*/*") },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF18181B))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.AttachFile,
                            contentDescription = stringResource(R.string.attach_file),
                            tint = if (selectedUris.isNotEmpty()) Color(0xFF60A5FA) else Color(0xFFA1A1AA),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(stringResource(R.string.prompt_hint), color = Color(0xFF71717A), fontSize = 13.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp, max = 110.dp),
                        shape = RoundedCornerShape(22.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF60A5FA),
                            unfocusedBorderColor = Color(0xFF27272A),
                            focusedContainerColor = Color(0xFF141416),
                            unfocusedContainerColor = Color(0xFF141416),
                            focusedTextColor = Color(0xFFFAFAFA),
                            unfocusedTextColor = Color(0xFFFAFAFA)
                        ),
                        maxLines = 4
                    )

                    Spacer(Modifier.width(8.dp))

                    if (inputText.isNotBlank() || selectedUris.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val textToSend = inputText.trim()
                                val urisToSend = selectedUris.toList()
                                inputText = ""
                                selectedUris.clear()
                                viewModel.sendText(textToSend, urisToSend)
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF2563EB))
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = stringResource(R.string.send_message),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else {
                        IconButton(
                            onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    if (state.isConnected) {
                                        viewModel.toggleMic()
                                    } else {
                                        viewModel.toggleConnection()
                                    }
                                } else {
                                    handleConnectClick()
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (state.isMicActive) Color(0xFF2563EB) else Color(0xFF18181B))
                        ) {
                            Icon(
                                imageVector = if (state.isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                                contentDescription = stringResource(R.string.mic_content_desc),
                                tint = if (state.isMicActive) Color.White else Color(0xFFA1A1AA),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF09090B)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Верхний визуализатор (компактный при наличии сообщений, полный при старте)
            val visualizerSize = if (state.messages.isEmpty()) 240.dp else 140.dp

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AgslVoiceVisualizer(
                        nativeEngine = viewModel.nativeAudioEngine,
                        state = state,
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                if (state.isConnected) {
                                    viewModel.toggleMic()
                                } else {
                                    viewModel.toggleConnection()
                                }
                            } else {
                                handleConnectClick()
                            }
                        },
                        size = visualizerSize
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = when {
                            state.error != null -> state.error.orEmpty()
                            state.isAiSpeaking -> "Ассистент говорит..."
                            state.link == LinkState.CONNECTING -> "Подключение... (нажмите для отмены)"
                            state.link == LinkState.RECONNECTING -> "Восстановление связи... (нажмите для отмены)"
                            state.isMicActive -> "Слушаю вас..."
                            state.isConnected -> "Микрофон на паузе (нажмите на сферу)"
                            else -> "Нажмите на сферу для запуска"
                        },
                        color = when {
                            state.error != null -> Color(0xFFF87171)
                            state.isAiSpeaking -> Color(0xFF34D399)
                            state.isMicActive -> Color(0xFF60A5FA)
                            else -> Color(0xFF71717A)
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Лента сообщений
            if (state.messages.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(msg)
                    }
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }

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
                Text(
                    text = stringResource(R.string.system_role_instruction),
                    color = Color(0xFFFAFAFA),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
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
                    Text(stringResource(R.string.apply_role), color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatMessage) {
    val isUser = msg.role == ClientRole.USER
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd = if (isUser) 4.dp else 16.dp
                ))
                .background(if (isUser) Color(0xFF1E3A8A) else Color(0xFF18181B))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                if (msg.attachmentNames.isNotEmpty()) {
                    Text(
                        text = "📎 " + msg.attachmentNames.joinToString(", "),
                        color = Color(0xFF93C5FD),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                }

                Text(
                    text = msg.text,
                    color = if (msg.interim) Color(0xFFA1A1AA) else Color(0xFFFAFAFA),
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun ForvoWordChip(word: ForvoWord, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF18181B),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, Color(0xFF27272A))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    word.isLoading -> Icons.Filled.HourglassEmpty
                    word.notFound -> Icons.Filled.VolumeOff
                    else -> Icons.Filled.VolumeUp
                },
                contentDescription = null,
                tint = if (word.audioUrl != null) Color(0xFF34D399) else Color(0xFFA1A1AA),
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = word.word,
                color = Color(0xFFFAFAFA),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            if (!word.translation.isNullOrBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "— ${word.translation}",
                    color = Color(0xFF71717A),
                    fontSize = 11.sp
                )
            }
        }
    }
}