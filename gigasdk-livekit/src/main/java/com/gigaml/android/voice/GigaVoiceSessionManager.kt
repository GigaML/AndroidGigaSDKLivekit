package com.gigaml.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.api.RoomResponse
import com.gigaml.android.model.TranscriptEntry
import io.livekit.android.annotations.Beta
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

enum class VoiceConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
}

data class VoiceSessionState(
    val connectionState: VoiceConnectionState = VoiceConnectionState.IDLE,
    val error: String? = null,
    val isLoading: Boolean = false,
    val isMicrophoneEnabled: Boolean = false,
    val room: RoomResponse? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
)

class GigaVoiceSessionManager(
    private val appContext: Context,
    private val client: GigaApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioSessionController = AndroidAudioSessionController(appContext)
    private val _state = MutableStateFlow(VoiceSessionState())

    private var activeRoom: Room? = null
    private var roomEventsJob: Job? = null

    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    suspend fun start(initializationValues: JsonObject): Boolean {
        if (!hasMicrophonePermission()) {
            setError("Microphone access is required for voice testing.")
            return false
        }

        stop()
        _state.value = VoiceSessionState(
            connectionState = VoiceConnectionState.CONNECTING,
            isLoading = true,
        )

        return try {
            audioSessionController.start()
            val roomResponse = client.createVoiceRoom(initializationValues)
            val nextRoom = LiveKit.create(appContext)
            activeRoom = nextRoom
            observeRoomEvents(nextRoom)
            nextRoom.connect(roomResponse.serverUrl, roomResponse.participantToken)
            nextRoom.localParticipant.setMicrophoneEnabled(true)

            _state.update {
                it.copy(
                    connectionState = VoiceConnectionState.CONNECTED,
                    error = null,
                    isLoading = false,
                    isMicrophoneEnabled = nextRoom.localParticipant.isMicrophoneEnabled,
                    room = roomResponse,
                )
            }
            true
        } catch (error: Exception) {
            handleTerminalVoiceFailure(error.message ?: "Failed to create voice room.")
            false
        }
    }

    suspend fun toggleMicrophone() {
        val room = activeRoom ?: return
        try {
            room.localParticipant.setMicrophoneEnabled(!room.localParticipant.isMicrophoneEnabled)
            _state.update {
                it.copy(
                    error = null,
                    isMicrophoneEnabled = room.localParticipant.isMicrophoneEnabled,
                )
            }
        } catch (error: Exception) {
            setError(error.message ?: "Failed to update microphone state.")
        }
    }

    fun stop() {
        roomEventsJob?.cancel()
        roomEventsJob = null

        activeRoom?.disconnect()
        activeRoom = null
        audioSessionController.stop()

        _state.value = VoiceSessionState()
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    @OptIn(Beta::class)
    private fun observeRoomEvents(room: Room) {
        roomEventsJob?.cancel()
        roomEventsJob = scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.Reconnecting -> {
                        _state.update {
                            it.copy(connectionState = VoiceConnectionState.CONNECTING)
                        }
                    }

                    is RoomEvent.Reconnected -> {
                        _state.update {
                            it.copy(connectionState = VoiceConnectionState.CONNECTED)
                        }
                    }

                    is RoomEvent.Disconnected -> {
                        handleTerminalVoiceFailure(event.error?.message)
                    }

                    is RoomEvent.FailedToConnect -> {
                        handleTerminalVoiceFailure(event.error.message)
                    }

                    is RoomEvent.DataReceived -> {
                        if (readDataTopic(event) == "agent_error") {
                            setError(parseAgentError(event.data.decodeToString()))
                        }
                    }

                    is RoomEvent.TranscriptionReceived -> {
                        val latestSegment = event.transcriptionSegments.lastOrNull() ?: return@collect
                        val transcriptText = latestSegment.text.trim()
                        if (latestSegment.id.isBlank() || transcriptText.isBlank()) {
                            return@collect
                        }

                        val participantIdentity = event.participant?.identity?.toString()
                        val isUserSpeaker =
                            participantIdentity == room.localParticipant.identity.toString() ||
                                participantIdentity?.startsWith("user_") == true

                        val nextEntry = TranscriptEntry(
                            id = latestSegment.id,
                            role = if (isUserSpeaker) {
                                TranscriptEntry.Role.USER
                            } else {
                                TranscriptEntry.Role.ASSISTANT
                            },
                            text = transcriptText,
                        )

                        _state.update { currentState ->
                            currentState.copy(
                                transcript = upsertTranscriptEntry(currentState.transcript, nextEntry),
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun handleTerminalVoiceFailure(message: String?) {
        roomEventsJob?.cancel()
        roomEventsJob = null
        activeRoom = null
        audioSessionController.stop()

        _state.update {
            it.copy(
                connectionState = VoiceConnectionState.IDLE,
                error = message ?: it.error,
                isLoading = false,
                isMicrophoneEnabled = false,
                room = null,
            )
        }
    }

    private fun parseAgentError(payload: String): String =
        runCatching {
            val errorField = com.gigaml.android.api.parseInitializationValues(payload)
                ?.get("error")
                ?.toString()
                ?.trim('"')
            errorField?.takeIf { it.isNotBlank() } ?: payload
        }.getOrDefault(payload)

    private fun upsertTranscriptEntry(
        currentEntries: List<TranscriptEntry>,
        nextEntry: TranscriptEntry,
    ): List<TranscriptEntry> {
        val existingIndex = currentEntries.indexOfFirst { it.id == nextEntry.id }
        if (existingIndex < 0) {
            return currentEntries + nextEntry
        }

        return currentEntries.mapIndexed { index, entry ->
            if (index == existingIndex) {
                nextEntry
            } else {
                entry
            }
        }
    }

    private fun readDataTopic(event: RoomEvent.DataReceived): String? =
        runCatching {
            event.javaClass.getMethod("getTopic").invoke(event) as? String
        }.getOrNull()
}
