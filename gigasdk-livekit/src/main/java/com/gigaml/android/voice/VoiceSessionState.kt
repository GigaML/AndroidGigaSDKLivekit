package com.gigaml.android.voice

import com.gigaml.android.api.RoomResponse
import com.gigaml.android.model.TranscriptEntry

/**
 * Observable state of a [GigaVoiceSessionManager].
 *
 * @property connectionState Lifecycle of the LiveKit room connection.
 * @property error Last error surfaced by the session. Cleared on
 *   [GigaVoiceSessionManager.start] and explicit [GigaVoiceSessionManager.setError].
 * @property isLoading `true` while the session is connecting.
 * @property isMicrophoneEnabled Mirror of `localParticipant.isMicrophoneEnabled`
 *   from LiveKit; flips on user mute and on audio focus loss/gain.
 * @property room The backend's room creation response, available once
 *   the LiveKit room is joined.
 * @property transcript Ordered transcript entries received from the
 *   agent and local user. New segments replace earlier ones with the
 *   same id (LiveKit transcriptions are streamed and refined).
 */
data class VoiceSessionState(
    val connectionState: VoiceConnectionState = VoiceConnectionState.IDLE,
    val error: String? = null,
    val isLoading: Boolean = false,
    val isMicrophoneEnabled: Boolean = false,
    val room: RoomResponse? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
)
