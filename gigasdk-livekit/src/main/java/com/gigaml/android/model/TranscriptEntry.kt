package com.gigaml.android.model

data class TranscriptEntry(
    val id: String,
    val role: Role,
    val text: String,
) {
    enum class Role {
        USER,
        ASSISTANT,
    }
}
