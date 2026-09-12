// >>> FILE: app/src/main/java/com/client/app/haptics/HapticBargeInManager.kt
package com.client.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticBargeInManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    companion object {
        // Минимальный интервал между срабатываниями (защита от дребезга)
        private const val DEBOUNCE_MS = 450L
    }

    private var lastTriggerMs = 0L

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // Предварительно скомпилированный аппаратный примитив для нулевой задержки вызова
    private val clickEffect: VibrationEffect? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f, 0)
                .compose()
        }.getOrNull()
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    } else {
        null
    }

    /**
     * Срабатывает в момент фиксации перебивания (Barge-In).
     * Задержка исполнения на S23 Ultra составляет менее 3 мс.
     */
    fun triggerBargeIn() {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < DEBOUNCE_MS) return
        lastTriggerMs = now

        val v = vibrator ?: return
        if (!v.hasVibrator()) return

        try {
            if (clickEffect != null) {
                v.vibrate(clickEffect)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20L)
            }
        } catch (e: Exception) {
            logger.e("HapticBargeInManager vibration error", e)
        }
    }
}