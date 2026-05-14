package com.gigaml.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

internal class AndroidAudioSessionController(
    context: Context,
) {
    private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java)) {
        "AudioManager is required for voice sessions."
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var active = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var focusListener: ((Int) -> Unit)? = null

    fun start(onFocusChange: (Int) -> Unit = {}) {
        if (active) {
            return
        }

        focusListener = onFocusChange
        previousMode = audioManager.mode
        previousSpeakerphoneState = audioManager.isSpeakerphoneOn

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val nextRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change -> focusListener?.invoke(change) }, mainHandler)
            .build()

        audioManager.requestAudioFocus(nextRequest)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        applyPreferredRouting()
        registerDeviceCallback()

        audioFocusRequest = nextRequest
        active = true
    }

    fun stop() {
        if (!active) {
            return
        }

        deviceCallback?.let(audioManager::unregisterAudioDeviceCallback)
        deviceCallback = null

        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null
        focusListener = null

        audioManager.mode = previousMode
        audioManager.isSpeakerphoneOn = previousSpeakerphoneState
        active = false
    }

    private fun applyPreferredRouting() {
        audioManager.isSpeakerphoneOn = !hasExternalAudioDevice()
    }

    private fun hasExternalAudioDevice(): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> true
                else -> false
            }
        }
    }

    private fun registerDeviceCallback() {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                if (active) applyPreferredRouting()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                if (active) applyPreferredRouting()
            }
        }
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
        deviceCallback = callback
    }
}
