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
    CMF_BUDS_WIRELESS  // Nothing CMF Buds 2 (LE Audio LC3 24 кГц / BT SCO 16 кГц)
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

    // Скоуп антидребезга системных колбэков маршрутизации
    private var routerScope: CoroutineScope? = null
    private val debounceTrigger = MutableSharedFlow<Unit>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val _currentProfile = MutableStateFlow(createSpeakerProfile())
    val currentProfile: StateFlow<RouteProfile> = _currentProfile.asStateFlow()

    private var onRouteChangedListener: ((RouteProfile) -> Unit)? = null

    // Фингерпринт для отсечения повторных ложных срабатываний
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

    fun start(onRouteChange: (RouteProfile) -> Unit) {
        this.onRouteChangedListener = onRouteChange
        
        routerScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        routerScope = scope

        // Подписка на антидребезг: гасит пачки из 3–5 системных колбэков за 180 мс
        scope.launch {
            debounceTrigger
                .debounce(180L)
                .collect {
                    evaluateActiveRouteInternal()
                }
        }

        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        
        // Первый запуск выполняется мгновенно без задержки дебаунса
        evaluateActiveRouteInternal()
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

    /**
     * Внутренняя оценка портов с фильтрацией дребезга и фингерпринтингом
     */
    private fun evaluateActiveRouteInternal() = synchronized(routeLock) {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 1. Поиск разрешенных коммуникационных портов вывода (A2DP строго запрещен!)
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPermission) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val btOutputDevice = if (hasBtPermission) {
            commDevices.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: allOutputs.firstOrNull { dev ->
                dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        } else {
            null
        }

        // 2. Поиск парного физического микрофона гарнитуры (Source)
        val btInputDevice = if (hasBtPermission && btOutputDevice != null) {
            allInputs.firstOrNull { dev ->
                (dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                 dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) &&
                dev.isSource
            }
        } else {
            null
        }

        val newProfile = if (btOutputDevice != null && bindBluetoothCommunication(btOutputDevice)) {
            // Источник 8: Если гарнитура поддерживает LE Audio (LC3) — нативная частота 24 кГц!
            // Ресемплинг полностью отключается, звук транслируется бит-в-бит с Gemini Live!
            val sampleRate = if (btOutputDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 24000 else 16000
            val inDevId = btInputDevice?.id ?: 0
            val outDevId = btOutputDevice.id

            RouteProfile(
                path = AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRateOut = sampleRate,
                leadInBufferSizeFrames = 260 * 16,
                vadThresholdStart = 0.40f, // Высокая чувствительность для наушников
                vadThresholdEnd = 0.20f,
                deviceName = btOutputDevice.productName.toString().ifBlank { "CMF Buds 2 (Wireless)" },
                inputDeviceId = inDevId,
                outputDeviceId = outDevId
            )
        } else {
            bindSpeakerCommunication()
            createSpeakerProfile()
        }

        // 3. Проверка фингерпринта: если конфигурация железа не изменилась — не дергаем C++ ядро!
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

    private fun bindBluetoothCommunication(device: AudioDeviceInfo): Boolean {
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val currentComm = audioManager.communicationDevice
            if (currentComm?.id != device.id) {
                audioManager.setCommunicationDevice(device)
            } else {
                true
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
            sampleRateOut = 48000, // 48 кГц нативный ЦАП WCD9385 Galaxy S23 Ultra для чистого MMAP
            leadInBufferSizeFrames = 160 * 16,
            vadThresholdStart = 0.50f,
            vadThresholdEnd = 0.25f,
            deviceName = "Samsung S23 Ultra Native MMAP (48kHz)",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0
        )
    }
}