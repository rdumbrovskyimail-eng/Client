package com.client.app.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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
import com.client.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var logger: AppLogger

    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaSession: MediaSessionCompat? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaObserverJob: Job? = null

    companion object {
        const val ACTION_STOP = "com.client.app.action.STOP"
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "live_client_voice_channel"

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _serviceError = MutableStateFlow<String?>(null)
        val serviceError: StateFlow<String?> = _serviceError.asStateFlow()

        @Volatile
        private var instanceRef: WeakReference<LiveSessionForegroundService>? = null

        fun prepareForStart() {
            _serviceError.value = null
        }

        fun ensureMicrophoneForegroundType(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
            val service = instanceRef?.get() ?: return false
            val permissionGranted = ContextCompat.checkSelfPermission(
                service,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted) return false
            return service.promoteToForeground()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        prepareForStart()
        createNotificationChannel()
        initMediaSession()
        promoteToForeground()
        if (_isServiceActive.value) {
            acquireHardwareLocks()
        }
        mediaObserverJob = serviceScope.launch {
            sessionManager.state.collect { state ->
                val mediaState = when {
                    state.isAiSpeaking -> PlaybackStateCompat.STATE_PLAYING
                    state.link != com.client.app.session.LinkState.IDLE -> PlaybackStateCompat.STATE_PAUSED
                    else -> PlaybackStateCompat.STATE_STOPPED
                }
                mediaSession?.setPlaybackState(
                    PlaybackStateCompat.Builder()
                        .setActions(PlaybackStateCompat.ACTION_STOP)
                        .setState(mediaState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                        .build()
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // SessionManager owns the shutdown ordering. Do not stop the
            // service here, otherwise the service could disappear before
            // capture/playback/WebSocket cleanup has completed.
            sessionManager.stopSession()
            return START_NOT_STICKY
        }

        promoteToForeground()
        if (_isServiceActive.value) {
            acquireHardwareLocks()
        }
        return START_NOT_STICKY
    }

    /**
     * Exposes the active session to Android media controls while the Live
     * foreground service owns the background audio lifecycle.
     */
    private fun initMediaSession() {
        runCatching {
            mediaSession = MediaSessionCompat(this, "GeminiLiveMediaSession").apply {
                setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS)
                setCallback(object : MediaSessionCompat.Callback() {
                    override fun onStop() {
                        sessionManager.stopSession()
                    }
                })
                val state = PlaybackStateCompat.Builder()
                    .setActions(PlaybackStateCompat.ACTION_STOP)
                    .setState(PlaybackStateCompat.STATE_STOPPED, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0.0f)
                    .build()
                setPlaybackState(state)
                isActive = true
            }
        }
    }

    private fun acquireHardwareLocks() {
        if (wakeLock?.isHeld != true) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "client:live_session_cpu"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }

        }

    }

    private fun promoteToForeground(): Boolean {
        val notification = buildNotification()
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (hasMic) {
                    type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    type
                )
            } else {
                @Suppress("DEPRECATION")
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }
            _serviceError.value = null
            _isServiceActive.value = true
            true
        }.getOrElse { throwable ->
            _serviceError.value =
                throwable.localizedMessage ?: throwable.javaClass.simpleName
            _isServiceActive.value = false
            logger.e(
                "LiveSessionForegroundService: startForeground failed",
                throwable
            )
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            false
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
            .setContentText("Фоновая Gemini Live-сессия активна")
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

    override fun onTaskRemoved(rootIntent: Intent?) {
        logger.w("LiveSessionForegroundService: task removed while service is active")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        _isServiceActive.value = false
        mediaObserverJob?.cancel()
        serviceScope.cancel()

        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null

        runCatching {
            mediaSession?.isActive = false
            mediaSession?.release()
        }
        mediaSession = null

        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
