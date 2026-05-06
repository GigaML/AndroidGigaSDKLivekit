package com.gigaml.android.model

data class TranscriptEntry(
    val id: String,
    val role: Role,
    val text: String,
    val imageUrls: List<String> = emptyList(),
) {
    enum class Role {
        USER,
        ASSISTANT,
    }
}
