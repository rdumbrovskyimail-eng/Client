package com.client.app.ui.components

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Чёрная палитра консоли промпта
private val ColorPill = Color(0xFF111113)
private val ColorSheet = Color(0xFF0D0D0F)
private val ColorField = Color(0xFF151518)
private val ColorButton = Color(0xFF1A1A1D)
private val ColorHairline = Color(0xFF26262B)
private val ColorHairlineFocused = Color(0xFF5A5A63)
private val ColorTextPrimary = Color(0xFFF4F4F5)
private val ColorTextSecondary = Color(0xFFA1A1AA)
private val ColorTextMuted = Color(0xFF71717A)
private val ColorApply = Color(0xFFF4F4F5)
private val ColorOnApply = Color(0xFF09090B)
private val ColorScrim = Color(0xCC000000)

// Фирменная точка Gemini — единственный цветной акцент консоли
private val GeminiAccent = Brush.sweepGradient(
    listOf(Color(0xFF4285F4), Color(0xFFEA4335), Color(0xFFFBBC04), Color(0xFF34A853), Color(0xFF4285F4))
)

private val SheetShape = RoundedCornerShape(26.dp)
private val FieldShape = RoundedCornerShape(18.dp)
private val ButtonShape = RoundedCornerShape(14.dp)
private val SheetEasing = CubicBezierEasing(0.16f, 1.0f, 0.3f, 1.0f)

/**
 * Тактильный импульс щелчка для кнопок консоли.
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
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(18L)
                }
            }
        }
    }
}

/**
 * Капсула «Prompt» в верхней панели: метка и первая строка текущей системной роли.
 */
@Composable
fun PromptPill(
    prompt: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(23.dp)
    val preview = remember(prompt) {
        prompt.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    }

    Row(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(ColorPill)
            .border(1.dp, ColorHairline, shape)
            .clickable {
                performTactileClick(context)
                onClick()
            }
            .padding(start = 6.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(ColorButton)
                .border(1.dp, ColorHairline, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = null,
                tint = ColorTextPrimary,
                modifier = Modifier.size(16.dp)
            )
        }

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(GeminiAccent)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "PROMPT",
                    color = ColorTextMuted,
                    fontSize = 9.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp
                )
            }
            Text(
                text = preview.ifEmpty { "Роль не задана — нажмите, чтобы написать" },
                color = if (preview.isEmpty()) ColorTextMuted else ColorTextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Редактор системной роли поверх экрана.
 *
 * Шторка занимает всё место между строкой состояния и клавиатурой (insets statusBars + ime/navigationBars),
 * но не выше 640 dp. Когда открывается клавиатура, сжимается только поле ввода (оно прокручивается),
 * а заголовок, счётчик и кнопки всегда остаются на экране.
 */
@Composable
fun PromptEditorSheet(
    visible: Boolean,
    currentPrompt: String,
    onApply: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    BackHandler(enabled = visible) {
        performTactileClick(context)
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(durationMillis = 180)),
        exit = fadeOut(animationSpec = tween(durationMillis = 160))
    ) {
        val clipboardManager = LocalClipboardManager.current
        val focusManager = LocalFocusManager.current
        var draft by remember { mutableStateOf(currentPrompt) }
        val sheetState = remember { MutableTransitionState(false) }.apply { targetState = true }
        val fieldInteraction = remember { MutableInteractionSource() }
        val fieldFocused by fieldInteraction.collectIsFocusedAsState()

        fun close() {
            focusManager.clearFocus(force = true)
            onDismiss()
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ColorScrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { close() }
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            AnimatedVisibility(
                visibleState = sheetState,
                enter = slideInVertically(
                    animationSpec = tween(durationMillis = 340, easing = SheetEasing),
                    initialOffsetY = { -it / 6 }
                ) + fadeIn(animationSpec = tween(durationMillis = 220)),
                exit = fadeOut(animationSpec = tween(durationMillis = 120))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 640.dp)
                        .fillMaxHeight()
                        .clip(SheetShape)
                        .background(ColorSheet)
                        .border(1.dp, ColorHairline, SheetShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { /* клики внутри шторки её не закрывают */ }
                        .padding(18.dp)
                ) {
                    // Шапка
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(GeminiAccent)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "SYSTEM PROMPT",
                                    color = ColorTextMuted,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.8.sp
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "Роль и правила ассистента",
                                color = ColorTextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = "Применяется сразу: активная сессия переподключится с новой ролью.",
                                color = ColorTextSecondary,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        SheetIconButton(
                            icon = Icons.Filled.Close,
                            description = "Закрыть",
                            size = 38
                        ) {
                            performTactileClick(context)
                            close()
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Поле ввода: забирает всё оставшееся место и прокручивается внутри себя
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .clip(FieldShape)
                            .background(ColorField)
                            .border(1.dp, if (fieldFocused) ColorHairlineFocused else ColorHairline, FieldShape)
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        BasicTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier.fillMaxSize(),
                            textStyle = TextStyle(
                                color = ColorTextPrimary,
                                fontSize = 15.sp,
                                lineHeight = 22.sp,
                                fontFamily = FontFamily.SansSerif
                            ),
                            interactionSource = fieldInteraction,
                            cursorBrush = SolidColor(ColorTextPrimary),
                            decorationBox = { innerTextField ->
                                Box(modifier = Modifier.fillMaxSize()) {
                                    if (draft.isEmpty()) {
                                        Text(
                                            text = "Например: «Ты — терпеливый преподаватель немецкого. Отвечай коротко и по делу.»",
                                            color = ColorTextMuted,
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "${draft.length} симв.",
                        color = ColorTextMuted,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.End)
                    )
                    Spacer(Modifier.height(10.dp))

                    // Панель действий
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SheetIconButton(icon = Icons.Filled.ContentCopy, description = "Копировать") {
                            performTactileClick(context)
                            if (draft.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(draft))
                                Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
                            }
                        }
                        SheetIconButton(icon = Icons.Filled.ContentPaste, description = "Вставить") {
                            performTactileClick(context)
                            val pasted = clipboardManager.getText()?.text.orEmpty()
                            if (pasted.isNotBlank()) {
                                draft = if (draft.isBlank()) pasted else "$draft\n$pasted"
                            }
                        }
                        SheetIconButton(icon = Icons.Filled.DeleteOutline, description = "Очистить") {
                            performTactileClick(context)
                            draft = ""
                        }

                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                                .clip(ButtonShape)
                                .background(ColorApply)
                                .clickable {
                                    performTactileClick(context)
                                    val clean = draft.trim()
                                    val changed = clean != currentPrompt.trim()
                                    if (changed) onApply(clean)
                                    close()
                                    Toast.makeText(
                                        context,
                                        if (changed) "Роль применена" else "Без изменений",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = ColorOnApply,
                                modifier = Modifier.size(17.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Готово",
                                color = ColorOnApply,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.3.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Квадратная графитовая кнопка-пиктограмма консоли.
 */
@Composable
private fun SheetIconButton(
    icon: ImageVector,
    description: String,
    size: Int = 46,
    onClick: () -> Unit
) {
    val shape = if (size < 46) CircleShape else ButtonShape
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(shape)
            .background(ColorButton)
            .border(1.dp, ColorHairline, shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = ColorTextPrimary,
            modifier = Modifier.size(18.dp)
        )
    }
}