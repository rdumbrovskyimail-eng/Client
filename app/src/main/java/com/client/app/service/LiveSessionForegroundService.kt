// >>> FILE: app/src/main/java/com/client/app/service/LiveSessionForegroundService.kt
package com.client.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.client.app.MainActivity
import com.client.app.session.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {

    @Inject lateinit var sessionManager: SessionManager

    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renewJob: Job? = null

    companion object {
        const val ACTION_STOP = "com.client.app.action.STOP"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "live_client_channel"
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 минут
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Гарантированный старт в первые миллисекунды исключает падение по таймауту 5 сек
        promoteToForeground()
        acquireWakeLockWithRenewal()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            sessionManager.stopSession()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
            return START_NOT_STICKY
        }

        promoteToForeground()
        // START_NOT_STICKY: сервис не должен самовольно подниматься с пустым сокетом при убийстве OS
        return START_NOT_STICKY
    }

    private fun promoteToForeground() {
        val notification = buildNotification()

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> { // Android 14+ (API 34+)
                    val type = if (hasMic) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> { // Android 11-13 (API 30-33)
                    val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> { // Android 10 (API 29)
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                }
                else -> {
                    startForeground(NOTIFICATION_ID, notification)
                }
            }
        }.onFailure {
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gemini Live Ultra",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Активная сессия дуплекса"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = Intent(this, LiveSessionForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini Live Ultra активен")
            .setContentText("Микрофон и динамики задействованы")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Завершить",
                stopPendingIntent
            )
            .build()
    }

    private fun acquireWakeLockWithRenewal() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "client:live_session").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        renewJob = serviceScope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L)
                runCatching { wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        renewJob?.cancel()
        serviceScope.cancel()

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}