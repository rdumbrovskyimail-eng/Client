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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val AGSL_SHADER_SRC = """
uniform shader u_Content;
uniform float2 u_Resolution;
uniform float u_Time;
uniform float u_State;
uniform float4 u_Spectrum;
uniform float4 u_Dynamics;

float hash3D(float3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float noise3D(float3 x) {
    float3 p = floor(x);
    float3 w = fract(x);
    float3 u = w * w * w * (w * (w * 6.0 - 15.0) + 10.0);
    float a = hash3D(p + float3(0.0, 0.0, 0.0));
    float b = hash3D(p + float3(1.0, 0.0, 0.0));
    float c = hash3D(p + float3(0.0, 1.0, 0.0));
    float d = hash3D(p + float3(1.0, 1.0, 0.0));
    float e = hash3D(p + float3(0.0, 0.0, 1.0));
    float f = hash3D(p + float3(1.0, 0.0, 1.0));
    float g = hash3D(p + float3(0.0, 1.0, 1.0));
    float h = hash3D(p + float3(1.0, 1.0, 1.0));

    return mix(
        mix(mix(a, b, u.x), mix(c, d, u.x), u.y),
        mix(mix(e, f, u.x), mix(g, h, u.x), u.y),
        u.z
    );
}

float mapSDF(float3 p) {
    float bassEnergy = u_Spectrum.x * 1.1 + u_Spectrum.y * 0.7;
    float midEnergy = u_Spectrum.z * 0.5;

    float r = 0.70 + bassEnergy * 0.22;
    r += sin(u_Time * 2.2) * 0.015;

    float n1 = noise3D(
        p * 2.1 + float3(0.0, u_Time * 0.75, 0.0)
    );

    float n2 = noise3D(
        p * 4.2 - float3(u_Time * 1.1, 0.0, u_Time * 0.6)
    );

    float displacement =
        (n1 * 0.62 + n2 * 0.38 - 0.5) *
        (0.18 + midEnergy * 0.40);

    return length(p) - r + displacement;
}

float3 calcNormal(float3 p) {
    float2 e = float2(1.0, -1.0) * 0.005;

    return normalize(
        e.xyy * mapSDF(p + e.xyy) +
        e.yyx * mapSDF(p + e.yyx) +
        e.yxy * mapSDF(p + e.yxy) +
        e.xxx * mapSDF(p + e.xxx)
    );
}

half4 main(float2 fragCoord) {
    float2 uv =
        (fragCoord - 0.5 * u_Resolution) /
        min(u_Resolution.x, u_Resolution.y);

    float3 colIdle = float3(0.96, 0.62, 0.05);
    float3 colListening = float3(0.23, 0.51, 0.96);
    float3 colThinking = float3(0.55, 0.36, 0.96);
    float3 colSpeaking = float3(0.06, 0.72, 0.51);
    float3 colBargeIn = float3(0.94, 0.27, 0.27);

    float3 coreColor = colIdle;

    if (u_State < 1.5) {
        coreColor = mix(
            colIdle,
            colListening,
            clamp(u_State, 0.0, 1.0)
        );
    } else if (u_State < 2.5) {
        coreColor = mix(
            colListening,
            colThinking,
            clamp(u_State - 1.0, 0.0, 1.0)
        );
    } else if (u_State < 3.5) {
        coreColor = mix(
            colThinking,
            colSpeaking,
            clamp(u_State - 2.0, 0.0, 1.0)
        );
    } else {
        coreColor = mix(
            colSpeaking,
            colBargeIn,
            clamp(u_State - 3.0, 0.0, 1.0)
        );
    }

    float3 ro = float3(0.0, 0.0, -2.4);
    float3 rd = normalize(float3(uv, 1.25));

    // Фиксированное число шагов без раннего break.
    float t = 0.0;

    for (int i = 0; i < 16; i++) {
        float3 p = ro + rd * t;
        float d = mapSDF(p);
        t += d * 1.22;
    }

    float3 finalColor = float3(0.0);
    float alpha = 0.0;

    float distToCenter = length(uv);
    float aura = 0.032 / (distToCenter - 0.44 + 0.07);
    aura = clamp(aura, 0.0, 0.85);

    finalColor += coreColor * aura * 0.75;
    alpha = aura * 0.65;

    if (t <= 3.8) {
        float3 p = ro + rd * t;
        float3 n = calcNormal(p);

        float3 lightDir =
            normalize(float3(0.45, 0.75, -1.0));

        float diff = max(dot(n, lightDir), 0.0);

        float3 h =
            normalize(lightDir - rd);

        float spec =
            pow(max(dot(n, h), 0.0), 28.0) * 0.45;

        float fresnel =
            pow(
                1.0 - max(dot(-rd, n), 0.0),
                2.8
            );

        float sss =
            pow(
                max(dot(rd, lightDir), 0.0),
                2.2
            ) * 0.55;

        float3 surfaceColor =
            coreColor * (diff * 0.55 + 0.45) +
            sss * coreColor;

        surfaceColor += float3(spec);
        surfaceColor +=
            fresnel *
            (coreColor + float3(0.2, 0.2, 0.35));

        finalColor =
            mix(finalColor, surfaceColor, 0.92);

        alpha = 1.0;
    }

    float dither =
        (
            fract(
                sin(
                    dot(
                        fragCoord,
                        float2(12.9898, 78.233)
                    )
                ) * 43758.5453
            ) - 0.5
        ) / 255.0;

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

                    state.link == LinkState.CONNECTING ->
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
            val centerX = size.width / 2f
            val centerY = size.height / 2f

            val baseRadius =
                minOf(size.width, size.height) * 0.31f

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
                    width =
                        minOf(size.width, size.height) * 0.035f
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
    val runtimeShader =
        remember {
            RuntimeShader(AGSL_SHADER_SRC)
        }

    /*
     * RenderEffect не зависит от значений uniform-параметров.
     * RuntimeShader остаётся тем же объектом, а uniform'ы
     * обновляются перед отрисовкой.
     *
     * Поэтому RenderEffect создаём один раз и не аллоцируем
     * новый объект на каждый кадр.
     */
    val renderEffect =
        remember(runtimeShader) {
            RenderEffect
                .createRuntimeShaderEffect(
                    runtimeShader,
                    "u_Content"
                )
                .asComposeRenderEffect()
        }

    val targetStateId = when {
        state.error != null -> 4.0f
        state.isAiSpeaking -> 3.0f
        state.link == LinkState.CONNECTING -> 2.0f
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

    /*
     * Движение шейдера обновляется примерно 30 FPS.
     * Это не является фиксацией частоты дисплея: это только
     * частота изменения time-uniform для визуального эффекта.
     */
    val shouldAnimate =
        state.link != LinkState.IDLE ||
            state.isMicActive ||
            state.isAiSpeaking ||
            state.error != null

    var timeParam by remember {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(shouldAnimate) {
        if (!shouldAnimate) {
            return@LaunchedEffect
        }

        while (isActive) {
            timeParam =
                (timeParam + 0.10471976f) % 125.663704f

            delay(33L)
        }
    }

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
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    val w = this.size.width
                    val h = this.size.height

                    runtimeShader.setFloatUniform(
                        "u_Resolution",
                        w,
                        h
                    )

                    runtimeShader.setFloatUniform(
                        "u_Time",
                        timeParam
                    )

                    runtimeShader.setFloatUniform(
                        "u_State",
                        animatedState
                    )

                    val spec =
                        nativeEngine.spectrumUniforms.get()

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

                    /*
                     * Используем один заранее созданный RenderEffect.
                     */
                    renderEffect
                        .let { this.renderEffect = it }
                }
        ) {
            drawRect(
                color = Color(0xFF09090B)
            )
        }
    }
}