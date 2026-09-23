// >>> FILE: app/src/main/java/com/client/app/ui/screens/ClientScreen.kt
package com.client.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
                // Кнопка настроек
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

                // Кнопка перехода в системный логгер с индикатором ошибок
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

                // Селектор системной роли ассистента
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

                // Кнопка подключения / завершения сессии
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF09090B)),
            contentAlignment = Alignment.Center
        ) {
            // Центральный полноэкранный 120 FPS AGSL-визуализатор
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                AgslVoiceVisualizer(
                    nativeEngine = viewModel.nativeAudioEngine,
                    state = state,
                    onClick = {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            if (state.isConnected) {
                                // Если сессия активна — переключаем микрофон
                                viewModel.toggleMic()
                            } else {
                                // Если сессия отключена или находится в процессе подключения — управляем подключением (включая отмену)
                                viewModel.toggleConnection()
                            }
                        } else {
                            handleConnectClick()
                        }
                    },
                    size = 280.dp
                )

                Spacer(Modifier.height(24.dp))

                // Текстовая подсказка статуса
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
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
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