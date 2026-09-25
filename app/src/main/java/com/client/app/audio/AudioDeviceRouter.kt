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

    private companion object {
        const val LEGACY_SCO_RETRY_COOLDOWN_MS = 1500L
        const val ROUTE_DEBOUNCE_MS = 180L
        const val COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS = 800L
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

    @Volatile private var activeFingerprint: RouteFingerprint? = null

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
        var targetBtCandidate: AudioDeviceInfo? = null

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
                        .debounce(ROUTE_DEBOUNCE_MS)
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

                targetBtCandidate = findPreferredBluetoothCandidateLocked()
            }

            if (targetBtCandidate != null) {
                val target = targetBtCandidate!!

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val confirmation = CompletableDeferred<AudioDeviceInfo?>()

                    synchronized(routeLock) {
                        communicationDeviceConfirmation = confirmation
                        pendingCommunicationDeviceId = target.id
                        pendingCommunicationAcceptSpeakerDefault = false
                    }

                    val accepted = synchronized(routeLock) {
                        bindBluetoothCommunication(target)
                    }

                    val activated =
                        accepted &&
                            awaitCommunicationDeviceActivation(
                                target,
                                confirmation,
                                COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS
                            )

                    synchronized(routeLock) {
                        pendingCommunicationDeviceId = null
                        pendingCommunicationAcceptSpeakerDefault = false
                        communicationDeviceConfirmation = null
                    }

                    if (!activated) {
                        logger.w(
                            "AudioDeviceRouter: Bluetooth communication route ${target.id} " +
                                "was not confirmed within ${COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS} ms; " +
                                "falling back to speaker"
                        )
                        awaitSpeakerFallback()
                    }
                } else {
                    val confirmation = CompletableDeferred<Unit>()

                    synchronized(routeLock) {
                        legacyScoConfirmation = confirmation
                    }

                    val requested = synchronized(routeLock) {
                        bindBluetoothCommunication(target)
                    }

                    val activated =
                        requested &&
                            awaitLegacyScoActivation(
                                confirmation,
                                COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS
                            )

                    synchronized(routeLock) {
                        legacyScoConfirmation = null
                    }

                    if (!activated) {
                        logger.w(
                            "AudioDeviceRouter: Bluetooth SCO was not confirmed within " +
                                "${COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS} ms; falling back to speaker"
                        )
                        synchronized(routeLock) {
                            bindSpeakerCommunication()
                        }
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
                    sampleRate = profile.sampleRateOut
                )
                _currentProfile.value = profile
                routeStartInProgress = false
                logger.d(
                    "AudioDeviceRouter: Запущен после подтверждения маршрута -> " +
                        "[Path=${profile.path}, Dev='${profile.deviceName}', " +
                        "OutId=${profile.outputDeviceId}, Rate=${profile.sampleRateOut}Hz]"
                )
            }
        } catch (t: Throwable) {
            synchronized(routeLock) {
                routeStartInProgress = false
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

        val signaled =
            withTimeoutOrNull(timeoutMs) {
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

        val signaled =
            withTimeoutOrNull(timeoutMs) {
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
                withTimeoutOrNull(COMMUNICATION_ROUTE_CONFIRM_TIMEOUT_MS) {
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

    private fun findPreferredBluetoothCandidateLocked(): AudioDeviceInfo? {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasBtPermission) return null

        val candidates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                audioManager.availableCommunicationDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }.getOrDefault(emptyList())
        } else {
            runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                }
            }.getOrDefault(emptyList())
        }

        return candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
            ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: candidates.firstOrNull()
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
        pendingCommunicationDeviceId = null
        pendingCommunicationAcceptSpeakerDefault = false
        communicationDeviceConfirmation = null
        legacyScoConfirmation = null

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
            routeStartInProgress

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
        if (routerStartInProgressOrUninitialized()) return@synchronized

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
                logger.d("AudioDeviceRouter: Аппаратный маршрут переключён -> ${newProfile.path} [InId=${newProfile.inputDeviceId}, OutId=${newProfile.outputDeviceId}, Rate=${newProfile.sampleRateOut}Hz, Name='${newProfile.deviceName}']")
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

    /**
     * Валидация фактического входного порта, открытого драйвером AAudio.
     */
    fun isInputDeviceMatchingRoute(
        profile: RouteProfile,
        actualInputDeviceId: Int
    ): Boolean {
        if (actualInputDeviceId <= 0) {
            logger.e("AudioDeviceRouter: invalid actual input device ID=$actualInputDeviceId")
            return false
        }

        val inputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        }.getOrElse {
            logger.e("AudioDeviceRouter: failed to enumerate input devices", it)
            return false
        }

        val actualInput = inputs.firstOrNull { it.id == actualInputDeviceId }
        if (actualInput == null) {
            logger.e("AudioDeviceRouter: actual input device $actualInputDeviceId is not present in AudioManager")
            return false
        }

        return when (profile.path) {
            AudioRoutePath.BLUETOOTH_COMMUNICATION -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val communicationDevice = runCatching { audioManager.communicationDevice }.getOrNull()
                    if (communicationDevice == null) {
                        logger.w("AudioDeviceRouter: Bluetooth capture validation failed: communicationDevice == null")
                        return false
                    }

                    if (communicationDevice.id != profile.outputDeviceId) {
                        logger.w("AudioDeviceRouter: communication sink mismatch: expected=${profile.outputDeviceId}, actual=${communicationDevice.id}")
                        return false
                    }

                    if (!isBluetoothDeviceType(communicationDevice.type)) {
                        logger.w("AudioDeviceRouter: communication sink is not Bluetooth")
                        return false
                    }

                    if (!isBluetoothDeviceType(actualInput.type)) {
                        logger.e("AudioDeviceRouter: Bluetooth route resolved to non-BT input: id=${actualInput.id}, type=${actualInput.type}")
                        return false
                    }

                    if (profile.inputDeviceId > 0 && actualInputDeviceId != profile.inputDeviceId) {
                        logger.e("AudioDeviceRouter: explicit BT input mismatch: expected=${profile.inputDeviceId}, actual=$actualInputDeviceId")
                        return false
                    }
                    true
                } else {
                    val scoActive = synchronized(routeLock) {
                        @Suppress("DEPRECATION")
                        legacyScoConnected && audioManager.isBluetoothScoOn
                    }
                    if (!scoActive) {
                        logger.w("AudioDeviceRouter: legacy Bluetooth SCO is not active")
                        return false
                    }

                    if (actualInput.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        logger.e("AudioDeviceRouter: legacy SCO route resolved to non-SCO input")
                        return false
                    }

                    if (profile.inputDeviceId > 0 && actualInputDeviceId != profile.inputDeviceId) {
                        logger.e("AudioDeviceRouter: legacy SCO input mismatch: expected=${profile.inputDeviceId}, actual=$actualInputDeviceId")
                        return false
                    }
                    true
                }
            }

            AudioRoutePath.SPEAKER_SHARED -> {
                if (actualInput.type != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    logger.e("AudioDeviceRouter: SPEAKER_SHARED resolved to non-built-in input: id=${actualInput.id}, type=${actualInput.type}")
                    return false
                }

                if (profile.inputDeviceId > 0 && actualInputDeviceId != profile.inputDeviceId) {
                    logger.e("AudioDeviceRouter: built-in microphone mismatch: expected=${profile.inputDeviceId}, actual=$actualInputDeviceId")
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

        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPermission) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        val btCandidates = if (hasBtPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                commDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                allOutputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            }
        } else {
            emptyList()
        }

        val currentCommunication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.communicationDevice }.getOrNull()
        } else {
            null
        }

        val btOutputDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            currentCommunication?.takeIf { comm ->
                comm in btCandidates ||
                    comm.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    comm.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            btCandidates.firstOrNull().takeIf { legacyScoConnected && audioManager.isBluetoothScoOn }
        }

        return if (btOutputDevice != null) {
            val sampleRate = selectOptimalBluetoothSampleRate(btOutputDevice)
            RouteProfile(
                path = AudioRoutePath.BLUETOOTH_COMMUNICATION,
                sampleRateOut = sampleRate,
                leadInBufferSizeFrames = 260 * 16,
                vadThresholdStart = 0.40f,
                vadThresholdEnd = 0.20f,
                deviceName = btOutputDevice.productName.toString().ifBlank { "Bluetooth communication device" },
                inputDeviceId = 0, // AudioPolicy автоматически привязывает source к communication sink
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPermission) {
            val candidates = runCatching {
                audioManager.availableCommunicationDevices.filter {
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(emptyList())

            val current = runCatching { audioManager.communicationDevice }.getOrNull()
            val currentIsBt = current != null && (
                current in candidates ||
                    current.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    current.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            )

            when {
                currentIsBt -> return
                candidates.isNotEmpty() -> {
                    val targetDevice = candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                        ?: candidates.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
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
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }.getOrDefault(emptyList())

            when {
                legacyScoConnected && audioManager.isBluetoothScoOn -> return
                legacyScoRequested -> return
                candidates.isNotEmpty() -> {
                    if (!bindBluetoothCommunication(candidates.first())) {
                        bindSpeakerCommunication()
                    }
                }
                else -> bindSpeakerCommunication()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun bindBluetoothCommunication(device: AudioDeviceInfo): Boolean = runCatching {
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
        }
    }.onFailure {
        logger.w("AudioDeviceRouter: Не удалось привязать Bluetooth-устройство связи: ${it.message}")
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun bindSpeakerCommunication(): Boolean {
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
                        logger.w("AudioDeviceRouter: setCommunicationDevice(speaker) вернул false; выполняем откат через clearCommunicationDevice()")
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

        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { logger.w("AudioDeviceRouter: Смена режима на MODE_IN_COMMUNICATION не удалась: ${it.message}") }
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

        return RouteProfile(
            path = AudioRoutePath.SPEAKER_SHARED,
            sampleRateOut = 48000,
            leadInBufferSizeFrames = 160 * 16,
            vadThresholdStart = 0.50f,
            vadThresholdEnd = 0.25f,
            deviceName = builtInSpeaker?.productName?.toString()?.ifBlank { "Built-in speaker" } ?: "Built-in speaker",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0
        )
    }
}
