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
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Дифференцированные аппаратные пути связи
 * с полной поддержкой проводных (3.5 мм) и USB-C гарнитур.
 */
enum class AudioRoutePath {
    SPEAKER_SHARED,
    BLUETOOTH_SCO,
    BLUETOOTH_BLE_HEADSET,
    WIRED_HEADSET,
    USB_HEADSET,

    // Звук через A2DP (AAC/SBC, стерео 48 кГц), микрофон телефона; без режима связи и без SCO
    BLUETOOTH_A2DP_HIFI,

    @Deprecated("Используйте BLUETOOTH_SCO или BLUETOOTH_BLE_HEADSET для точной настройки")
    BLUETOOTH_COMMUNICATION;

    val isBluetooth: Boolean
        get() = this == BLUETOOTH_SCO || this == BLUETOOTH_BLE_HEADSET || this == BLUETOOTH_COMMUNICATION ||
            this == BLUETOOTH_A2DP_HIFI

    val isHeadset: Boolean
        get() = isBluetooth || this == WIRED_HEADSET || this == USB_HEADSET
}

data class AudioDeviceCapabilities(
    val supportedSampleRates: List<Int>,
    val channelMasks: List<Int>,
    val isLowLatencySupported: Boolean = true
)

data class DeviceQuirks(
    val deviceModel: String,
    val encLatencyMs: Int = 0,
    val acousticErleRatio: Float = 0.20f,
    val echoThreshold: Float = 0.045f,
    val bargeInRequiredStreak: Int = 6,
    val micGainCompensation: Float = 1.0f,
    val preferredSampleRate: Int = 48000
) {
    companion object {
        // Профиль встроенного динамика S23 Ultra с активным аппаратным AEC Qualcomm Fluence
        val DEFAULT_SPEAKER = DeviceQuirks(
            deviceModel = "Built-in Speaker",
            encLatencyMs = 0,
            acousticErleRatio = 0.20f,   // 35-40 дБ подавления аппаратным блоком Hexagon ADSP
            echoThreshold = 0.045f,       // Порог выше остаточного эха, голос пользователя легко преодолевает
            bargeInRequiredStreak = 6,   // 60 мс устойчивого подтверждения речи
            micGainCompensation = 1.0f,
            preferredSampleRate = 48000  // Нативная частота шины кодека WCD9385
        )

        val GENERIC_SCO = DeviceQuirks(
            deviceModel = "Generic Bluetooth SCO (HFP mSBC)",
            encLatencyMs = 25,
            acousticErleRatio = 0.65f,
            echoThreshold = 0.040f,
            bargeInRequiredStreak = 4,
            micGainCompensation = 1.05f,
            preferredSampleRate = 16000
        )

        val GENERIC_BLE = DeviceQuirks(
            deviceModel = "Generic BLE Audio (LC3)",
            encLatencyMs = 15,
            acousticErleRatio = 0.68f,
            echoThreshold = 0.038f,
            bargeInRequiredStreak = 3,
            micGainCompensation = 1.0f,
            preferredSampleRate = 24000
        )

        val CMF_BUDS_2 = DeviceQuirks(
            deviceModel = "CMF Buds 2 (Bestechnic BES2600)",
            encLatencyMs = 35,
            acousticErleRatio = 0.60f,
            echoThreshold = 0.035f,
            bargeInRequiredStreak = 3,
            micGainCompensation = 1.15f,
            preferredSampleRate = 16000
        )

        val GENERIC_WIRED = DeviceQuirks(
            deviceModel = "Wired Headset (3.5mm)",
            encLatencyMs = 0,
            acousticErleRatio = 0.60f,
            echoThreshold = 0.030f,
            bargeInRequiredStreak = 3,
            micGainCompensation = 1.0f,
            preferredSampleRate = 48000
        )

        val GENERIC_USB = DeviceQuirks(
            deviceModel = "USB-C Headset",
            encLatencyMs = 5,
            acousticErleRatio = 0.60f,
            echoThreshold = 0.030f,
            bargeInRequiredStreak = 3,
            micGainCompensation = 1.0f,
            preferredSampleRate = 48000
        )
    }
}

