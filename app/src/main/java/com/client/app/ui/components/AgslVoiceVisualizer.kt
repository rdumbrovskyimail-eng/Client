// >>> FILE: app/src/main/java/com/client/app/ui/components/AgslVoiceVisualizer.kt
package com.client.app.ui.components

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.client.app.audio.NativeAudioEngine
import com.client.app.session.LinkState
import com.client.app.session.SessionState

private const val AGSL_SHADER_SRC = """
uniform float2 u_Resolution;
uniform float u_Time;
uniform float u_State;
uniform float4 u_Spectrum;
uniform float4 u_Dynamics;

half4 main(float2 fragCoord) {
    float minRes = max(min(u_Resolution.x, u_Resolution.y), 1.0);
    float2 uv = (fragCoord - 0.5 * u_Resolution) / minRes;
    float dist = length(uv);

    float3 colIdle      = float3(0.96, 0.62, 0.05);
    float3 colListening = float3(0.23, 0.51, 0.96);
    float3 colThinking  = float3(0.55, 0.36, 0.96);
    float3 colSpeaking  = float3(0.06, 0.72, 0.51);
    float3 colBargeIn   = float3(0.94, 0.27, 0.27);

    float3 coreColor = colIdle;
    if (u_State < 1.0) {
        coreColor = mix(colIdle, colListening, clamp(u_State, 0.0, 1.0));
    } else if (u_State < 2.0) {
        coreColor = mix(colListening, colThinking, clamp(u_State - 1.0, 0.0, 1.0));
    } else if (u_State < 3.0) {
        coreColor = mix(colThinking, colSpeaking, clamp(u_State - 2.0, 0.0, 1.0));
    } else {
        coreColor = mix(colSpeaking, colBargeIn, clamp(u_State - 3.0, 0.0, 1.0));
    }

    float bassEnergy = u_Spectrum.x * 0.85 + u_Spectrum.y * 0.55;
    float midEnergy  = u_Spectrum.z * 0.45;
    float voiceRms   = max(u_Dynamics.y, u_Dynamics.z);

    float angle = atan(uv.y, uv.x + 1e-6);
    float harmonic = sin(angle * 3.0 + u_Time * 1.6) * cos(angle * 2.0 - u_Time * 0.9);
    harmonic += sin(angle * 5.0 - u_Time * 2.4) * 0.35;

    float baseRadius = 0.32 + (bassEnergy * 0.08) + (voiceRms * 0.05);
    float r = baseRadius + harmonic * (0.012 + midEnergy * 0.025);

    float auraDist = max(0.0, dist - (r * 0.92));
    float aura = 0.024 / (auraDist + 0.045);
    aura = clamp(aura, 0.0, 0.85);

    float dNorm = clamp(dist / max(r, 1e-4), 0.0, 1.0);
    float nz = sqrt(max(0.0, 1.0 - dNorm * dNorm));
    float2 nxy = uv / max(r, 1e-4);
    float3 n = normalize(float3(nxy, nz));

    float3 lightDir = normalize(float3(0.45, 0.75, 1.0));
    float3 viewDir  = float3(0.0, 0.0, 1.0);
    float3 halfVec  = normalize(lightDir + viewDir);

    float diff    = max(dot(n, lightDir), 0.0);
    float spec    = pow(max(dot(n, halfVec), 0.0), 24.0) * 0.45;
    float fresnel = pow(1.0 - max(n.z, 0.0), 2.5);
    float sss     = pow(max(dot(viewDir, lightDir), 0.0), 2.2) * 0.45;

    float3 surfaceColor = coreColor * (diff * 0.55 + 0.45) + sss * coreColor;
    surfaceColor += float3(spec);
    surfaceColor += fresnel * (coreColor + float3(0.20, 0.20, 0.35));

    float edge = smoothstep(r + 0.006, r - 0.006, dist);
    float3 finalColor = mix(coreColor * aura * 0.75, surfaceColor, edge);
    float alpha = clamp(mix(aura * 0.65, 1.0, edge), 0.0, 1.0);

    float dither = (fract(dot(fragCoord.xy, float2(0.06711056, 0.00583715)) * 52.9829189) - 0.5) / 255.0;
    finalColor += float3(dither);

    return half4(finalColor, alpha);
}
"""

@Composable
fun AgslVoiceVisualizer(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 220.dp
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        AgslOrbInternal(
            nativeEngine = nativeEngine,
            state = state,
            onClick = onClick,
            modifier = modifier,
            size = size
        )
    } else {
        LegacyVoiceVisualizer(
            nativeEngine = nativeEngine,
            state = state,
            onClick = onClick,
            modifier = modifier,
            size = size
        )
    }
}

