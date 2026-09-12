// >>> FILE: app/src/main/java/com/client/app/service/LiveSessionForegroundService.kt
package com.client.app.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.client.app.MainActivity
import com.client.app.session.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {

    @Inject lateinit var sessionManager: SessionManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSessionCompat? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renewJob: Job? = null

    companion object {
        const val ACTION_STOP = "com.client.app.action.STOP"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "live_client_voice_channel"
        private const val WAKELOCK_TIMEOUT_MS = 15 * 60 * 1000L // 15 минут
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initMediaSession()
        acquireHardwareLocks()
        promoteToForeground()
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
        return START_NOT_STICKY
    }

    /**
     * Ключевой механизм обхода One UI App Freezer:
     * Активная MediaSession со статусом STATE_PLAYING блокирует перевод cgroup процесса в статус FROZEN.
     */
    private fun initMediaSession() {
        runCatching {
            mediaSession = MediaSessionCompat(this, "GeminiLiveMediaSession").apply {
                setFlags(
                    MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                )
                val state = PlaybackStateCompat.Builder()
                    .setActions(
                        PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_STOP
                    )
                    .setState(PlaybackStateCompat.STATE_PLAYING, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
        }
    }

    private fun acquireHardwareLocks() {
        // 1. Аппаратный процессорный замок с пролонгацией
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "client:live_session_cpu").apply {
            setReferenceCounted(false)
            acquire(WAKELOCK_TIMEOUT_MS)
        }

        renewJob = serviceScope.launch {
            while (isActive) {
                delay(8 * 60 * 1000L) // Обновление каждые 8 минут
                runCatching { wakeLock?.acquire(WAKELOCK_TIMEOUT_MS) }
            }
        }

        // 2. Радиочастотный замок Wi-Fi 7 (FastConnect 7800 Low Latency Lock)
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "client:live_session_wifi")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun promoteToForeground() {
        val notification = buildNotification()

        val hasMic = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        runCatching {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                    val type = if (hasMic) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    }
                    startForeground(NOTIFICATION_ID, notification, type)
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
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
                "Gemini Live Ultra Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Активный фоновый дуплекс-диалог"
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

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini Live Ultra активен")
            .setContentText("Аппаратный дуплекс и микрофон S23 Ultra задействованы")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Завершить",
                stopPendingIntent
            )

        // Связываем с токеном MediaSession для нативного стиля Android Media
        mediaSession?.let { session ->
            builder.setStyle(
                MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0)
            )
        }

        return builder.build()
    }

    override fun onDestroy() {
        super.onDestroy()
        renewJob?.cancel()
        serviceScope.cancel()

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null

        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null
    }

    override fun onBind(intent: Intent?): IBinder? = null
}