package com.gigaml.android.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.api.parseInitializationValues
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.text.decodeToString

/**
 * Owns a single Giga voice session built on top of a LiveKit room.
 *
 * Responsibilities:
 * - Calls `POST /api/voice/create-room` and connects to the returned
 *   LiveKit server.
 * - Enables the local microphone and exposes a mute toggle.
 * - Manages the system audio session via [AndroidAudioSessionController]:
 *   routes audio to a connected headset when present, falls back to
 *   speakerphone, and pauses the microphone on audio focus loss
 *   (incoming call, alarm, Assistant) — resuming on focus gain.
 * - Surfaces a live transcript and connection state via [state].
 *
 * Call [close] from `onDestroy` / `DisposableEffect.onDispose` to
 * release the LiveKit room and cancel the internal coroutine scope.
 *
 * The host app must hold `Manifest.permission.RECORD_AUDIO` before
 * calling [start]; otherwise [start] sets an error and returns `false`.
 */
class GigaVoiceSessionManager(
    appContext: Context,
    private val client: GigaApiClient,
) {
    private val appContext = appContext.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioSessionController = AndroidAudioSessionController(this.appContext)
    private val _state = MutableStateFlow(VoiceSessionState())
    private val startMutex = Mutex()

    private var activeRoom: Room? = null
    private var roomEventsJob: Job? = null
    private var pausedByAudioFocus = false

    /** Observable session state — connection, transcript, mic, error. */
    val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    /** Returns `true` if the host app currently holds `RECORD_AUDIO`. */
    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Creates a voice room via the backend, connects to LiveKit, and
     * enables the microphone.
     *
     * @param initializationValues Caller-supplied values forwarded to
     *   the backend (typically an [InitializationOption.values] bundle).
     * @return `true` on success. On failure the error message is
     *   surfaced via [state] and the session is rolled back to IDLE.
     */
    suspend fun start(initializationValues: JsonObject): Boolean {
        if (!hasMicrophonePermission()) {
            setError("Microphone access is required for voice testing.")
            return false
        }

        if (!startMutex.tryLock()) {
            Log.d(TAG, "Ignoring duplicate voice start request while startup is already in progress.")
            return false
        }

        try {
            stop()
            _state.value = VoiceSessionState(
                connectionState = VoiceConnectionState.CONNECTING,
                isLoading = true,
            )

            audioSessionController.start(::onAudioFocusChange)

            Log.d(TAG, "Creating voice room.")
            val roomResponse = try {
                client.createVoiceRoom(initializationValues)
            } catch (error: Exception) {
                return handleStartFailure("Failed to create voice room.", error)
            }

            Log.d(
                TAG,
                "Voice room created roomId=${roomResponse.roomId} roomName=${roomResponse.roomName}.",
            )

            val room = LiveKit.create(appContext)
            activeRoom = room
            observeRoomEvents(room)

            try {
                Log.d(TAG, "Connecting to LiveKit room.")
                room.connect(roomResponse.serverUrl, roomResponse.participantToken)
            } catch (error: Exception) {
                return handleStartFailure("Failed to connect to the voice room.", error)
            }

            val microphoneEnabled = try {
                Log.d(TAG, "Enabling local microphone.")
                room.localParticipant.setMicrophoneEnabled(true)
            } catch (error: Exception) {
                return handleStartFailure(
                    "Connected to the voice room, but failed to enable the microphone.",
                    error,
                )
            }

            _state.value = VoiceSessionState(
                connectionState = VoiceConnectionState.CONNECTED,
                isLoading = false,
                isMicrophoneEnabled = microphoneEnabled,
                room = roomResponse,
            )

            Log.d(TAG, "Voice session connected successfully.")
            return true
        } finally {
            startMutex.unlock()
        }
    }

    /** Flips local mute state. No-op if no room is active. */
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
            val message = error.message ?: "Failed to update microphone state."
            Log.e(TAG, message, error)
            setError(message)
        }
    }

    /**
     * Disconnects the LiveKit room and restores the system audio mode
     * and speakerphone state. The manager can be restarted by calling
     * [start] again.
     */
    fun stop() {
        roomEventsJob?.cancel()
        roomEventsJob = null

        activeRoom?.disconnect()
        activeRoom = null
        pausedByAudioFocus = false

        audioSessionController.stop()
        _state.value = VoiceSessionState()
    }

    /** Overwrites the current error message on [state]. */
    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    /**
     * Permanently releases this manager. Cancels the internal coroutine
     * scope; the instance must not be reused after this call. Safe to
     * call from `onDestroy` or `DisposableEffect.onDispose`.
     */
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
                        Log.w(TAG, "Voice room is reconnecting.")
                        _state.update { it.copy(connectionState = VoiceConnectionState.CONNECTING) }
                    }

                    is RoomEvent.Reconnected -> {
                        Log.d(TAG, "Voice room reconnected.")
                        _state.update { it.copy(connectionState = VoiceConnectionState.CONNECTED) }
                    }

                    is RoomEvent.Disconnected -> {
                        handleTerminalVoiceFailure(
                            describeFailure("Voice session disconnected.", event.error?.message),
                            event.error,
                        )
                    }

                    is RoomEvent.FailedToConnect -> {
                        handleTerminalVoiceFailure(
                            describeFailure("Failed to connect to the voice room.", event.error.message),
                            event.error,
                        )
                    }

                    is RoomEvent.DataReceived -> {
                        if (readDataTopic(event) == "agent_error") {
                            val message = parseAgentError(event.data.decodeToString())
                            Log.e(TAG, "Agent error received: $message")
                            setError(message)
                        }
                    }

                    is RoomEvent.TranscriptionReceived -> {
                        val segment = event.transcriptionSegments.lastOrNull() ?: return@collect
                        val text = segment.text.trim()
                        if (segment.id.isBlank() || text.isBlank()) {
                            return@collect
                        }

                        val participantIdentity = event.participant?.identity?.value
                        val isUserTranscript =
                            participantIdentity == room.localParticipant.identity?.value ||
                                participantIdentity?.startsWith("user_") == true

                        val transcriptEntry = TranscriptEntry(
                            id = segment.id,
                            role = if (isUserTranscript) {
                                TranscriptEntry.Role.USER
                            } else {
                                TranscriptEntry.Role.ASSISTANT
                            },
                            text = text,
                        )

                        _state.update { currentState ->
                            currentState.copy(
                                transcript = upsertTranscriptEntry(
                                    currentState.transcript,
                                    transcriptEntry,
                                ),
                            )
                        }
                    }

                    else -> Unit
                }
            }
        }
    }

    private fun handleTerminalVoiceFailure(
        message: String?,
        error: Throwable? = null,
    ) {
        roomEventsJob?.cancel()
        roomEventsJob = null
        activeRoom?.disconnect()
        activeRoom = null
        audioSessionController.stop()

        val resolvedMessage = message?.takeIf { it.isNotBlank() } ?: _state.value.error
        when {
            error != null && resolvedMessage != null -> Log.e(TAG, resolvedMessage, error)
            error != null -> Log.e(TAG, "Voice session failed.", error)
            resolvedMessage != null -> Log.w(TAG, resolvedMessage)
            else -> Log.w(TAG, "Voice session ended.")
        }

        _state.update { currentState ->
            currentState.copy(
                connectionState = VoiceConnectionState.IDLE,
                error = resolvedMessage,
                isLoading = false,
                isMicrophoneEnabled = false,
                room = null,
            )
        }
    }

    private fun handleStartFailure(
        stageMessage: String,
        error: Exception,
    ): Boolean {
        handleTerminalVoiceFailure(
            message = describeFailure(stageMessage, error.message),
            error = error,
        )
        return false
    }

    private fun onAudioFocusChange(change: Int) {
        val room = activeRoom ?: return
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (room.localParticipant.isMicrophoneEnabled) {
                    Log.d(TAG, "Audio focus lost (change=$change); muting microphone.")
                    pausedByAudioFocus = true
                    scope.launch {
                        runCatching { room.localParticipant.setMicrophoneEnabled(false) }
                        _state.update { it.copy(isMicrophoneEnabled = false) }
                    }
                }
            }

            AudioManager.AUDIOFOCUS_GAIN -> {
                if (pausedByAudioFocus) {
                    Log.d(TAG, "Audio focus regained; resuming microphone.")
                    pausedByAudioFocus = false
                    scope.launch {
                        val enabled = runCatching {
                            room.localParticipant.setMicrophoneEnabled(true)
                            room.localParticipant.isMicrophoneEnabled
                        }.getOrDefault(false)
                        _state.update { it.copy(isMicrophoneEnabled = enabled) }
                    }
                }
            }
        }
    }

    private fun parseAgentError(rawMessage: String): String =
        runCatching {
            val errorValue = parseInitializationValues(rawMessage)
                ?.get("error")
                ?.let { it as? JsonPrimitive }
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            errorValue ?: rawMessage
        }.getOrDefault(rawMessage)

    private fun upsertTranscriptEntry(
        transcript: List<TranscriptEntry>,
        entry: TranscriptEntry,
    ): List<TranscriptEntry> {
        val index = transcript.indexOfFirst { it.id == entry.id }
        if (index < 0) {
            return transcript + entry
        }

        return transcript.mapIndexed { currentIndex, currentEntry ->
            if (currentIndex == index) {
                entry
            } else {
                currentEntry
            }
        }
    }

    private fun readDataTopic(event: RoomEvent.DataReceived): String? =
        runCatching {
            event::class.java.getMethod("getTopic").invoke(event) as? String
        }.getOrNull() ?: event.topic

    private fun describeFailure(stageMessage: String, detail: String?): String {
        val normalizedDetail = detail?.trim()?.trimEnd('.')?.takeIf { it.isNotBlank() }
        if (normalizedDetail == null) {
            return stageMessage
        }

        if (normalizedDetail.equals(stageMessage.trimEnd('.'), ignoreCase = true)) {
            return stageMessage
        }

        return "$stageMessage $normalizedDetail."
    }

    private companion object {
        private const val TAG = "GigaVoiceSession"
    }
}