@Composable
private fun LegacyVoiceVisualizer(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    onClick: () -> Unit,
    modifier: Modifier,
    size: Dp
) {
    val mic by nativeEngine.micLevel.collectAsState()
    val output by nativeEngine.outLevel.collectAsState()

    val targetAmplitude =
        maxOf(0.08f, mic, output)
            .coerceIn(0.08f, 1.0f)

    val animatedAmplitude by animateFloatAsState(
        targetValue = targetAmplitude,
        animationSpec = tween(
            durationMillis = 90,
            easing = LinearOutSlowInEasing
        ),
        label = "legacy_amplitude"
    )

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    state.error != null ->
                        "Ошибка голосового режима"

                    state.isAiSpeaking ->
                        "Ассистент говорит"

                    state.isMicActive ->
                        "Микрофон слушает"

                    state.link == LinkState.CONNECTING ||
                        state.link == LinkState.RECONNECTING ->
                        "Сессия подключается"

                    else ->
                        "Голосовой режим ожидания"
                }
            }
            .clickable(
                interactionSource =
                    remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier.size(size)
        ) {
            val canvasW = this.size.width
            val canvasH = this.size.height
            val centerX = canvasW / 2f
            val centerY = canvasH / 2f

            val baseRadius =
                minOf(canvasW, canvasH) * 0.31f

            val pulseRadius =
                baseRadius * (1f + animatedAmplitude * 0.28f)

            drawCircle(
                color = Color(0xFF202024),
                radius = baseRadius.coerceAtLeast(1f),
                center = androidx.compose.ui.geometry.Offset(
                    centerX,
                    centerY
                )
            )

            drawCircle(
                color = Color(0xFF4F46E5),
                radius = pulseRadius.coerceAtLeast(baseRadius),
                center = androidx.compose.ui.geometry.Offset(
                    centerX,
                    centerY
                ),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = minOf(canvasW, canvasH) * 0.035f
                )
            )
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun AgslOrbInternal(
    nativeEngine: NativeAudioEngine,
    state: SessionState,
    onClick: () -> Unit,
    modifier: Modifier,
    size: Dp
) {
    val runtimeShader = remember {
        RuntimeShader(AGSL_SHADER_SRC)
    }

    val shaderBrush = remember(runtimeShader) {
        androidx.compose.ui.graphics.ShaderBrush(runtimeShader)
    }

    val targetStateId = when {
        state.error != null -> 4.0f
        state.isAiSpeaking -> 3.0f
        state.link == LinkState.CONNECTING || state.link == LinkState.RECONNECTING -> 2.0f
        state.isMicActive -> 1.0f
        else -> 0.0f
    }

    val animatedState by animateFloatAsState(
        targetValue = targetStateId,
        animationSpec = tween(
            durationMillis = 350,
            easing = FastOutSlowInEasing
        ),
        label = "state_anim"
    )

    val shouldAnimate =
        state.link != LinkState.IDLE ||
            state.isMicActive ||
            state.isAiSpeaking ||
            state.error != null

    // P1 Fix (Проблема №17): Аппаратная анимация времени через VSYNC 120 FPS.
    // Заменяет корутину с delay(33L) и mutableFloatStateOf, устраняя лишние рекомпозиции
    // и фазовый джиттер (Judder). Чтение анимированного значения происходит строго в Draw Phase.
    val infiniteTransition = rememberInfiniteTransition(label = "agsl_vibration")
    val animatedTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 62.831853f, // 20 * PI
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "agsl_time"
    )

    Box(
        modifier = modifier
            .size(size)
            .semantics {
                role = Role.Button
                contentDescription = when {
                    state.error != null ->
                        "Ошибка голосового режима"

                    state.isAiSpeaking ->
                        "Ассистент говорит"

                    state.isMicActive ->
                        "Микрофон слушает"

                    state.link == LinkState.CONNECTING ||
                        state.link == LinkState.RECONNECTING ->
                        "Сессия подключается"

                    else ->
                        "Голосовой режим ожидания"
                }
            }
            .clickable(
                interactionSource =
                    remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier.size(size)
        ) {
            val w = this.size.width
            val h = this.size.height

            if (w > 0f && h > 0f) {
                runtimeShader.setFloatUniform("u_Resolution", w, h)
                runtimeShader.setFloatUniform(
                    "u_Time",
                    if (shouldAnimate) animatedTime else animatedTime * 0.25f
                )
                runtimeShader.setFloatUniform("u_State", animatedState)

                val spec = nativeEngine.spectrumUniforms.get()
                runtimeShader.setFloatUniform(
                    "u_Spectrum",
                    spec.getOrElse(0) { 0f },
                    spec.getOrElse(1) { 0f },
                    spec.getOrElse(2) { 0f },
                    spec.getOrElse(3) { 0f }
                )
                runtimeShader.setFloatUniform(
                    "u_Dynamics",
                    spec.getOrElse(4) { 0f },
                    nativeEngine.micLevel.value,
                    nativeEngine.outLevel.value,
                    0.0f
                )

                drawRect(brush = shaderBrush)
            }
        }
    }
}