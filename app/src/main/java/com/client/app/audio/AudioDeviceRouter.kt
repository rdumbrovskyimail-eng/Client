// >>> FILE: app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt
package com.client.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRoutePath {
    SPEAKER_EXCLUSIVE, // Samsung Galaxy S23 Ultra Native MMAP Exclusive (48 кГц / 4.2 мс)
    CMF_BUDS_WIRELESS  // Nothing CMF Buds 2 (LE Audio LC3 / BT SCO)
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

    /**
     * REMEDIATION #5: Атомарная инициализация маршрута.
     * Слушатель регистрируется, первичный профиль вычисляется и сохраняется в activeFingerprint
     * под routeLock. Это исключает окно потери событий между оценкой и подпиской,
     * а также устраняет ложный повторный запуск движка при старте.
     */
    fun start(onRouteChange: (RouteProfile) -> Unit) = synchronized(routeLock) {
        this.onRouteChangedListener = onRouteChange

        routerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        routerScope = scope

        scope.launch {
            debounceTrigger
                .debounce(180L)
                .collect {
                    evaluateActiveRouteInternal()
                }
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, null)

        // Первичная оценка под единой блокировкой: инициализирует fingerprint,
        // поэтому первое совпадение не вызовет повторный duplicate trigger
        val initialProfile = evaluateActiveProfileLocked()
        activeFingerprint = RouteFingerprint(
            path = initialProfile.path,
            inDevId = initialProfile.inputDeviceId,
            outDevId = initialProfile.outputDeviceId,
            sampleRate = initialProfile.sampleRateOut
        )
        _currentProfile.value = initialProfile
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        onRouteChangedListener = null
        routerScope?.cancel()
        routerScope = null
        activeFingerprint = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audioManager.clearCommunicationDevice() }
        }
        if (audioManager.mode != AudioManager.MODE_NORMAL) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun selectOptimalBluetoothSampleRate(device: AudioDeviceInfo): Int {
        val supportedRates = device.sampleRates
        if (supportedRates.isNotEmpty()) {
            return when {
                supportedRates.contains(24000) -> 24000
                supportedRates.contains(16000) -> 16000
                supportedRates.contains(48000) -> 48000
                else -> supportedRates.firstOrNull { it >= 16000 } ?: 16000
            }
        }
        return if (device.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 24000 else 16000
    }

    private fun evaluateActiveRouteInternal() = synchronized(routeLock) {
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
    }

    /**
     * REMEDIATION #4: Проверка результата setCommunicationDevice() и безопасная ассоциация входа.
     */
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

        val btOutputDevice = if (hasBtPermission) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                commDevices.firstOrNull { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            } else {
                allOutputs.firstOrNull { dev ->
                    dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
            }
        } else {
            null
        }

        // Проверяем фактический булев результат привязки communication device
        val bound = btOutputDevice != null && bindBluetoothCommunication(btOutputDevice)

        return if (bound && btOutputDevice != null) {
            val sampleRate = selectOptimalBluetoothSampleRate(btOutputDevice)
            RouteProfile(
                path = AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRateOut = sampleRate,
                leadInBufferSizeFrames = 260 * 16,
                vadThresholdStart = 0.40f,
                vadThresholdEnd = 0.20f,
                deviceName = btOutputDevice.productName.toString().ifBlank { "CMF Buds 2 (Wireless)" },
                inputDeviceId = 0, // AAUDIO_UNSPECIFIED: communication routing управляется платформой Android
                outputDeviceId = btOutputDevice.id
            )
        } else {
            bindSpeakerCommunication()
            createSpeakerProfile()
        }
    }

    private fun bindBluetoothCommunication(device: AudioDeviceInfo): Boolean {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
    }

    private fun bindSpeakerCommunication() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                val currentComm = audioManager.communicationDevice
                if (currentComm?.id != speaker.id) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                audioManager.clearCommunicationDevice()
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
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }
    }

    private fun createSpeakerProfile(): RouteProfile {
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val builtInSpeaker = allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val builtInMic = allInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        return RouteProfile(
            path = AudioRoutePath.SPEAKER_EXCLUSIVE,
            sampleRateOut = 48000,
            leadInBufferSizeFrames = 160 * 16,
            vadThresholdStart = 0.50f,
            vadThresholdEnd = 0.25f,
            deviceName = "Samsung S23 Ultra Native MMAP (48kHz)",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0
        )
    }
}