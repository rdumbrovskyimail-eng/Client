package com.client.app.ui.components

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Цветовые константы белого минимализма
private val ColorPillBackground = Color(0xFFFFFFFF)
private val ColorDrawerBackground = Color(0xFFFAFAFA)
private val ColorCardBackground = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFEBEBEB)
private val ColorTextPrimary = Color(0xFF09090B)
private val ColorTextSecondary = Color(0xFF71717A)
private val ColorActionCyan = Color(0xFF4285F4) // синий Gemini
private val ColorButtonBlack = Color(0xFF09090B)
private val ColorButtonTextWhite = Color(0xFFFAFAFA)
private val ColorScrim = Color(0x28000000)

/**
 * Тактильный импульс щелчка X-Axis LRA для физического отклика кнопок и плашки.
 */
private fun performTactileClick(context: Context, intensity: Float = 0.85f) {
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
                    vibrator.vibrate(18L)
                }
            }
        }
    }
}

/**
 * Извлекает чистое имя файла по Content Uri без блокировки UI.
 */
private fun queryFileName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "материал"
}

/**
 * Левая нижняя плашка «Materials» и выезжающая снизу вверх консоль прикрепления файлов с текстом.
 */
@Composable
fun MaterialsConsoleDrawer(
    onSendMaterials: (text: String, uris: List<Uri>) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var isDrawerOpen by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    val selectedUris = remember { mutableStateListOf<Uri>() }

    // Контроль лазурно-голубого состояния текста плашки
    var isColorLatchedToCyan by remember { mutableStateOf(false) }

    val animatedTextColor by animateColorAsState(
        targetValue = if (isColorLatchedToCyan) ColorActionCyan else ColorTextPrimary,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "materials_pill_color"
    )

    // Лаунчер системного файлового селектора
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            performTactileClick(context, intensity = 0.65f)
            selectedUris.addAll(uris)
        }
    }

    // Перехват жеста "Назад" при открытой консоли
    BackHandler(enabled = isDrawerOpen) {
        performTactileClick(context)
        isDrawerOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {

        // =====================================================================
        // 1. ВЕРТИКАЛЬНАЯ ПЛАШКА-ЯЗЫЧОК "M-a-t-e-r-i-a-l-s" (Слева снизу)
        // =====================================================================
        if (!isDrawerOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(bottom = 54.dp)
                    .width(44.dp)
                    .height(200.dp)
                    .shadow(
                        elevation = 6.dp,
                        shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp),
                        ambientColor = Color(0x0F000000),
                        spotColor = Color(0x1A000000)
                    )
                    .clip(RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp))
                    .background(ColorPillBackground)
                    .border(
                        width = 1.dp,
                        color = if (isColorLatchedToCyan) ColorActionCyan.copy(alpha = 0.5f) else ColorHairline,
                        shape = RoundedCornerShape(topStart = 0.dp, bottomStart = 0.dp, topEnd = 24.dp, bottomEnd = 24.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        performTactileClick(context)
                        isDrawerOpen = true
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceEvenly
                ) {
                    val letters = listOf("M", "a", "t", "e", "r", "i", "a", "l", "s")
                    letters.forEach { char ->
                        Text(
                            text = char,
                            color = animatedTextColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 2. ВЫЕЗЖАЮЩАЯ СНИЗУ ВВЕРХ БЕЛАЯ КОНСОЛЬ-ШУХЛЯДКА (Vertical Drawer)
        // =====================================================================
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 320, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
            ) + fadeIn(animationSpec = tween(220)),
            // Быстрое схлопывание вниз за 180 мс при нажатии OK
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 180, easing = FastOutLinearInEasing)
            ) + fadeOut(animationSpec = tween(150))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(ColorScrim)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        performTactileClick(context)
                        isDrawerOpen = false
                    }
            ) {
                // Основной белый прямоугольный блок консоли
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .shadow(
                            elevation = 20.dp,
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                            ambientColor = Color(0x14000000),
                            spotColor = Color(0x28000000)
                        )
                        .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
                        .background(ColorDrawerBackground)
                        .border(
                            width = 1.dp,
                            color = ColorHairline,
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 0.dp, bottomEnd = 0.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Защита от кликов сквозь консоль */ }
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .windowInsetsPadding(WindowInsets.ime)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {

                        // Шапка консоли: заголовок и кнопка закрытия
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "МАТЕРИАЛЫ И КОНТЕКСТ",
                                    color = ColorTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Отправка документов, изображений и текста в Gemini Live",
                                    color = ColorTextSecondary.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }

                            // Кнопка закрытия
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(ColorCardBackground)
                                    .border(1.dp, ColorHairline, CircleShape)
                                    .clickable {
                                        performTactileClick(context)
                                        isDrawerOpen = false
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Закрыть",
                                    tint = ColorTextPrimary,
                                    modifier = Modifier.size(17.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Поле ввода текстового комментария (текст может быть пустым)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 84.dp, max = 130.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ColorCardBackground)
                                .border(1.dp, ColorHairline, RoundedCornerShape(14.dp))
                                .padding(12.dp)
                        ) {
                            BasicTextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    color = ColorTextPrimary,
                                    fontSize = 13.5.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    lineHeight = 18.sp
                                ),
                                cursorBrush = SolidColor(ColorTextPrimary),
                                decorationBox = { innerTextField ->
                                    if (inputText.isEmpty()) {
                                        Text(
                                            text = "Текстовое сообщение или инструкция к файлам (необязательно)...",
                                            color = ColorTextSecondary.copy(alpha = 0.6f),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        // Горизонтальная карусель прикрепленных материалов
                        if (selectedUris.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Вложенные файлы (${selectedUris.size}):",
                                color = ColorTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                selectedUris.forEachIndexed { index, uri ->
                                    val name = queryFileName(context, uri)
                                    AttachmentChip(
                                        fileName = name,
                                        onRemove = {
                                            performTactileClick(context, intensity = 0.5f)
                                            selectedUris.removeAt(index)
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // Нижняя панель действий: "Добавить материалы" и "ОК"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            // Кнопка добавления файлов / фото
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .shadow(1.dp, RoundedCornerShape(12.dp), ambientColor = Color(0x08000000), spotColor = Color(0x10000000))
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ColorCardBackground)
                                    .border(1.dp, ColorHairline, RoundedCornerShape(12.dp))
                                    .clickable {
                                        performTactileClick(context)
                                        filePickerLauncher.launch("*/*")
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.AttachFile,
                                        contentDescription = null,
                                        tint = ColorTextPrimary,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Файлы / Фото",
                                        color = ColorTextPrimary,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }

                            // Кнопка "ОК" (Быстрое закрытие, смена цвета на лазурный и отправка)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ColorButtonBlack)
                                    .clickable {
                                        performTactileClick(context, intensity = 1.0f)
                                        val textToSend = inputText.trim()
                                        val urisToSend = selectedUris.toList()

                                        // Мгновенное сворачивание шухлядки
                                        isDrawerOpen = false

                                        // Очистка внутреннего состояния консоли
                                        inputText = ""
                                        selectedUris.clear()

                                        // Запуск цветовой фиксации плашки на лазурно-голубой
                                        coroutineScope.launch {
                                            isColorLatchedToCyan = true

                                            // Передача данных в доменный контур сессии
                                            onSendMaterials(textToSend, urisToSend)

                                            // Удержание цвета на время доставки/анализа материалов
                                            delay(3200L)
                                            isColorLatchedToCyan = false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "OK",
                                        color = ColorButtonTextWhite,
                                        fontSize = 13.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.6.sp
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Send,
                                        contentDescription = null,
                                        tint = ColorButtonTextWhite,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Белый чип прикрепленного материала с распознаванием типа файла и кнопкой удаления.
 */
@Composable
private fun AttachmentChip(
    fileName: String,
    onRemove: () -> Unit
) {
    val isImage = fileName.endsWith(".jpg", true) ||
        fileName.endsWith(".jpeg", true) ||
        fileName.endsWith(".png", true) ||
        fileName.endsWith(".webp", true)

    val isPdf = fileName.endsWith(".pdf", true)

    val icon = when {
        isImage -> Icons.Filled.Image
        isPdf -> Icons.Filled.Description
        else -> Icons.Filled.InsertDriveFile
    }

    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ColorCardBackground)
            .border(1.dp, ColorHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isImage) ColorActionCyan else ColorTextSecondary,
                modifier = Modifier.size(15.dp)
            )

            Text(
                text = fileName,
                color = ColorTextPrimary,
                fontSize = 11.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 140.dp)
            )

            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Удалить",
                    tint = ColorTextSecondary,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}