object DeviceProfileRegistry {
    @Suppress("DEPRECATION")
    fun resolveQuirks(device: AudioDeviceInfo?): DeviceQuirks {
        if (device == null) return DeviceQuirks.DEFAULT_SPEAKER
        val name = device.productName.toString().lowercase()

        return when {
            name.contains("cmf") || name.contains("buds 2") || name.contains("nothing") ->
                DeviceQuirks.CMF_BUDS_2
            device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ->
                DeviceQuirks.GENERIC_BLE
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ->
                DeviceQuirks.GENERIC_SCO
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ->
                DeviceQuirks.GENERIC_USB
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                DeviceQuirks.GENERIC_WIRED
            else ->
                DeviceQuirks.DEFAULT_SPEAKER
        }
    }
}

data class RouteProfile(
    val path: AudioRoutePath,
    val targetSampleRate: Int,
    val negotiatedSampleRate: Int = targetSampleRate,
    val deviceName: String,
    val inputDeviceId: Int = 0,
    val outputDeviceId: Int = 0,
    val capabilities: AudioDeviceCapabilities = AudioDeviceCapabilities(emptyList(), emptyList()),
    val quirks: DeviceQuirks = DeviceQuirks.DEFAULT_SPEAKER
) {
    val sampleRateOut: Int get() = targetSampleRate
    val isBluetooth: Boolean get() = path.isBluetooth
}

