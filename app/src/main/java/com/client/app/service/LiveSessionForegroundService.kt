package com.client.app.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.client.app.MainActivity
import com.client.app.session.LinkState
import com.client.app.session.SessionManager
import com.client.app.util.AppLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import javax.inject.Inject

/**
 * УСТРАНЕНИЕ ДЕФЕКТА 180:
 * Мониторинг энергопотребления голосовой сессии (Power Budget Tracker).
 * Целевой бюджет платформы Snapdragon 8 Gen 2: расход < 7% емкости аккумулятора в час.
 */
class SessionPowerBudgetTracker(
    private val context: Context,
    private val logger: AppLogger
) {
    private var sessionStartTimeMs: Long = 0L
    private var startBatteryLevel: Int = -1
    private var activeDuplexDurationMs: Long = 0L
    private var lastActiveStartMs: Long = 0L
    private var wifiLockHeldDurationMs: Long = 0L
    private var lastWifiLockStartMs: Long = 0L

    fun onSessionStarted() {
        sessionStartTimeMs = SystemClock.elapsedRealtime()
        startBatteryLevel = readBatteryLevel()
        activeDuplexDurationMs = 0L
        wifiLockHeldDurationMs = 0L
        logger.d("PowerBudget: Старт мониторинга (уровень заряда: $startBatteryLevel%)")
    }

    fun onDuplexStateChanged(isLive: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isLive) {
            lastActiveStartMs = now
        } else if (lastActiveStartMs > 0L) {
            activeDuplexDurationMs += (now - lastActiveStartMs)
            lastActiveStartMs = 0L
        }
    }

    fun onWifiLockStateChanged(isHeld: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (isHeld) {
            lastWifiLockStartMs = now
        } else if (lastWifiLockStartMs > 0L) {
            wifiLockHeldDurationMs += (now - lastWifiLockStartMs)
            lastWifiLockStartMs = 0L
        }
    }

    fun reportBudgetSummary() {
        val now = SystemClock.elapsedRealtime()
        val totalSessionMs = now - sessionStartTimeMs
        if (totalSessionMs <= 0L) return

        val totalMinutes = totalSessionMs / 60000.0
        val endBatteryLevel = readBatteryLevel()
        val batteryDrop = if (startBatteryLevel >= 0 && endBatteryLevel >= 0) {
            (startBatteryLevel - endBatteryLevel).coerceAtLeast(0)
        } else {
            0
        }

        val dischargeRatePerHour = if (totalMinutes >= 5.0) {
            (batteryDrop.toDouble() / totalMinutes) * 60.0
        } else {
            0.0
        }

        val wifiHeldSec = wifiLockHeldDurationMs / 1000L
        val activeSec = activeDuplexDurationMs / 1000L

        logger.d(
            "PowerBudget: Итоги сессии -> " +
            "Длительность=${"%.1f".format(totalMinutes)} мин, " +
            "Расход=$batteryDrop%, " +
            "Темп=${"%.2f".format(dischargeRatePerHour)}%/час (Лимит: 7.0%/час), " +
            "Активный дуплекс=${activeSec}с, " +
            "Low-Latency Wi-Fi=${wifiHeldSec}с"
        )
    }

    private fun readBatteryLevel(): Int {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        return bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
    }
}

@AndroidEntryPoint
class LiveSessionForegroundService : Service() {

    @Inject lateinit var sessionManager: SessionManager
    @Inject lateinit var logger: AppLogger

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var mediaSession: MediaSessionCompat? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var mediaObserverJob: Job? = null
    private var powerTracker: SessionPowerBudgetTracker? = null

    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isCurrentNetworkWifi: Boolean = false

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
            // Тип microphone уже активен — повторный startForeground() не нужен
            if ((service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0) return true
            return service.promoteToForeground()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        prepareForStart()
        createNotificationChannel()
        initMediaSession()
        initNetworkMonitoring()

        powerTracker = SessionPowerBudgetTracker(this, logger).apply {
            onSessionStarted()
        }

        promoteToForeground()
        if (_isServiceActive.value) {
            acquireCpuWakeLock()
        }

        mediaObserverJob = serviceScope.launch {
            sessionManager.state.collect { state ->
                val isLive = state.link == LinkState.LIVE
                powerTracker?.onDuplexStateChanged(isLive)

                // УСТРАНЕНИЕ ДЕФЕКТА 179: Динамическое управление блокировкой Wi-Fi
                updateWifiLockState(isLive)

                val mediaState = when {
                    state.link != LinkState.IDLE -> PlaybackStateCompat.STATE_PLAYING
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
            sessionManager.stopSession()
            return START_NOT_STICKY
        }

        promoteToForeground()
        if (_isServiceActive.value) {
            acquireCpuWakeLock()
        }
        return START_NOT_STICKY
    }

    private fun initNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        checkCurrentNetworkTransport()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                if (isCurrentNetworkWifi != isWifi) {
                    isCurrentNetworkWifi = isWifi
                    val isLive = sessionManager.state.value.link == LinkState.LIVE
                    updateWifiLockState(isLive)
                }
            }

            override fun onLost(network: Network) {
                isCurrentNetworkWifi = false
                updateWifiLockState(false)
            }
        }
        networkCallback = callback
        // Главный поток: wifiLock меняется там же, где его трогает mediaObserverJob.
        // Колбэк обязательно снимается в onDestroy().
        runCatching {
            connectivityManager?.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        }.onFailure {
            networkCallback = null
            logger.w("LiveSessionForegroundService: NetworkCallback не зарегистрирован: ${it.message}")
        }
    }

    private fun checkCurrentNetworkTransport() {
        val cm = connectivityManager ?: return
        val activeNetwork = cm.activeNetwork ?: return
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return
        isCurrentNetworkWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * УСТРАНЕНИЕ ДЕФЕКТА 179:
     * Блокировка Low-Latency Wi-Fi удерживается строго при наличии Wi-Fi соединения и активного дуплекса.
     * При переходе на сотовую сеть или паузе в диалоге радиомодуль переходит в энергосбережение.
     */
    private fun updateWifiLockState(isLive: Boolean) {
        val shouldHoldWifi = isLive && isCurrentNetworkWifi

        if (shouldHoldWifi) {
            if (wifiLock?.isHeld != true) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                wifiLock = wm.createWifiLock(lockMode, "client:live_session_wifi").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                powerTracker?.onWifiLockStateChanged(true)
            }
        } else {
            if (wifiLock?.isHeld == true) {
                runCatching { wifiLock?.release() }
                wifiLock = null
                powerTracker?.onWifiLockStateChanged(false)
            }
        }
    }

    private fun acquireCpuWakeLock() {
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
        _isServiceActive.value = false
        mediaObserverJob?.cancel()
        serviceScope.cancel()

        networkCallback?.let { cb -> runCatching { connectivityManager?.unregisterNetworkCallback(cb) } }
        networkCallback = null

        powerTracker?.reportBudgetSummary()
        powerTracker = null

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

        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}