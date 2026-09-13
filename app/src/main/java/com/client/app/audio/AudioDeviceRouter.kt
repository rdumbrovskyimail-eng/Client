// >>> FILE: app/src/main/java/com/client/app/audio/AudioDeviceRouter.kt
package com.client.app.audio

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.client.app.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class AudioRoutePath {
    SPEAKER_EXCLUSIVE, // S23 Ultra MMAP Exclusive
    CMF_BUDS_WIRELESS  // Bluetooth Voice Communication
}

data class RouteProfile(
    val path: AudioRoutePath,
    val sampleRateOut: Int,
    val leadInBufferSizeFrames: Int,
    val vadThresholdStart: Float,
    val vadThresholdEnd: Float,
    val deviceName: String
)

@Singleton
class AudioDeviceRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val logger: AppLogger
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

    fun evaluateActiveRoute() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

        // E-20: Исключаем A2DP из голосовой связи (принимаем строго BLE или SCO гарнитуры)
        val btVoiceDevice = devices.firstOrNull { dev ->
            dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET || dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

        val newProfile = if (btVoiceDevice != null && bindBluetoothCommunication(btVoiceDevice)) {
            RouteProfile(
                path = AudioRoutePath.CMF_BUDS_WIRELESS,
                sampleRateOut = if (btVoiceDevice.type == AudioDeviceInfo.TYPE_BLE_HEADSET) 24000 else 16000,
                leadInBufferSizeFrames = 260 * 16,
                vadThresholdStart = 0.55f,
                vadThresholdEnd = 0.30f,
                deviceName = btVoiceDevice.productName.toString().ifBlank { "Bluetooth Headset" }
            )
        } else {
            bindSpeakerCommunication()
            createSpeakerProfile()
        }

        if (_currentProfile.value != newProfile) {
            _currentProfile.value = newProfile
            logger.d("AudioDeviceRouter: Маршрут -> ${newProfile.path} (${newProfile.deviceName})")
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
            if (speaker != null) audioManager.setCommunicationDevice(speaker)
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun createSpeakerProfile() = RouteProfile(
        path = AudioRoutePath.SPEAKER_EXCLUSIVE,
        sampleRateOut = 24000,
        leadInBufferSizeFrames = 160 * 16,
        vadThresholdStart = 0.65f,
        vadThresholdEnd = 0.35f,
        deviceName = "Samsung S23 Ultra Native MMAP"
    )
}