package com.gigaml.android.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

internal class AndroidAudioSessionController(
    context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private var active = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var previousMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerphoneState: Boolean = false

    @Suppress("DEPRECATION")
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

        val nextFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { }
            .build()

        audioManager.requestAudioFocus(nextFocusRequest)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = true

        audioFocusRequest = nextFocusRequest
        active = true
    }

    @Suppress("DEPRECATION")
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
