package com.gigaml.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
import android.os.Build
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
    private var previousCommunicationDeviceId: Int? = null
    private var previousSpeakerphoneState = false
    private var deviceCallback: AudioDeviceCallback? = null
    private var focusListener: ((Int) -> Unit)? = null

    fun start(onFocusChange: (Int) -> Unit = {}): Boolean {
        if (active) {
            return true
        }

        focusListener = onFocusChange
        previousMode = audioManager.mode
        previousCommunicationDeviceId = readCurrentCommunicationDeviceId()
        previousSpeakerphoneState = readLegacySpeakerphoneState()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val nextRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change -> focusListener?.invoke(change) }, mainHandler)
            .build()

        val focusResult = audioManager.requestAudioFocus(nextRequest)
        if (focusResult != AUDIOFOCUS_REQUEST_GRANTED) {
            focusListener = null
            return false
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        applyPreferredRouting()
        registerDeviceCallback()

        audioFocusRequest = nextRequest
        active = true
        return true
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
        restorePreviousRouting()
        active = false
    }

    private fun applyPreferredRouting() {
        val shouldUseSpeaker = AudioRoutingPolicy.prefersBuiltInSpeaker(
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map(AudioDeviceInfo::getType),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (shouldUseSpeaker) {
                val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull { device ->
                    device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                if (speakerDevice != null) {
                    audioManager.setCommunicationDevice(speakerDevice)
                } else {
                    audioManager.clearCommunicationDevice()
                }
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        setLegacySpeakerphoneEnabled(shouldUseSpeaker)
    }

    private fun restorePreviousRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val previousDevice = previousCommunicationDeviceId?.let { previousId ->
                audioManager.availableCommunicationDevices.firstOrNull { device ->
                    device.id == previousId
                }
            }

            if (previousDevice != null) {
                audioManager.setCommunicationDevice(previousDevice)
            } else {
                audioManager.clearCommunicationDevice()
            }
            return
        }

        setLegacySpeakerphoneEnabled(previousSpeakerphoneState)
    }

    private fun readCurrentCommunicationDeviceId(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return null
        }

        return audioManager.communicationDevice?.id
    }

    @Suppress("DEPRECATION")
    private fun readLegacySpeakerphoneState(): Boolean = audioManager.isSpeakerphoneOn

    @Suppress("DEPRECATION")
    private fun setLegacySpeakerphoneEnabled(enabled: Boolean) {
        audioManager.isSpeakerphoneOn = enabled
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
