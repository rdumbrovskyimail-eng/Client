package com.client.app.service

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.client.app.MainActivity
import com.client.app.session.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {
    @Inject lateinit var sessionManager: SessionManager
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "live_client_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(channelId) == null) {
            nm?.createNotificationChannel(
                NotificationChannel(channelId, "Gemini Live Ultra", NotificationManager.IMPORTANCE_LOW)
            )
        }

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Gemini Live Ultra активен")
            .setContentText("Аппаратный дуплекс и микрофон S23 Ultra активны")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                101, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(101, notification)
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "client:live").apply { acquire(3600000L) }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}