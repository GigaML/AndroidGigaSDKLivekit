package com.gigaml.android.model

/**
 * A single line in a voice or chat transcript.
 *
 * @property id Stable identifier — for voice this is the LiveKit
 *   transcription segment id (used to upsert refined transcriptions);
 *   for chat it is the backend message id (or a generated one for
 *   optimistic local entries).
 * @property role Whether the line was produced by the user or the
 *   agent.
 * @property text Plain-text transcript content.
 * @property imageUrls Image URLs extracted from chat messages. Empty
 *   for voice transcripts.
 */
data class TranscriptEntry(
    val id: String,
    val role: Role,
    val text: String,
    val imageUrls: List<String> = emptyList(),
) {
    /** Author of a [TranscriptEntry]. */
    enum class Role {
        USER,
        ASSISTANT,
    }
}
