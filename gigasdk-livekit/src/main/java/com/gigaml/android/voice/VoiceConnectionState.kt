package com.gigaml.android.voice

/**
 * Lifecycle of a [GigaVoiceSessionManager] LiveKit connection.
 *
 * - [IDLE]: not connected. Either never started or stopped/disconnected.
 * - [CONNECTING]: room creation and/or LiveKit handshake in flight.
 *   Also re-entered on transient reconnects.
 * - [CONNECTED]: LiveKit room is joined and the mic is active.
 */
enum class VoiceConnectionState {
    IDLE,
    CONNECTING,
    CONNECTED,
}
