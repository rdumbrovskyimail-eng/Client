// >>> FILE: app/src/main/java/com/client/app/ui/display/DisplayRateManager.kt
package com.client.app.ui.display

import android.os.Build
import android.util.Log
import android.view.Window
import android.view.WindowManager

object DisplayRateManager {

    private const val TAG = "DisplayRateManager"

    /**
     * Адаптивный режим: доверяем системным настройкам Samsung One UI.
     * Сбрасывает любые принудительные переопределения частоты на уровне WindowManager,
     * позволяя экрану работать нативно с системной частотой без микрозадержек.
     */
    fun setHighRefreshRate(window: Window, enable: Boolean) {
        runCatching {
            val layoutParams = window.attributes
            // 0.0f сообщает WindowManager: "Использовать системную политику One UI без переопределений"
            layoutParams.preferredRefreshRate = 0.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                layoutParams.preferredDisplayModeId = 0
            }
            window.attributes = layoutParams
            Log.d(TAG, "Частота экрана синхронизирована с системной политикой One UI (Adaptive default)")
        }.onFailure {
            Log.w(TAG, "Сбой сброса частоты окна", it)
        }
    }
}