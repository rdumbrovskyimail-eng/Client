package com.client.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// Цветовые константы белого минимализма для консоли Prompt
private val ColorPillBackground = Color(0xFFFFFFFF)
private val ColorDrawerBackground = Color(0xFFFAFAFA)
private val ColorInputBackground = Color(0xFFFFFFFF)
private val ColorHairline = Color(0xFFEBEBEB)
private val ColorTextPrimary = Color(0xFF09090B)
private val ColorTextSecondary = Color(0xFF71717A)
private val ColorButtonBackground = Color(0xFFFFFFFF)
private val ColorApplyButtonBackground = Color(0xFF09090B)
private val ColorApplyButtonText = Color(0xFFFAFAFA)
private val ColorScrim = Color(0x24000000)

/**
 * Тактильный импульс щелчка X-Axis LRA для физического отклика кнопок и плашки.
 */
private fun performTactileClick(context: Context) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            val vibrator = vibratorManager?.defaultVibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 0)
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
 * Верхняя горизонтальная плашка «Prompt» на всю ширину экрана с горизонтальной радужной дисперсией
 * и выезжающей сверху вниз консолью редактирования системной роли Gemini Live.
 */
@Composable
fun PromptConsoleDrawer(
    currentPrompt: String,
    onApplyPrompt: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    var isDrawerOpen by remember { mutableStateOf(false) }
    var draftPromptText by remember(currentPrompt) { mutableStateOf(currentPrompt) }

    // Контроль спектрального радужного свечения (цвета Gemini)
    var isRainbowActive by remember { mutableStateOf(false) }
    val rainbowOffsetAnim = remember { Animatable(0f) }

    // Пружинный толчок вниз при нажатии на верхнюю плашку
    val pillVerticalBump = remember { Animatable(0f) }

    BackHandler(enabled = isDrawerOpen) {
        performTactileClick(context)
        isDrawerOpen = false
    }

    val rainbowColors = listOf(
        Color(0xFF4285F4),
        Color(0xFFEA4335),
        Color(0xFFFBBC04),
        Color(0xFF34A853),
        Color(0xFF4285F4)
    )

    // Горизонтальный градиент бегущей волны
    val rainbowBrush = Brush.horizontalGradient(
        colors = rainbowColors,
        startX = rainbowOffsetAnim.value,
        endX = rainbowOffsetAnim.value + 400f
    )

    Box(modifier = modifier.fillMaxSize()) {

        // =====================================================================
        // 1. ВЕРХНИЙ ГОРИЗОНТАЛЬНЫЙ БЛОК "P R O M P T" НА ВСЮ ШИРИНУ ЭКРАНА
        // =====================================================================
        if (!isDrawerOpen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, pillVerticalBump.value.roundToInt()) }
                    .fillMaxWidth()
                    .height(48.dp)
                    .shadow(
                        elevation = 4.dp,
                        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp),
                        ambientColor = Color(0x0A000000),
                        spotColor = Color(0x14000000)
                    )
                    .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(ColorPillBackground)
                    .border(
                        width = 1.dp,
                        color = ColorHairline,
                        shape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        coroutineScope.launch {
                            performTactileClick(context)
                            isRainbowActive = true

                            // Механический пружинный толчок плашки вниз
                            launch {
                                pillVerticalBump.animateTo(
                                    targetValue = 4.dp.value,
                                    animationSpec = tween(durationMillis = 110, easing = FastOutSlowInEasing)
                                )
                                pillVerticalBump.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessLow
                                    )
                                )
                            }

                            // Горизонтальный пробег радуги слева направо
                            rainbowOffsetAnim.snapTo(-400f)
                            rainbowOffsetAnim.animateTo(
                                targetValue = 1200f,
                                animationSpec = tween(durationMillis = 420, easing = LinearEasing)
                            )

                            draftPromptText = currentPrompt
                            isDrawerOpen = true
                            isRainbowActive = false
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "P  R  O  M  P  T",
                        style = if (isRainbowActive) {
                            TextStyle(
                                brush = rainbowBrush,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 2.5.sp
                            )
                        } else {
                            TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 2.5.sp
                            )
                        },
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // =====================================================================
        // 2. ВЫЕЗЖАЮЩАЯ СВЕРХУ ВНИЗ БЕЛАЯ КОНСОЛЬ РЕДАКТИРОВАНИЯ
        // =====================================================================
        AnimatedVisibility(
            visible = isDrawerOpen,
            enter = slideInVertically(
                initialOffsetY = { -it },
                animationSpec = tween(durationMillis = 360, easing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f))
            ) + fadeIn(animationSpec = tween(240)),
            exit = slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(durationMillis = 260, easing = FastOutLinearInEasing)
            ) + fadeOut(animationSpec = tween(180))
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
                        .fillMaxHeight(0.68f)
                        .shadow(
                            elevation = 16.dp,
                            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
                            ambientColor = Color(0x14000000),
                            spotColor = Color(0x28000000)
                        )
                        .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                        .background(ColorDrawerBackground)
                        .border(
                            width = 1.dp,
                            color = ColorHairline,
                            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp)
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* Изоляция кликов внутри консоли */ }
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.ime)
                    ) {

                        // Шапка: заголовок и кнопка закрытия
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "СИСТЕМНАЯ РОЛЬ И ПРОМПТ",
                                    color = ColorTextSecondary,
                                    fontSize = 11.5.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.8.sp
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Инструкция мышления и поведения ассистента",
                                    color = ColorTextSecondary.copy(alpha = 0.8f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.SansSerif
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(ColorButtonBackground)
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
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Рабочее поле ввода текста промпта
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(ColorInputBackground)
                                .border(1.dp, ColorHairline, RoundedCornerShape(14.dp))
                                .padding(14.dp)
                        ) {
                            BasicTextField(
                                value = draftPromptText,
                                onValueChange = { draftPromptText = it },
                                modifier = Modifier.fillMaxSize(),
                                textStyle = TextStyle(
                                    color = ColorTextPrimary,
                                    fontSize = 13.5.sp,
                                    fontFamily = FontFamily.SansSerif,
                                    lineHeight = 19.sp
                                ),
                                cursorBrush = SolidColor(ColorTextPrimary),
                                decorationBox = { innerTextField ->
                                    if (draftPromptText.isEmpty()) {
                                        Text(
                                            text = "Задайте системную инструкцию роли Gemini Live...",
                                            color = ColorTextSecondary.copy(alpha = 0.6f),
                                            fontSize = 13.sp,
                                            lineHeight = 18.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            )
                        }

                        Spacer(Modifier.height(14.dp))

                        // Нижняя панель действий
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            MinimalistActionButton(
                                icon = Icons.Filled.ContentCopy,
                                label = "Копия",
                                modifier = Modifier.weight(1f)
                            ) {
                                performTactileClick(context)
                                if (draftPromptText.isNotBlank()) {
                                    clipboardManager.setText(AnnotatedString(draftPromptText))
                                    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                                }
                            }

                            MinimalistActionButton(
                                icon = Icons.Filled.ContentPaste,
                                label = "Вставка",
                                modifier = Modifier.weight(1f)
                            ) {
                                performTactileClick(context)
                                val text = clipboardManager.getText()?.text.orEmpty()
                                if (text.isNotBlank()) {
                                    draftPromptText = if (draftPromptText.isBlank()) text else "$draftPromptText\n$text"
                                }
                            }

                            MinimalistActionButton(
                                icon = Icons.Filled.DeleteOutline,
                                label = "Сброс",
                                modifier = Modifier.weight(1f)
                            ) {
                                performTactileClick(context)
                                draftPromptText = ""
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(42.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ColorApplyButtonBackground)
                                    .clickable {
                                        performTactileClick(context)
                                        onApplyPrompt(draftPromptText.trim())
                                        isDrawerOpen = false
                                        Toast.makeText(context, "Роль обновлена", Toast.LENGTH_SHORT).show()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = ColorApplyButtonText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = "Готово",
                                        color = ColorApplyButtonText,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.4.sp
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
 * Стандартная белая контурная кнопка в строгом стиле швейцарского минимализма.
 */
@Composable
private fun MinimalistActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .height(42.dp)
            .shadow(1.dp, RoundedCornerShape(12.dp), ambientColor = Color(0x08000000), spotColor = Color(0x10000000))
            .clip(RoundedCornerShape(12.dp))
            .background(ColorButtonBackground)
            .border(1.dp, ColorHairline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = ColorTextPrimary,
                modifier = Modifier.size(15.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = label,
                color = ColorTextPrimary,
                fontSize = 11.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}