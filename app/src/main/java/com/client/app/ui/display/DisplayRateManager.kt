// >>> FILE: app/src/main/java/com/client/app/ui/display/DisplayRateManager.kt
package com.client.app.ui.display

import android.os.Build
import android.view.Display
import android.view.Surface
import android.view.Window

object DisplayRateManager {

    /**
     * Аппаратная фиксация 120 Гц на дисплейной панели Samsung LTPO 2.0 (Dynamic AMOLED 2X).
     * Исключает сброс частоты до 24 Гц контроллером One UI при отсутствии касаний пальцем.
     */
    fun setHighRefreshRate(window: Window, enable: Boolean) {
        runCatching {
            val layoutParams = window.attributes

            if (enable) {
                // 1. Принудительный запрос частоты кадра на уровне Surface
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setFrameRate(120.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }

                // 2. Поиск и фиксация 120-Гц режима в DisplayModeDirector
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        window.context.display
                    } else {
                        @Suppress("DEPRECATION")
                        window.windowManager.defaultDisplay
                    }

                    val mode120 = display?.supportedModes?.firstOrNull { mode ->
                        mode.refreshRate >= 119.0f
                    }
                    if (mode120 != null) {
                        layoutParams.preferredDisplayModeId = mode120.modeId
                    }
                }
                layoutParams.preferredRefreshRate = 120.0f
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    window.setFrameRate(0.0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    layoutParams.preferredDisplayModeId = 0
                }
                layoutParams.preferredRefreshRate = 0.0f
            }

            window.attributes = layoutParams
        }
    }
}