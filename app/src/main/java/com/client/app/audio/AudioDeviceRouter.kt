// >>> FILE: app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt
package com.client.app.audio

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRoutePath {
    SPEAKER_SHARED,
    BLUETOOTH_COMMUNICATION
}

data class RouteProfile(
    val path: AudioRoutePath,
    val sampleRateOut: Int,
    val leadInBufferSizeFrames: Int,
    val vadThresholdStart: Float,
    val vadThresholdEnd: Float,
    val deviceName: String,
    val inputDeviceId: Int = 0,
    val outputDeviceId: Int = 0
)

@OptIn(FlowPreview::class)
@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val routeLock = Any()

    private var routerScope: CoroutineScope? = null

    // Сохранение аудиосостояния устройства до старта сессии для бережного восстановления
    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    // Асинхронное состояние Bluetooth SCO для Android 9–11 (API 28–30)
    private var legacyScoRequested = false
    private var legacyScoConnected = false
    private var legacyScoReceiverRegistered = false
    private var lastLegacyScoStartMs = 0L

    private companion object {
        const val LEGACY_SCO_RETRY_COOLDOWN_MS = 1500L
        const val ROUTE_DEBOUNCE_MS = 180L
    }

    private val debounceTrigger = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _currentProfile = MutableStateFlow(createSpeakerProfile())
    val currentProfile: StateFlow<RouteProfile> = _currentProfile.asStateFlow()

    private var onRouteChangedListener: ((RouteProfile) -> Unit)? = null
    private var isCallbackRegistered = false
    private var isCommunicationListenerRegistered = false

    private val communicationDeviceListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                debounceTrigger.tryEmit(Unit)
                logger.d("AudioDeviceRouter: OnCommunicationDeviceChangedListener -> id=${device?.id}, type=${device?.type}, name=${device?.productName}")
            }
        } else {
            null
        }

    private data class RouteFingerprint(
        val path: AudioRoutePath,
        val inDevId: Int,
        val outDevId: Int,
        val sampleRate: Int
    )

    @Volatile
    private var activeFingerprint: RouteFingerprint? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            debounceTrigger.tryEmit(Unit)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            debounceTrigger.tryEmit(Unit)
        }
    }

    private val legacyScoStateReceiver = object : BroadcastReceiver() {
        @Suppress("DEPRECATION")
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return

            val state = intent.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR
            )

            synchronized(routeLock) {
                applyLegacyScoStateLocked(state)
            }
            debounceTrigger.tryEmit(Unit)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyScoStateLocked(state: Int) {
        when (state) {
            AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                legacyScoRequested = true
                legacyScoConnected = false
            }

            AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                audioManager.isBluetoothScoOn = true
                legacyScoRequested = false
                legacyScoConnected = true
            }

            AudioManager.SCO_AUDIO_STATE_DISCONNECTED,
            AudioManager.SCO_AUDIO_STATE_ERROR -> {
                audioManager.isBluetoothScoOn = false
                legacyScoRequested = false
                legacyScoConnected = false
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun registerLegacyScoReceiverLocked() {
        if (legacyScoReceiverRegistered || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return

        val sticky = ContextCompat.registerReceiver(
            context,
            legacyScoStateReceiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_EXPORTED
        )
        legacyScoReceiverRegistered = true

        val stickyState = sticky?.getIntExtra(
            AudioManager.EXTRA_SCO_AUDIO_STATE,
            AudioManager.SCO_AUDIO_STATE_DISCONNECTED
        ) ?: AudioManager.SCO_AUDIO_STATE_DISCONNECTED

        applyLegacyScoStateLocked(stickyState)
    }

    /**
     * Безотказный запуск маршрутизатора (Zero-Exception Contract).
     *
     * На Android 12–16 (API 31–36) переключение на Bluetooth выполняется реактивно (Event-Driven).
     * Устранён блокирующий синхронный таймаут в 1200 мс, вызывавший ложный срыв запуска звука.
     * Сессия стартует немедленно, а при подтверждении готовности LE Audio / SCO роутер бесшовно
     * переключает профиль через коллбэк [onRouteChange].
     */
    suspend fun start(onRouteChange: (RouteProfile) -> Unit) {
        synchronized(routeLock) {
            if (previousAudioMode == null) {
                previousAudioMode = audioManager.mode
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && previousSpeakerphoneOn == null) {
                @Suppress("DEPRECATION")
                previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
            }

            onRouteChangedListener = onRouteChange

            if (isCallbackRegistered) {
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                isCallbackRegistered = false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isCommunicationListenerRegistered) {
                communicationDeviceListener?.let {
                    runCatching {
                        audioManager.removeOnCommunicationDeviceChangedListener(it)
                    }
                }
                isCommunicationListenerRegistered = false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                registerLegacyScoReceiverLocked()
            }

            routerScope?.cancel()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            routerScope = scope

            scope.launch {
                debounceTrigger
                    .debounce(ROUTE_DEBOUNCE_MS)
                    .collect { evaluateActiveRouteInternal() }
            }

            try {
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
                isCallbackRegistered = true

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    communicationDeviceListener?.let { listener ->
                        audioManager.addOnCommunicationDeviceChangedListener(
                            context.mainExecutor,
                            listener
                        )
                        isCommunicationListenerRegistered = true
                    }
                }

                reconcileRouteBindingLocked()

                val profile = evaluateActiveProfileLocked()

                activeFingerprint = RouteFingerprint(
                    path = profile.path,
                    inDevId = profile.inputDeviceId,
                    outDevId = profile.outputDeviceId,
                    sampleRate = profile.sampleRateOut
                )

                _currentProfile.value = profile

                logger.d(
                    "AudioDeviceRouter: Запущен [Path=${profile.path}, Dev='${profile.deviceName}', OutId=${profile.outputDeviceId}, Rate=${profile.sampleRateOut}Hz]"
                )
            } catch (t: Throwable) {
                runCatching {
                    audioManager.unregisterAudioDeviceCallback(deviceCallback)
                }
                isCallbackRegistered = false

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    isCommunicationListenerRegistered
                ) {
                    communicationDeviceListener?.let {
                        runCatching {
                            audioManager.removeOnCommunicationDeviceChangedListener(it)
                        }
                    }
                    isCommunicationListenerRegistered = false
                }

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
                    legacyScoReceiverRegistered
                ) {
                    runCatching {
                        context.unregisterReceiver(legacyScoStateReceiver)
                    }
                    legacyScoReceiverRegistered = false
                }

                onRouteChangedListener = null
                routerScope = null
                scope.cancel()
                restoreAudioStateLocked()

                logger.e(
                    "AudioDeviceRouter: Сбой первичной инициализации маршрута",
                    t
                )

                throw t
            }
        }
    }

    fun stop() = synchronized(routeLock) {
        if (isCallbackRegistered) {
            runCatching {
                audioManager.unregisterAudioDeviceCallback(deviceCallback)
            }
            isCallbackRegistered = false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            isCommunicationListenerRegistered
        ) {
            communicationDeviceListener?.let {
                runCatching {
                    audioManager.removeOnCommunicationDeviceChangedListener(it)
                }
            }
            isCommunicationListenerRegistered = false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            legacyScoReceiverRegistered
        ) {
            runCatching {
                context.unregisterReceiver(legacyScoStateReceiver)
            }
            legacyScoReceiverRegistered = false
        }

        onRouteChangedListener = null
        routerScope?.cancel()
        routerScope = null
        activeFingerprint = null

        restoreAudioStateLocked()
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioStateLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audioManager.clearCommunicationDevice()
            }
        } else {
            runCatching {
                audioManager.stopBluetoothSco()
            }

            runCatching {
                audioManager.isBluetoothScoOn = false
            }

            previousSpeakerphoneOn?.let { desired ->
                runCatching {
                    audioManager.isSpeakerphoneOn = desired
                }
            }

            legacyScoRequested = false
            legacyScoConnected = false
        }

        previousAudioMode?.let { mode ->
            runCatching {
                audioManager.mode = mode
            }.onFailure {
                logger.w(
                    "AudioDeviceRouter: Не удалось восстановить AudioManager.mode=$mode: ${it.message}"
                )
            }
        }

        previousAudioMode = null
        previousSpeakerphoneOn = null
    }

    private fun selectOptimalBluetoothSampleRate(device: AudioDeviceInfo): Int {
        val supportedRates = device.sampleRates

        if (supportedRates.isEmpty()) {
            return 24000
        }

        return supportedRates
            .filter { it in 16000..48000 }
            .minByOrNull { kotlin.math.abs(it - 24000) }
            ?: supportedRates.minByOrNull { kotlin.math.abs(it - 24000) }
            ?: 24000
    }

    private fun evaluateActiveRouteInternal() = synchronized(routeLock) {
        if (routerScope == null || onRouteChangedListener == null) {
            return@synchronized
        }

        runCatching {
            reconcileRouteBindingLocked()

            val newProfile = evaluateActiveProfileLocked()

            val newFingerprint = RouteFingerprint(
                path = newProfile.path,
                inDevId = newProfile.inputDeviceId,
                outDevId = newProfile.outputDeviceId,
                sampleRate = newProfile.sampleRateOut
            )

            if (activeFingerprint != newFingerprint) {
                activeFingerprint = newFingerprint
                _currentProfile.value = newProfile

                logger.d(
                    "AudioDeviceRouter: Аппаратный маршрут переключён -> ${newProfile.path} [InId=${newProfile.inputDeviceId}, OutId=${newProfile.outputDeviceId}, Rate=${newProfile.sampleRateOut}Hz, Name='${newProfile.deviceName}']"
                )

                onRouteChangedListener?.invoke(newProfile)
            }
        }.onFailure {
            logger.w(
                "AudioDeviceRouter: Ошибка пересчёта маршрута: ${it.message}"
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun evaluateActiveProfileLocked(): RouteProfile {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val commDevices =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPermission) {
                runCatching {
                    audioManager.availableCommunicationDevices
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }

        val allOutputs =
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val btCandidates = if (hasBtPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                commDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                allOutputs.filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }
        } else {
            emptyList()
        }

        val currentCommunication =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    audioManager.communicationDevice
                }.getOrNull()
            } else {
                null
            }

        val btOutputDevice =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                currentCommunication?.takeIf { comm ->
                    comm in btCandidates ||
                        comm.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        comm.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                btCandidates.firstOrNull()
                    .takeIf {
                        legacyScoConnected &&
                            audioManager.isBluetoothScoOn
                    }
            }

        return if (btOutputDevice != null) {
            val sampleRate =
                selectOptimalBluetoothSampleRate(btOutputDevice)

            RouteProfile(
                path = AudioRoutePath.BLUETOOTH_COMMUNICATION,
                sampleRateOut = sampleRate,
                leadInBufferSizeFrames = 260 * 16,
                vadThresholdStart = 0.40f,
                vadThresholdEnd = 0.20f,
                deviceName = btOutputDevice.productName
                    .toString()
                    .ifBlank {
                        "Bluetooth communication device"
                    },
                inputDeviceId = 0,
                outputDeviceId = btOutputDevice.id
            )
        } else {
            createSpeakerProfile()
        }
    }

    @Suppress("DEPRECATION")
    private fun reconcileRouteBindingLocked() {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            hasBtPermission
        ) {
            val candidates = runCatching {
                audioManager.availableCommunicationDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(emptyList())

            val current =
                runCatching {
                    audioManager.communicationDevice
                }.getOrNull()

            val currentIsBt =
                current != null &&
                    (
                        current in candidates ||
                            current.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                            current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        )

            when {
                currentIsBt -> return

                candidates.isNotEmpty() -> {
                    val targetDevice =
                        candidates.firstOrNull {
                            it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        }
                            ?: candidates.firstOrNull {
                                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                            }
                            ?: candidates.first()

                    if (!bindBluetoothCommunication(targetDevice)) {
                        bindSpeakerCommunication()
                    }
                }

                else -> {
                    bindSpeakerCommunication()
                }
            }

            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val candidates = runCatching {
                audioManager
                    .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .filter {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
            }.getOrDefault(emptyList())

            when {
                legacyScoConnected &&
                    audioManager.isBluetoothScoOn -> return

                legacyScoRequested -> return

                candidates.isNotEmpty() -> {
                    if (!bindBluetoothCommunication(candidates.first())) {
                        bindSpeakerCommunication()
                    }
                }

                else -> {
                    bindSpeakerCommunication()
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bindBluetoothCommunication(
        device: AudioDeviceInfo
    ): Boolean = runCatching {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val currentComm = audioManager.communicationDevice

            if (currentComm?.id == device.id) {
                true
            } else {
                audioManager.setCommunicationDevice(device)
            }
        } else {
            if (legacyScoConnected &&
                audioManager.isBluetoothScoOn
            ) {
                return@runCatching true
            }

            if (!legacyScoRequested) {
                val now = SystemClock.elapsedRealtime()

                if (now - lastLegacyScoStartMs >=
                    LEGACY_SCO_RETRY_COOLDOWN_MS
                ) {
                    audioManager.isBluetoothScoOn = true
                    audioManager.startBluetoothSco()

                    legacyScoRequested = true
                    legacyScoConnected = false
                    lastLegacyScoStartMs = now
                }
            }

            true
        }
    }.onFailure {
        logger.w(
            "AudioDeviceRouter: Не удалось привязать Bluetooth-устройство связи: ${it.message}"
        )
    }.getOrDefault(false)

    /**
     * Переключение на встроенный динамик под спецификацию Android 16 (API 36).
     *
     * Устранена ловушка значения null: при вызове clearCommunicationDevice() значение null
     * является стандартным контрактом AOSP Audio Policy и означает 100% успех отката на системный спикер.
     */
    @Suppress("DEPRECATION")
    private fun bindSpeakerCommunication(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }.getOrNull()

            if (speaker != null) {
                val currentComm =
                    runCatching {
                        audioManager.communicationDevice
                    }.getOrNull()

                if (currentComm?.id != speaker.id) {
                    val assigned =
                        runCatching {
                            audioManager.setCommunicationDevice(speaker)
                        }.getOrDefault(false)

                    if (!assigned) {
                        logger.w(
                            "AudioDeviceRouter: setCommunicationDevice(speaker) вернул false; выполняем откат через clearCommunicationDevice()"
                        )

                        runCatching {
                            audioManager.clearCommunicationDevice()
                        }
                    }
                }
            } else {
                // На Samsung One UI / Android 16 сброс к системному дефолту спикера выполняется через clear
                runCatching {
                    audioManager.clearCommunicationDevice()
                }.onFailure {
                    logger.w(
                        "AudioDeviceRouter: clearCommunicationDevice() сбой: ${it.message}"
                    )
                }
            }
        } else {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()

            legacyScoRequested = false
            legacyScoConnected = false

            audioManager.isSpeakerphoneOn = true
        }

        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            }.onFailure {
                logger.w(
                    "AudioDeviceRouter: Смена режима на MODE_IN_COMMUNICATION не удалась: ${it.message}"
                )
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm =
                runCatching {
                    audioManager.communicationDevice
                }.getOrNull()

            // В Android 12–16 null означает активный системный маршрут динамика по умолчанию
            comm == null ||
                comm.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                comm.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        } else {
            audioManager.isSpeakerphoneOn
        }
    }

    private fun createSpeakerProfile(): RouteProfile {
        val allOutputs =
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val allInputs =
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val builtInSpeaker =
            allOutputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
                ?: allOutputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }

        val builtInMic =
            allInputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
            }

        return RouteProfile(
            path = AudioRoutePath.SPEAKER_SHARED,
            sampleRateOut = 48000,
            leadInBufferSizeFrames = 160 * 16,
            vadThresholdStart = 0.50f,
            vadThresholdEnd = 0.25f,
            deviceName = builtInSpeaker
                ?.productName
                ?.toString()
                ?.ifBlank {
                    "Built-in speaker"
                }
                ?: "Built-in speaker",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0
        )
    }
}