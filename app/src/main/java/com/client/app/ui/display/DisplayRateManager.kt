// >>> FILE: app/src/main/java/com/client/app/ui/display/DisplayRateManager.kt
package com.client.app.ui.display

import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.Window

object DisplayRateManager {

    private const val TAG = "DisplayRateManager"

    /**
     * Полностью адаптивный режим: доверяем системным настройкам Samsung One UI.
     * Не навязывает жестких команд контроллеру матрицы, устраняя конфликты и рывки
     * на неоригинальных дисплеях.
     */
    fun setHighRefreshRate(window: Window, enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        runCatching {
            // Значение 0.0f с флагом DEFAULT сообщает SurfaceFlinger:
            // "Использовать системную политику устройства без принудительного переключения".
            // Графика будет работать ровно с той частотой, которая сейчас активна в One UI.
            window.setFrameRate(
                0.0f,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                0
            )
            Log.d(TAG, "Частота экрана синхронизирована с системной политикой One UI (Adaptive)")
        }.onFailure {
            Log.w(TAG, "Сбой сброса частоты окна", it)
        }
    }
}