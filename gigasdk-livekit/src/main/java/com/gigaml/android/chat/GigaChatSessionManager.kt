package com.gigaml.android.chat

import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.model.TranscriptEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

data class GigaChatSessionState(
    val error: String? = null,
    val isClosing: Boolean = false,
    val isSending: Boolean = false,
    val isStarting: Boolean = false,
    val ticketId: String? = null,
    val transcript: List<TranscriptEntry> = emptyList(),
)

class GigaChatSessionManager(
    private val client: GigaApiClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(GigaChatSessionState())

    private var sessionEnded = false

    val state: StateFlow<GigaChatSessionState> = _state.asStateFlow()

    suspend fun start(initializationValues: JsonObject): Boolean {
        sessionEnded = false
        _state.value = GigaChatSessionState(isStarting = true)

        return try {
            val response = client.startChatSession(initializationValues)
            _state.value = GigaChatSessionState(
                isStarting = false,
                ticketId = response.ticketId,
                transcript = response.welcomeMessage
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        listOf(
                            TranscriptEntry(
                                id = "welcome-${response.ticketId}",
                                role = TranscriptEntry.Role.ASSISTANT,
                                text = it,
                                imageUrls = extractImageUrls(it),
                            ),
                        )
                    }
                    .orEmpty(),
            )
            true
        } catch (error: Exception) {
            _state.value = GigaChatSessionState(
                error = error.message ?: "Failed to start chat session.",
            )
            false
        }
    }

    suspend fun send(text: String): Boolean {
        val trimmedText = text.trim()
        val ticketId = _state.value.ticketId
        if (
            ticketId.isNullOrBlank() ||
            trimmedText.isBlank() ||
            _state.value.isSending ||
            _state.value.isClosing ||
            _state.value.isStarting
        ) {
            return false
        }

        val optimisticId = "user-${System.currentTimeMillis()}"
        _state.update { currentState ->
            currentState.copy(
                error = null,
                isSending = true,
                transcript = currentState.transcript + TranscriptEntry(
                    id = optimisticId,
                    role = TranscriptEntry.Role.USER,
                    text = trimmedText,
                    imageUrls = extractImageUrls(trimmedText),
                ),
            )
        }

        return try {
            val response = client.sendChatText(ticketId = ticketId, text = trimmedText)
            _state.update { currentState ->
                currentState.copy(
                    isSending = false,
                    transcript = appendAssistantResponse(
                        transcript = currentState.transcript,
                        responseText = response.message.text,
                        responseRole = response.message.role,
                        responseId = response.message.messageId ?: "assistant-${System.currentTimeMillis()}",
                        responseImageUrls = response.message.imageUrls,
                    ),
                )
            }
            true
        } catch (error: Exception) {
            _state.update { currentState ->
                currentState.copy(
                    error = error.message ?: "Failed to send chat message.",
                    isSending = false,
                    transcript = currentState.transcript.filterNot { it.id == optimisticId },
                )
            }
            false
        }
    }

    suspend fun end(): Boolean {
        val ticketId = _state.value.ticketId ?: return true
        sessionEnded = true
        _state.update { it.copy(error = null, isClosing = true) }

        return try {
            client.closeChatSession(ticketId)
            _state.value = GigaChatSessionState()
            true
        } catch (error: Exception) {
            sessionEnded = false
            _state.update {
                it.copy(
                    error = error.message ?: "Failed to close chat session.",
                    isClosing = false,
                )
            }
            false
        }
    }

    fun setError(message: String?) {
        _state.update { it.copy(error = message) }
    }

    fun close() {
        val ticketId = _state.value.ticketId
        if (!sessionEnded && !ticketId.isNullOrBlank()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { client.closeChatSession(ticketId) }
            }
        }

        scope.cancel()
    }

    private fun appendAssistantResponse(
        transcript: List<TranscriptEntry>,
        responseText: String,
        responseRole: String,
        responseId: String,
        responseImageUrls: List<String>,
    ): List<TranscriptEntry> {
        val imageUrls = if (responseImageUrls.isNotEmpty()) {
            responseImageUrls
        } else {
            extractImageUrls(responseText)
        }

        if (responseText.isBlank() && imageUrls.isEmpty()) {
            return transcript
        }

        val role = if (responseRole == "user") {
            TranscriptEntry.Role.USER
        } else {
            TranscriptEntry.Role.ASSISTANT
        }

        return transcript + TranscriptEntry(
            id = responseId,
            role = role,
            text = responseText,
            imageUrls = imageUrls,
        )
    }

    private fun extractImageUrls(text: String): List<String> =
        IMAGE_URL_REGEX.findAll(text)
            .map { it.value }
            .distinct()
            .toList()

    private companion object {
        val IMAGE_URL_REGEX =
            Regex("""https?://\S+\.(?:png|jpe?g|gif|webp)(?:\?\S*)?""", RegexOption.IGNORE_CASE)
    }
}
