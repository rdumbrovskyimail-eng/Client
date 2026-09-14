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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRoutePath {
    SPEAKER_EXCLUSIVE, // Samsung Galaxy S23 Ultra Native MMAP Exclusive (48 кГц / 4.2 мс)
    CMF_BUDS_WIRELESS  // Nothing CMF Buds 2 Low-Latency (BLE Audio LC3 / BT SCO)
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

@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val routeLock = Any()

    private val _currentProfile = MutableStateFlow(createSpeakerProfile())
    val currentProfile: StateFlow<RouteProfile> = _currentProfile.asStateFlow()

    private var onRouteChangedListener: ((RouteProfile) -> Unit)? = null

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            evaluateActiveRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            evaluateActiveRoute()
        }
    }

    fun start(onRouteChange: (RouteProfile) -> Unit) {
        this.onRouteChangedListener = onRouteChange
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        evaluateActiveRoute()
    }

    fun stop() {
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        onRouteChangedListener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    /**
     * Потокобезопасная оценка и селекция аппаратных портов ввода и вывода
     */
    fun evaluateActiveRoute() = synchronized(routeLock) {
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        // 1. Поиск устройств связи (API 31+) и устройств вывода
        val commDevices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasBtPermission) {
            runCatching { audioManager.availableCommunicationDevices }.getOrDefault(emptyList())
        } else {
            emptyList()
        }

        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        // Источник 4: Для связи допустимы ТОЛЬКО TYPE_BLE_HEADSET и TYPE_BLUETOOTH_SCO (A2DP запрещен!)
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

        // Источник 113: Поиск парного микрофона гарнитуры (Source) с отдельным inputDeviceId
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
            // Определение нативной частоты гарнитуры: LC3 (24 кГц) или mSBC (16 кГц)
            val sampleRate = if (btOutputDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 24000 else 16000
            val inDevId = btInputDevice?.id ?: 0
            val outDevId = btOutputDevice.id

            logger.d("AudioDeviceRouter: Привязан Bluetooth -> Out ID=$outDevId (${btOutputDevice.productName}), In ID=$inDevId")

            RouteProfile(
                path = AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRateOut = sampleRate,
                leadInBufferSizeFrames = 260 * 16, // 260 мс пре-ролл буфера для гарнитуры
                vadThresholdStart = 0.50f,
                vadThresholdEnd = 0.25f,
                deviceName = btOutputDevice.productName.toString().ifBlank { "CMF Buds 2 (Wireless)" },
                inputDeviceId = inDevId,
                outputDeviceId = outDevId
            )
        } else {
            bindSpeakerCommunication()
            createSpeakerProfile()
        }

        if (_currentProfile.value != newProfile) {
            _currentProfile.value = newProfile
            logger.d("AudioDeviceRouter: Активен профиль -> ${newProfile.path} [InId=${newProfile.inputDeviceId}, OutId=${newProfile.outputDeviceId}]")
            onRouteChangedListener?.invoke(newProfile)
        }
    }

    private fun bindBluetoothCommunication(device: AudioDeviceInfo): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.setCommunicationDevice(device)
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = true
            @Suppress("DEPRECATION")
            audioManager.startBluetoothSco()
            true
        }
    }

    private fun bindSpeakerCommunication() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
            val speaker = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            }
            if (speaker != null) {
                audioManager.setCommunicationDevice(speaker)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isBluetoothScoOn = false
            @Suppress("DEPRECATION")
            audioManager.stopBluetoothSco()
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    /**
     * Создает профиль для встроенного тракта Samsung S23 Ultra (48 кГц WCD9385 ЦАП MMAP Exclusive)
     */
    private fun createSpeakerProfile(): RouteProfile {
        val allOutputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val allInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val builtInSpeaker = allOutputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val builtInMic = allInputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        return RouteProfile(
            path = AudioRoutePath.SPEAKER_EXCLUSIVE,
            sampleRateOut = 48000, // 48 кГц нативная аппаратная сетка ЦАП WCD9385 для MMAP Direct
            leadInBufferSizeFrames = 160 * 16, // 160 мс пре-ролл буфера для спикера
            vadThresholdStart = 0.55f,
            vadThresholdEnd = 0.30f,
            deviceName = "Samsung S23 Ultra Native MMAP (48kHz)",
            inputDeviceId = builtInMic?.id ?: 0,
            outputDeviceId = builtInSpeaker?.id ?: 0
        )
    }
}