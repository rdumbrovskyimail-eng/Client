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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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
 * Горизонтальная плашка «Materials», расположенная чуть ниже панели Prompt,
 * и выезжающая сверху вниз консоль прикрепления файлов с текстом.
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

    // Пружинный толчок вниз при нажатии
    val pillVerticalBump = remember { Animatable(0f) }

    val animatedTextColor by animateColorAsState(
        targetValue = if (isColorLatchedToCyan) ColorActionCyan else ColorTextPrimary,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "materials_pill_color"
    )

    val animatedBorderColor by animateColorAsState(
        targetValue = if (isColorLatchedToCyan) ColorActionCyan.copy(alpha = 0.5f) else ColorHairline,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "materials_pill_border"
    )

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            performTactileClick(context, intensity = 0.65f)
            selectedUris.addAll(uris)
        }
    }

    BackHandler(enabled = isDrawerOpen) {
        performTactileClick(context)
        isDrawerOpen = false
    }

    Box(modifier = modifier.fillMaxSize()) {

        // =====================================================================
        // 1. ГОРИЗОНТАЛЬНАЯ КНОПКА "MATERIALS" (ЧУТЬ НИЖЕ ПАНЕЛИ PROMPT)
        // =====================================================================
        if (!isDrawerOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp, start = 20.dp, end = 20.dp)
                    .offset { IntOffset(0, pillVerticalBump.value.roundToInt()) }
                    .fillMaxWidth()
                    .height(44.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(14.dp),
                        ambientColor = Color(0x0A000000),
                        spotColor = Color(0x14000000)
                    )
                    .clip(RoundedCornerShape(14.dp))
                    .background(ColorPillBackground)
                    .border(
                        width = 1.dp,
                        color = animatedBorderColor,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        coroutineScope.launch {
                            performTactileClick(context)

                            launch {
                                pillVerticalBump.animateTo(
                                    targetValue = 4.dp.value,
                                    animationSpec = tween(durationMillis = 100, easing = FastOutSlowInEasing)
                                )
                                pillVerticalBump.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }

                            isDrawerOpen = true
                        }
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
                        tint = animatedTextColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "M  A  T  E  R  I  A  L  S",
                        color = animatedTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // =====================================================================
        // 2. ВЫЕЗЖАЮЩАЯ СВЕРХУ ВНИЗ КОНСОЛЬ ПРИКРЕПЛЕНИЯ ВЛОЖЕНИЙ
        // =====================================================================
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(durationMillis = 340, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
            ) + fadeIn(animationSpec = tween(220)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(durationMillis = 200, easing = FastOutLinearInEasing)
            ) + fadeOut(animationSpec = tween(160))
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
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .wrapContentHeight()
                        .shadow(
                            elevation = 18.dp,
                            shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp),
                            ambientColor = Color(0x14000000),
                            spotColor = Color(0x28000000)
                        )
                        .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
                        .background(ColorDrawerBackground)
                        .border(
                            width = 1.dp,
                            color = ColorHairline,
                            shape = RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Защита от кликов сквозь консоль */ }
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 20.dp, vertical = 18.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {

                        // Шапка: заголовок и кнопка закрытия
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

                        // Поле ввода текстового комментария
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

                        // Нижняя панель действий: "Файлы / Фото" и "ОК"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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

                                        isDrawerOpen = false
                                        inputText = ""
                                        selectedUris.clear()

                                        coroutineScope.launch {
                                            isColorLatchedToCyan = true
                                            onSendMaterials(textToSend, urisToSend)
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