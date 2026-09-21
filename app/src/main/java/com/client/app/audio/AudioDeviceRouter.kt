package com.client.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
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
                logger.d("AudioDeviceRouter: communication device changed -> ${device?.id}")
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

    suspend fun start(onRouteChange: (RouteProfile) -> Unit) {
        val initialProfile = synchronized(routeLock) {
            onRouteChangedListener = onRouteChange

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
            routerScope?.cancel()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            routerScope = scope

            scope.launch {
                debounceTrigger
                    .debounce(180L)
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
                profile
            } catch (t: Throwable) {
                runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
                isCallbackRegistered = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isCommunicationListenerRegistered) {
                    communicationDeviceListener?.let {
                        runCatching { audioManager.removeOnCommunicationDeviceChangedListener(it) }
                    }
                    isCommunicationListenerRegistered = false
                }
                onRouteChangedListener = null
                routerScope = null
                scope.cancel()
                logger.e("AudioDeviceRouter: initial route evaluation failed", t)
                throw t
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            initialProfile.outputDeviceId > 0) {
            val confirmed = awaitCommunicationDevice(
                initialProfile.outputDeviceId,
                timeoutMs = 1200L
            )
            if (!confirmed && initialProfile.path == AudioRoutePath.BLUETOOTH_COMMUNICATION) {
                synchronized(routeLock) {
                    runCatching { audioManager.clearCommunicationDevice() }
                    activeFingerprint = null
                    onRouteChangedListener = null
                    routerScope?.cancel()
                    routerScope = null
                }
                throw IllegalStateException(
                    "Audio communication route did not reach requested device ${initialProfile.outputDeviceId}"
                )
            }
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
        onRouteChangedListener = null
        routerScope?.cancel()
        routerScope = null
        activeFingerprint = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        runCatching {
            if (audioManager.mode != AudioManager.MODE_NORMAL) {
                audioManager.mode = AudioManager.MODE_NORMAL
            }
        }.onFailure {
            logger.w("AudioDeviceRouter: не удалось вернуть AudioManager.MODE_NORMAL: ${it.message}")
        }
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

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun awaitCommunicationDevice(
        expectedDeviceId: Int,
        timeoutMs: Long
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val current = runCatching { audioManager.communicationDevice?.id }.getOrNull()
            if (current == expectedDeviceId) return true
            delay(25L)
        }
        return runCatching { audioManager.communicationDevice?.id == expectedDeviceId }.getOrDefault(false)
    }

    private fun evaluateActiveRouteInternal() = synchronized(routeLock) {
        if (routerScope == null || onRouteChangedListener == null) return@synchronized

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
                logger.d("AudioDeviceRouter: Аппаратный профиль зафиксирован -> ${newProfile.path} [InId=${newProfile.inputDeviceId}, OutId=${newProfile.outputDeviceId}, Rate=${newProfile.sampleRateOut}Hz]")
                onRouteChangedListener?.invoke(newProfile)
            }
        }.onFailure {
            logger.w("AudioDeviceRouter: ошибка проверки маршрута: ${it.message}")
        }
    }

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
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val btCandidates = if (hasBtPermission) {
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) commDevices else allOutputs.toList())
                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
        } else emptyList()

        val currentCommunication = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager.communicationDevice else null
        val btOutputDevice = currentCommunication?.takeIf { it in btCandidates }
            ?: if (currentCommunication == null) btCandidates.singleOrNull() else null

        if (currentCommunication != null && currentCommunication !in btCandidates &&
            currentCommunication.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            logger.w("AudioDeviceRouter: current communication device is no longer in the available communication set")
        }

        val btInputDevice = if (hasBtPermission) {
            allInputs.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            null
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
                inputDeviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0 else btInputDevice?.id ?: 0,
                outputDeviceId = btOutputDevice.id
            )
        } else {
            createSpeakerProfile()
        }
    }

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
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
            }.getOrDefault(emptyList())

            val current = runCatching { audioManager.communicationDevice }.getOrNull()
            val currentStillValid = current != null && current in candidates

            if (current != null && (currentStillValid || current.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)) {
                return
            }

            when {
                candidates.size == 1 -> {
                    bindBluetoothCommunication(candidates.single())
                }
                candidates.size > 1 -> {
                    logger.w("AudioDeviceRouter: multiple Bluetooth communication devices available; keeping Android's current route instead of selecting arbitrarily")
                    bindSpeakerCommunication()
                }
                else -> {
                    bindSpeakerCommunication()
                }
            }
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            bindSpeakerCommunication()
        }
    }

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
            @Suppress("DEPRECATION")
            if (!audioManager.isBluetoothScoOn) {
                audioManager.isBluetoothScoOn = true
                audioManager.startBluetoothSco()
            }
            true
        }
    }.onFailure {
        logger.w("AudioDeviceRouter: не удалось привязать Bluetooth communication device: ${it.message}")
    }.getOrDefault(false)

    private fun bindSpeakerCommunication(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                audioManager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            }.getOrNull()
            if (speaker != null) {
                val currentComm = audioManager.communicationDevice
                if (currentComm?.id != speaker.id) {
                    runCatching { audioManager.setCommunicationDevice(speaker) }
                        .onFailure { logger.w("AudioDeviceRouter: не удалось выбрать встроенный динамик: ${it.message}") }
                }
            } else {
                runCatching { audioManager.clearCommunicationDevice() }
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }

        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
                .onFailure { logger.w("AudioDeviceRouter: mode change failed: ${it.message}") }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn
        }
    }

    private fun createSpeakerProfile(): RouteProfile {
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val builtInSpeaker = allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
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