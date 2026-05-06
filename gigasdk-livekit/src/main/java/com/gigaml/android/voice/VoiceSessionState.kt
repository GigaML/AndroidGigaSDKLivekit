package com.gigaml.android.voice

import com.gigaml.android.api.RoomResponse
import com.gigaml.android.model.TranscriptEntry

data class VoiceSessionState(
    val connectionState: VoiceConnectionState = VoiceConnectionState.IDLE,
    val error: String? = null,
    val isLoading: Boolean = false,
    val isMicrophoneEnabled: Boolean = false,
    val room: RoomResponse? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
)
