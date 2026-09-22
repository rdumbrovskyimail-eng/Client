package com.client.app.ui.display

import android.view.Window

object DisplayRateManager {

    /**
     * Запрашивает предпочтительную частоту обновления окна.
     *
     * Это именно preference/hint для WindowManager, а не гарантия
     * постоянной аппаратной работы дисплея на 120 Гц.
     *
     * При отключении предпочтение сбрасывается, позволяя системе
     * самостоятельно выбирать подходящую частоту.
     */
    fun setHighRefreshRate(window: Window, enable: Boolean) {
        runCatching {
            val layoutParams = window.attributes

            layoutParams.preferredRefreshRate = if (enable) {
                120.0f
            } else {
                0.0f
            }

            /*
             * Не фиксируем preferredDisplayModeId вручную.
             *
             * preferredRefreshRate достаточно как предпочтения частоты,
             * а выбор конкретного Display.Mode должен оставаться за системой.
             * Поэтому при каждом вызове явно снимаем ранее заданный mode-id.
             */
            layoutParams.preferredDisplayModeId = 0

            window.attributes = layoutParams
        }
    }
}