@OptIn(FlowPreview::class)
@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val routeLock = Any()

    private var routerScope: CoroutineScope? = null

    private var previousAudioMode: Int? = null
    private var previousSpeakerphoneOn: Boolean? = null

    private var legacyScoRequested = false
    private var legacyScoConnected = false
    private var legacyScoReceiverRegistered = false
    private var lastLegacyScoStartMs = 0L

    private var pendingCommunicationDeviceId: Int? = null
    private var pendingCommunicationAcceptSpeakerDefault = false
    private var communicationDeviceConfirmation: CompletableDeferred<AudioDeviceInfo?>? = null
    private var legacyScoConfirmation: CompletableDeferred<Unit>? = null
    private var routeStartInProgress = false
    private var isTransitionInProgress = false

    private var failedDeviceCooldownId: Int? = null
    private var failedDeviceCooldownUntilMs: Long = 0L

    private companion object {
        const val LEGACY_SCO_RETRY_COOLDOWN_MS = 1500L
        const val BT_BIND_FAILURE_COOLDOWN_MS = 4000L
        const val ROUTE_DEBOUNCE_FAST_MS = 40L
        const val TIMEOUT_SPEAKER_MS = 150L
        const val TIMEOUT_COMMUNICATION_MS = 600L
        const val TIMEOUT_LEGACY_SCO_MS = 1500L
    }

    private val debounceTrigger = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    // Hi-Fi Bluetooth: при подключённых A2DP-наушниках звук идёт как медиа, голос — с микрофона телефона
    @Volatile var bluetoothHiFiEnabled: Boolean = true

    fun requestReevaluation() {
        debounceTrigger.tryEmit(Unit)
    }

    private fun findHiFiA2dpOutputLocked(): AudioDeviceInfo? {
        if (!bluetoothHiFiEnabled) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        val outputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }.getOrDefault(emptyList())
        // Проводная/USB гарнитура приоритетнее беспроводной
        if (outputs.any {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        ) {
            return null
        }
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    }

    /** Hi-Fi: режим связи и SCO не нужны — иначе система переведёт наушники в узкополосный HFP. */
    private fun bindHiFiMediaRoute(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            runCatching { audioManager.mode = AudioManager.MODE_NORMAL }
                .onFailure { logger.w("AudioDeviceRouter: Смена режима на MODE_NORMAL не удалась: ${it.message}") }
        }
        return true
    }

    private val _currentProfile = MutableStateFlow(createSpeakerProfile())
    val currentProfile: StateFlow<RouteProfile> = _currentProfile.asStateFlow()

    private var onRouteChangedListener: ((RouteProfile) -> Unit)? = null
    private var isCallbackRegistered = false
    private var isCommunicationListenerRegistered = false

    private val communicationDeviceListener =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AudioManager.OnCommunicationDeviceChangedListener { device ->
                synchronized(routeLock) {
                    val expectedId = pendingCommunicationDeviceId
                    val acceptSpeakerDefault = pendingCommunicationAcceptSpeakerDefault

                    val matchesExpected =
                        (expectedId != null && device?.id == expectedId) ||
                            (acceptSpeakerDefault &&
                                (device == null ||
                                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                                    device.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))

                    if (matchesExpected) {
                        communicationDeviceConfirmation?.complete(device)
                    }
                }

                evaluateActiveRouteImmediate()
                logger.d("AudioDeviceRouter: OnCommunicationDeviceChangedListener -> id=${device?.id}, type=${device?.type}, name=${device?.productName}")
            }
        } else {
            null
        }

    private data class RouteFingerprint(
        val path: AudioRoutePath,
        val inDevId: Int,
        val outDevId: Int,
        val targetSampleRate: Int
    )

    @Volatile private var activeFingerprint: RouteFingerprint? = null

    private fun isRelevantAudioDevice(device: AudioDeviceInfo): Boolean {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> true
            else -> false
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            val hasRelevant = addedDevices?.any { isRelevantAudioDevice(it) } ?: false
            if (hasRelevant) {
                logger.d("AudioDeviceRouter: Discovery -> добавлены аудиоустройства")
                debounceTrigger.tryEmit(Unit)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            val hasRelevant = removedDevices?.any { isRelevantAudioDevice(it) } ?: false
            if (hasRelevant) {
                logger.d("AudioDeviceRouter: Discovery -> отключены аудиоустройства")
                debounceTrigger.tryEmit(Unit)
            }
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
                legacyScoConfirmation?.complete(Unit)
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

    suspend fun start(onRouteChange: (RouteProfile) -> Unit) {
        var scope: CoroutineScope? = null
        var targetCandidate: AudioDeviceInfo? = null

        try {
            synchronized(routeLock) {
                if (previousAudioMode == null) {
                    previousAudioMode = audioManager.mode
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && previousSpeakerphoneOn == null) {
                    @Suppress("DEPRECATION")
                    previousSpeakerphoneOn = audioManager.isSpeakerphoneOn
                }

                onRouteChangedListener = onRouteChange
                routeStartInProgress = true
                isTransitionInProgress = true

                if (isCallbackRegistered) {
                    runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                    isCallbackRegistered = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isCommunicationListenerRegistered) {
                    communicationDeviceListener?.let {
                        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
                    }
                    isCommunicationListenerRegistered = false
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    registerLegacyScoReceiverLocked()
                }

                routerScope?.cancel()
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                routerScope = scope

                scope?.launch {
                    debounceTrigger
                        .debounce(ROUTE_DEBOUNCE_FAST_MS)
                        .collect { evaluateActiveRouteInternal() }
                }

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

                targetCandidate = findPreferredDeviceCandidateLocked()
            }

            if (targetCandidate != null) {
                val target = targetCandidate!!

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val alreadyActive = runCatching {
                        audioManager.communicationDevice?.id == target.id
                    }.getOrDefault(false)

                    val activated = if (alreadyActive) {
                        logger.d("AudioDeviceRouter: Устройство ${target.id} (${target.productName}) уже активно в системе")
                        true
                    } else {
                        val confirmation = CompletableDeferred<AudioDeviceInfo?>()

                        synchronized(routeLock) {
                            communicationDeviceConfirmation = confirmation
                            pendingCommunicationDeviceId = target.id
                            pendingCommunicationAcceptSpeakerDefault = false
                        }

                        val accepted = synchronized(routeLock) {
                            bindCommunicationDevice(target)
                        }

                        val isConfirmed = accepted && awaitCommunicationDeviceActivation(
                            target,
                            confirmation,
                            TIMEOUT_COMMUNICATION_MS
                        )

                        synchronized(routeLock) {
                            pendingCommunicationDeviceId = null
                            pendingCommunicationAcceptSpeakerDefault = false
                            communicationDeviceConfirmation = null
                            if (!isConfirmed && isBluetoothDeviceType(target.type)) {
                                failedDeviceCooldownId = target.id
                                failedDeviceCooldownUntilMs = SystemClock.elapsedRealtime() + BT_BIND_FAILURE_COOLDOWN_MS
                            }
                        }
                        isConfirmed
                    }

                    // Интервал стабилизации HAL для аппаратного включения AEC WCD9385
                    if (activated) {
                        delay(60L)
                    }

                    if (!activated) {
                        logger.w("AudioDeviceRouter: Маршрут ${target.id} не подтвержден; откат на встроенный динамик")
                        awaitSpeakerFallback()
                    }
                } else {
                    if (target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        val alreadyActive = legacyScoConnected && audioManager.isBluetoothScoOn
                        val activated = if (alreadyActive) {
                            true
                        } else {
                            val confirmation = CompletableDeferred<Unit>()
                            synchronized(routeLock) { legacyScoConfirmation = confirmation }
                            val requested = synchronized(routeLock) { bindCommunicationDevice(target) }
                            val isConfirmed = requested && awaitLegacyScoActivation(confirmation, TIMEOUT_LEGACY_SCO_MS)
                            synchronized(routeLock) {
                                legacyScoConfirmation = null
                                if (!isConfirmed) {
                                    failedDeviceCooldownId = target.id
                                    failedDeviceCooldownUntilMs = SystemClock.elapsedRealtime() + BT_BIND_FAILURE_COOLDOWN_MS
                                }
                            }
                            isConfirmed
                        }

                        if (activated) {
                            delay(60L)
                        }

                        if (!activated) {
                            logger.w("AudioDeviceRouter: Legacy SCO не подтвержден; откат на динамик")
                            synchronized(routeLock) { bindSpeakerCommunication() }
                        }
                    } else {
                        synchronized(routeLock) { bindCommunicationDevice(target) }
                    }
                }
            } else {
                synchronized(routeLock) {
                    bindSpeakerCommunication()
                }
            }

            synchronized(routeLock) {
                val profile = evaluateActiveProfileLocked()
                activeFingerprint = RouteFingerprint(
                    path = profile.path,
                    inDevId = profile.inputDeviceId,
                    outDevId = profile.outputDeviceId,
                    targetSampleRate = profile.targetSampleRate
                )
                _currentProfile.value = profile
                routeStartInProgress = false
                isTransitionInProgress = false
                logger.d(
                    "AudioDeviceRouter: Маршрут подтвержден -> " +
                        "[Path=${profile.path}, Dev='${profile.deviceName}', " +
                        "OutId=${profile.outputDeviceId}, Rate=${profile.targetSampleRate}Hz, Model='${profile.quirks.deviceModel}']"
                )
            }
        } catch (t: Throwable) {
            synchronized(routeLock) {
                routeStartInProgress = false
                isTransitionInProgress = false
                pendingCommunicationDeviceId = null
                pendingCommunicationAcceptSpeakerDefault = false
                communicationDeviceConfirmation = null
                legacyScoConfirmation = null
            }

            synchronized(routeLock) {
                if (isCallbackRegistered) {
                    runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                    isCallbackRegistered = false
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isCommunicationListenerRegistered) {
                    communicationDeviceListener?.let {
                        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
                    }
                    isCommunicationListenerRegistered = false
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && legacyScoReceiverRegistered) {
                    runCatching { context.unregisterReceiver(legacyScoStateReceiver) }
                    legacyScoReceiverRegistered = false
                }
                onRouteChangedListener = null
                routerScope = null
                scope?.cancel()
                restoreAudioStateLocked()
            }

            logger.e("AudioDeviceRouter: Сбой синхронизированного запуска маршрута", t)
            throw t
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitCommunicationDeviceActivation(
        targetDevice: AudioDeviceInfo,
        confirmation: CompletableDeferred<AudioDeviceInfo?>,
        timeoutMs: Long
    ): Boolean {
        val alreadyActive = runCatching {
            audioManager.communicationDevice?.id == targetDevice.id
        }.getOrDefault(false)

        if (alreadyActive) return true

        val signaled = withTimeoutOrNull(timeoutMs) {
            confirmation.await()
            true
        } ?: false

        if (!signaled) return false

        return runCatching {
            audioManager.communicationDevice?.id == targetDevice.id
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitLegacyScoActivation(
        confirmation: CompletableDeferred<Unit>,
        timeoutMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return false

        if (legacyScoConnected && audioManager.isBluetoothScoOn) {
            return true
        }

        val signaled = withTimeoutOrNull(timeoutMs) {
            confirmation.await()
            true
        } ?: false

        return signaled && legacyScoConnected && audioManager.isBluetoothScoOn
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitSpeakerFallback(): Boolean {
        val speaker = runCatching {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
        }.getOrNull()

        if (speaker == null) {
            synchronized(routeLock) {
                runCatching { audioManager.clearCommunicationDevice() }
            }
            return runCatching {
                audioManager.communicationDevice == null
            }.getOrDefault(false)
        }

        val confirmation = CompletableDeferred<AudioDeviceInfo?>()
        synchronized(routeLock) {
            communicationDeviceConfirmation = confirmation
            pendingCommunicationDeviceId = speaker.id
            pendingCommunicationAcceptSpeakerDefault = true
        }

        val accepted = synchronized(routeLock) {
            bindSpeakerCommunication()
        }

        val alreadyActive = runCatching {
            val current = audioManager.communicationDevice
            current == null ||
                current.id == speaker.id ||
                current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                current.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }.getOrDefault(false)

        val confirmed =
            if (alreadyActive) {
                true
            } else if (accepted) {
                withTimeoutOrNull(TIMEOUT_SPEAKER_MS) {
                    confirmation.await()
                    true
                } ?: false
            } else {
                false
            }

        val finalSpeaker = runCatching {
            val current = audioManager.communicationDevice
            current == null ||
                current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                current.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        }.getOrDefault(false)

        synchronized(routeLock) {
            pendingCommunicationDeviceId = null
            pendingCommunicationAcceptSpeakerDefault = false
            communicationDeviceConfirmation = null
        }

        if (!confirmed && !finalSpeaker) {
            synchronized(routeLock) {
                runCatching { audioManager.clearCommunicationDevice() }
            }
        }

        return confirmed || finalSpeaker
    }

    private fun rankDeviceCandidate(device: AudioDeviceInfo): Int {
        val name = device.productName.toString().lowercase()
        val isWatch = name.contains("watch") || name.contains("gear") || name.contains("band")
        val isTargetHeadset = name.contains("cmf") || name.contains("buds") || name.contains("headset") ||
            name.contains("ear") || name.contains("airpods")

        var score = 0
        when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> score += 100
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> score += 70
            AudioDeviceInfo.TYPE_USB_HEADSET -> score += 90
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> score += 80
            else -> score += 10
        }

        if (isTargetHeadset) score += 40
        if (isWatch) score -= 80

        return score
    }

    private fun findPreferredDeviceCandidateLocked(): AudioDeviceInfo? {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val now = SystemClock.elapsedRealtime()

        // Hi-Fi: Bluetooth не назначается устройством связи (иначе включится HFP/SCO)
        if (findHiFiA2dpOutputLocked() != null) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val current = runCatching { audioManager.communicationDevice }.getOrNull()
            if (current != null && (
                    current.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        current.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                        current.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                )) {
                return current
            }

            val candidates = runCatching {
                audioManager.availableCommunicationDevices.filter { dev ->
                    val isBt = isBluetoothDeviceType(dev.type)
                    val isWired = dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        dev.type == AudioDeviceInfo.TYPE_USB_HEADSET

                    ((isBt && hasBtPermission) || isWired) &&
                        (failedDeviceCooldownId != dev.id || now >= failedDeviceCooldownUntilMs)
                }
            }.getOrDefault(emptyList())

            return candidates.maxByOrNull { rankDeviceCandidate(it) }
        } else {
            val allOutputs = runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }.getOrDefault(emptyList())

            val candidates = allOutputs.filter { dev ->
                val isBt = dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                val isWired = dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET

                ((isBt && hasBtPermission) || isWired) &&
                    (failedDeviceCooldownId != dev.id || now >= failedDeviceCooldownUntilMs)
            }

            return candidates.maxByOrNull { rankDeviceCandidate(it) }
        }
    }

    fun stop() = synchronized(routeLock) {
        if (isCallbackRegistered) {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            isCallbackRegistered = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isCommunicationListenerRegistered) {
            communicationDeviceListener?.let { runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) } }
            isCommunicationListenerRegistered = false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && legacyScoReceiverRegistered) {
            runCatching { context.unregisterReceiver(legacyScoStateReceiver) }
            legacyScoReceiverRegistered = false
        }

        onRouteChangedListener = null
        routerScope?.cancel()
        routerScope = null
        activeFingerprint = null
        routeStartInProgress = false
        isTransitionInProgress = false
        pendingCommunicationDeviceId = null
        pendingCommunicationAcceptSpeakerDefault = false
        communicationDeviceConfirmation = null
        legacyScoConfirmation = null
        failedDeviceCooldownId = null
        failedDeviceCooldownUntilMs = 0L

        restoreAudioStateLocked()
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioStateLocked() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        } else {
            runCatching { audioManager.stopBluetoothSco() }
            runCatching { audioManager.isBluetoothScoOn = false }
            previousSpeakerphoneOn?.let { desired ->
                runCatching { audioManager.isSpeakerphoneOn = desired }
            }
            legacyScoRequested = false
            legacyScoConnected = false
        }

        previousAudioMode?.let { mode ->
            runCatching { audioManager.mode = mode }
                .onFailure {
                    logger.w("AudioDeviceRouter: Не удалось восстановить AudioManager.mode=$mode: ${it.message}")
                }
        }
        previousAudioMode = null
        previousSpeakerphoneOn = null
    }

    private fun routerStartInProgressOrUninitialized(): Boolean =
        routerScope == null ||
            onRouteChangedListener == null ||
            routeStartInProgress ||
            isTransitionInProgress

    private fun selectOptimalBluetoothSampleRate(device: AudioDeviceInfo): Int {
        val supportedRates = device.sampleRates
        val validRates = supportedRates.filter { it in 8000..48000 }

        return when (device.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                if (validRates.contains(16000)) 16000
                else if (validRates.isNotEmpty()) validRates.minByOrNull { kotlin.math.abs(it - 16000) } ?: 16000
                else 16000
            }
            AudioDeviceInfo.TYPE_BLE_HEADSET -> {
                if (validRates.contains(24000)) 24000
                else if (validRates.contains(32000)) 32000
                else if (validRates.isNotEmpty()) validRates.minByOrNull { kotlin.math.abs(it - 24000) } ?: 24000
                else 24000
            }
            else -> {
                if (validRates.isNotEmpty()) validRates.minByOrNull { kotlin.math.abs(it - 24000) } ?: 48000
                else 48000
            }
        }
    }

    private fun evaluateActiveRouteImmediate() {
        routerScope?.launch {
            evaluateActiveRouteInternal()
        }
    }

    private fun evaluateActiveRouteInternal() = synchronized(routeLock) {
        if (routerStartInProgressOrUninitialized()) return@synchronized

        runCatching {
            reconcileRouteBindingLocked()
            val newProfile = evaluateActiveProfileLocked()
            val newFingerprint = RouteFingerprint(
                path = newProfile.path,
                inDevId = newProfile.inputDeviceId,
                outDevId = newProfile.outputDeviceId,
                targetSampleRate = newProfile.targetSampleRate
            )

            if (activeFingerprint != newFingerprint) {
                activeFingerprint = newFingerprint
                _currentProfile.value = newProfile
                logger.d("AudioDeviceRouter: Аппаратный маршрут переключён -> ${newProfile.path} [InId=${newProfile.inputDeviceId}, OutId=${newProfile.outputDeviceId}, Rate=${newProfile.targetSampleRate}Hz, Name='${newProfile.deviceName}', Quirks='${newProfile.quirks.deviceModel}']")
                onRouteChangedListener?.invoke(newProfile)
            }
        }.onFailure {
            logger.w("AudioDeviceRouter: Ошибка пересчёта маршрута: ${it.message}")
        }
    }

    private fun isBluetoothDeviceType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
    }

    @Suppress("DEPRECATION")
    fun isInputDeviceMatchingRoute(
        profile: RouteProfile,
        actualInputDeviceId: Int
    ): Boolean {
        if (actualInputDeviceId <= 0) {
            logger.e("AudioDeviceRouter: Невалидный фактический ID входного устройства=$actualInputDeviceId")
            return false
        }

        val inputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrElse {
            logger.e("AudioDeviceRouter: Не удалось перечислить входные аудиоустройства", it)
            return false
        }

        val actualInput = inputs.firstOrNull { it.id == actualInputDeviceId }
        if (actualInput == null) {
            logger.w("AudioDeviceRouter: Входное устройство ID=$actualInputDeviceId не найдено среди системных портов")
            return false
        }

        return when (profile.path) {
            AudioRoutePath.BLUETOOTH_SCO -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    logger.w("AudioDeviceRouter: Ожидался вход BLUETOOTH_SCO, но физический порт ID=$actualInputDeviceId имеет тип ${actualInput.type} (${actualInput.productName})")
                    return false
                }
                true
            }

            AudioRoutePath.BLUETOOTH_BLE_HEADSET -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    logger.w("AudioDeviceRouter: Ожидался вход BLE_HEADSET, но физический порт ID=$actualInputDeviceId имеет тип ${actualInput.type} (${actualInput.productName})")
                    return false
                }
                true
            }

            AudioRoutePath.BLUETOOTH_COMMUNICATION -> {
                if (!isBluetoothDeviceType(actualInput.type)) {
                    logger.w("AudioDeviceRouter: Ожидался Bluetooth порт, но получен тип ${actualInput.type}")
                    return false
                }
                true
            }

            AudioRoutePath.WIRED_HEADSET -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_WIRED_HEADSET && actualInput.type != AudioDeviceInfo.TYPE_USB_HEADSET) {
                    logger.w("AudioDeviceRouter: Ожидался вход проводной гарнитуры, но получен тип ${actualInput.type}")
                    return false
                }
                true
            }

            AudioRoutePath.USB_HEADSET -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_USB_HEADSET && actualInput.type != AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                    logger.w("AudioDeviceRouter: Ожидался вход USB-C гарнитуры, но получен тип ${actualInput.type}")
                    return false
                }
                true
            }

            AudioRoutePath.BLUETOOTH_A2DP_HIFI -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    logger.w("AudioDeviceRouter: Hi-Fi ожидает микрофон телефона, получен тип ${actualInput.type}")
                    return false
                }
                true
            }

            AudioRoutePath.SPEAKER_SHARED -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    logger.e("AudioDeviceRouter: SPEAKER_SHARED разрешился в не-встроенный микрофон: id=${actualInput.id}, тип=${actualInput.type}")
                    return false
                }
                true
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun evaluateActiveProfileLocked(): RouteProfile {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

        findHiFiA2dpOutputLocked()?.let { a2dp ->
            val builtinMic = allInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            return RouteProfile(
                path = AudioRoutePath.BLUETOOTH_A2DP_HIFI,
                targetSampleRate = 48000,
                negotiatedSampleRate = 48000,
                deviceName = a2dp.productName.toString().ifBlank { "Bluetooth Hi-Fi" },
                inputDeviceId = builtinMic?.id ?: 0,
                outputDeviceId = a2dp.id,
                capabilities = AudioDeviceCapabilities(
                    supportedSampleRates = a2dp.sampleRates.toList(),
                    channelMasks = a2dp.channelMasks.toList(),
                    isLowLatencySupported = false
                ),
                // Микрофон телефона: компенсация усиления гарнитуры не применяется
                quirks = DeviceQuirks.DEFAULT_SPEAKER
            )
        }

        val currentCommunication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice }.getOrNull()
        } else {
            null
        }

        val activeOutputDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            currentCommunication ?: commDevices.firstOrNull { dev ->
                isBluetoothDeviceType(dev.type) && hasBtPermission ||
                    dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
        } else {
            val btCandidate = allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                ?.takeIf { legacyScoConnected && audioManager.isBluetoothScoOn }
            val wiredCandidate = allOutputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            btCandidate ?: wiredCandidate
        }

        if (activeOutputDevice != null) {
            val routePath = when (activeOutputDevice.type) {
                AudioDeviceInfo.TYPE_BLE_HEADSET -> AudioRoutePath.BLUETOOTH_BLE_HEADSET
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> AudioRoutePath.BLUETOOTH_SCO
                AudioDeviceInfo.TYPE_USB_HEADSET -> AudioRoutePath.USB_HEADSET
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> AudioRoutePath.WIRED_HEADSET
                else -> AudioRoutePath.SPEAKER_SHARED
            }

            if (routePath != AudioRoutePath.SPEAKER_SHARED) {
                // Единый путь 24→48 кГц (полуполосный КИХ); к SCO/LC3 ресемплирует AudioFlinger
                val sampleRate = 48000

                val matchedInput = allInputs.firstOrNull { inDev ->
                    when (routePath) {
                        AudioRoutePath.BLUETOOTH_BLE_HEADSET -> inDev.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                        AudioRoutePath.BLUETOOTH_SCO -> inDev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        AudioRoutePath.USB_HEADSET -> inDev.type == AudioDeviceInfo.TYPE_USB_HEADSET
                        AudioRoutePath.WIRED_HEADSET -> inDev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                        else -> false
                    }
                }

                val quirks = DeviceProfileRegistry.resolveQuirks(activeOutputDevice)
                val capabilities = AudioDeviceCapabilities(
                    supportedSampleRates = activeOutputDevice.sampleRates.toList(),
                    channelMasks = activeOutputDevice.channelMasks.toList(),
                    isLowLatencySupported = true
                )

                return RouteProfile(
                    path = routePath,
                    targetSampleRate = sampleRate,
                    negotiatedSampleRate = sampleRate,
                    deviceName = activeOutputDevice.productName.toString().ifBlank { routePath.name },
                    inputDeviceId = matchedInput?.id ?: 0,
                    outputDeviceId = activeOutputDevice.id,
                    capabilities = capabilities,
                    quirks = quirks
                )
            }
        }

        return createSpeakerProfile()
    }

    @Suppress("DEPRECATION")
    private fun reconcileRouteBindingLocked() {
        val candidate = findPreferredDeviceCandidateLocked()
        if (candidate != null) {
            val currentComm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { audioManager.communicationDevice }.getOrNull()
            } else {
                null
            }

            if (currentComm?.id != candidate.id) {
                if (!bindCommunicationDevice(candidate)) {
                    bindSpeakerCommunication()
                }
            }
        } else {
            bindSpeakerCommunication()
        }
    }

    @Suppress("DEPRECATION")
    private fun bindCommunicationDevice(device: AudioDeviceInfo): Boolean = runCatching {
        // УСТРАНЕНИЕ СРЫВА АППАРАТНОГО AEC: Режим MODE_IN_COMMUNICATION активируется ПЕРВЫМ
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
            if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                if (legacyScoConnected && audioManager.isBluetoothScoOn) {
                    return@runCatching true
                }

                if (!legacyScoRequested) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLegacyScoStartMs >= LEGACY_SCO_RETRY_COOLDOWN_MS) {
                        audioManager.isBluetoothScoOn = true
                        audioManager.startBluetoothSco()
                        legacyScoRequested = true
                        legacyScoConnected = false
                        lastLegacyScoStartMs = now
                    }
                }
                true
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.isSpeakerphoneOn = false
                true
            }
        }
    }.onFailure {
        logger.w("AudioDeviceRouter: Не удалось привязать устройство связи ${device.id}: ${it.message}")
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun bindSpeakerCommunication(): Boolean {
        if (findHiFiA2dpOutputLocked() != null) return bindHiFiMediaRoute()

        // УСТРАНЕНИЕ СРЫВА АППАРАТНОГО AEC: Перевод AudioManager в режим MODE_IN_COMMUNICATION
        // строго ДО вызова setCommunicationDevice(), чтобы AudioPolicyService сразу скоммутировал
        // аппаратный петлевой порт ECHO_REFERENCE для подавителя эха WCD9385.
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { logger.w("AudioDeviceRouter: Смена режима на MODE_IN_COMMUNICATION не удалась: ${it.message}") }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }.getOrNull()

            if (speaker != null) {
                val currentComm = runCatching { audioManager.communicationDevice }.getOrNull()
                if (currentComm?.id != speaker.id) {
                    val assigned = runCatching { audioManager.setCommunicationDevice(speaker) }.getOrDefault(false)
                    if (!assigned) {
                        logger.w("AudioDeviceRouter: setCommunicationDevice(speaker) вернул false; откат через clearCommunicationDevice()")
                        runCatching { audioManager.clearCommunicationDevice() }
                    }
                }
            } else {
                runCatching { audioManager.clearCommunicationDevice() }
                    .onFailure {
                        logger.w("AudioDeviceRouter: clearCommunicationDevice() сбой: ${it.message}")
                    }
            }
        } else {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            legacyScoRequested = false
            legacyScoConnected = false
            audioManager.isSpeakerphoneOn = true
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val comm = runCatching { audioManager.communicationDevice }.getOrNull()
            comm == null ||
                comm.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                comm.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        } else {
            audioManager.isSpeakerphoneOn
        }
    }

    private fun createSpeakerProfile(): RouteProfile {
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val builtInSpeaker = allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?: allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        val builtInMic = allInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        val nativeSampleRate = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            ?.toIntOrNull() ?: 48000

        val capabilities = AudioDeviceCapabilities(
            supportedSampleRates = builtInSpeaker?.sampleRates?.toList() ?: listOf(48000),
            channelMasks = builtInSpeaker?.channelMasks?.toList() ?: emptyList(),
            isLowLatencySupported = true
        )

        return RouteProfile(
            path = AudioRoutePath.SPEAKER_SHARED,
            targetSampleRate = nativeSampleRate,
            negotiatedSampleRate = nativeSampleRate,
            deviceName = builtInSpeaker?.productName?.toString()?.ifBlank { "Built-in speaker" } ?: "Built-in speaker",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0,
            capabilities = capabilities,
            quirks = DeviceQuirks.DEFAULT_SPEAKER
        )
    }
}