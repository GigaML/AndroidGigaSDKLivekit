package com.gigaml.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

internal class AndroidAudioSessionController(
    context: Context,
) {
    private val audioManager = checkNotNull(context.getSystemService(AudioManager::class.java)) {
        "AudioManager is required for voice sessions."
    }

    private var active = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState = false

    fun start() {
        if (active) {
            return
        }

        previousMode = audioManager.mode
        previousSpeakerphoneState = audioManager.isSpeakerphoneOn

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val nextRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { }
            .build()

        audioManager.requestAudioFocus(nextRequest)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        audioFocusRequest = nextRequest
        active = true
    }

    fun stop() {
        if (!active) {
            return
        }

        audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
        audioFocusRequest = null

        audioManager.mode = previousMode
        audioManager.isSpeakerphoneOn = previousSpeakerphoneState
        active = false
    }
}
