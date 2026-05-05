package com.gigaml.android.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.Call
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
data class AppConfigResponse(
    val chatEndpoint: String,
    val defaultAgentId: String? = null,
    val defaultAgentTemplateId: String? = null,
    val roomEndpoint: String,
    val supports: Supports = Supports(),
) {
    @Serializable
    data class Supports(
        val chat: Boolean = true,
        val voice: Boolean = true,
    )
}

@Serializable
data class RoomResponse(
    val agentId: String? = null,
    val agentTemplateId: String? = null,
    val initializationSchema: JsonObject? = null,
    val participantName: String,
    val participantToken: String,
    val roomId: String,
    val roomName: String,
    val serverUrl: String,
)

@Serializable
data class ChatStartResponse(
    val agentId: String? = null,
    val agentTemplateId: String? = null,
    val ticketId: String,
    val welcomeMessage: String? = null,
)

@Serializable
data class ChatMessage(
    val messageId: String? = null,
    val role: String,
    val text: String,
)

@Serializable
data class ChatSendResponse(
    val message: ChatMessage,
    val responseType: String,
)

@Serializable
data class ChatCloseResponse(
    val message: String? = null,
    val success: Boolean,
)

data class GigaApiEndpoints(
    val closeChatSession: String = "/api/chat/end",
    val config: String = "/api/config",
    val createVoiceRoom: String = "/api/voice/create-room",
    val sendChatText: String = "/api/chat/send",
    val startChatSession: String = "/api/chat/start",
)

data class GigaApiClientConfig(
    val baseUrl: String,
    val endpoints: GigaApiEndpoints = GigaApiEndpoints(),
    val headers: Map<String, String> = emptyMap(),
)

class GigaApiClient(
    private val config: GigaApiClientConfig,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    constructor(config: GigaApiClientConfig) : this(
        config = config,
        httpClient = OkHttpClient(),
        json = Json {
            ignoreUnknownKeys = true
        },
    )

    suspend fun fetchConfig(): AppConfigResponse =
        requestJsonWithoutBody(
            endpoint = config.endpoints.config,
            method = "GET",
            fallbackMessage = "Failed to load app config.",
        )

    suspend fun createVoiceRoom(
        initializationValues: JsonObject,
    ): RoomResponse =
        requestJson(
            endpoint = config.endpoints.createVoiceRoom,
            method = "POST",
            body = VoiceRoomRequest(initializationValues),
            fallbackMessage = "Failed to create voice room.",
        )

    suspend fun startChatSession(
        initializationValues: JsonObject,
    ): ChatStartResponse =
        requestJson(
            endpoint = config.endpoints.startChatSession,
            method = "POST",
            body = ChatStartRequest(initializationValues),
            fallbackMessage = "Failed to start chat session.",
        )

    suspend fun sendChatText(
        ticketId: String,
        text: String,
        messageId: String? = null,
    ): ChatSendResponse =
        requestJson(
            endpoint = config.endpoints.sendChatText,
            method = "POST",
            body = ChatSendRequest(ticketId = ticketId, text = text, messageId = messageId),
            fallbackMessage = "Failed to send chat message.",
        )

    suspend fun closeChatSession(ticketId: String): ChatCloseResponse =
        requestJson(
            endpoint = config.endpoints.closeChatSession,
            method = "POST",
            body = ChatEndRequest(ticketId = ticketId),
            fallbackMessage = "Failed to close chat session.",
        )

    private suspend inline fun <reified T> requestJsonWithoutBody(
        endpoint: String,
        method: String,
        fallbackMessage: String,
    ): T {
        val request = Request.Builder()
            .url(resolveEndpoint(config.baseUrl, endpoint))
            .headers(buildHeaders(hasBody = false))
            .method(method, null)
            .build()

        return executeRequest(request, fallbackMessage)
    }

    private suspend inline fun <reified T, reified B> requestJson(
        endpoint: String,
        method: String,
        fallbackMessage: String,
        body: B? = null,
    ): T {
        val bodyJson = body?.let { json.encodeToString(it) }
        val request = Request.Builder()
            .url(resolveEndpoint(config.baseUrl, endpoint))
            .headers(buildHeaders(bodyJson != null))
            .method(
                method,
                bodyJson?.toRequestBody(JSON_MEDIA_TYPE),
            )
            .build()

        return executeRequest(request, fallbackMessage)
    }

    private suspend inline fun <reified T> executeRequest(
        request: Request,
        fallbackMessage: String,
    ): T {
        val response = httpClient.newCall(request).await()
        response.use { nextResponse ->
            val payload = nextResponse.body?.string().orEmpty()
            if (!nextResponse.isSuccessful) {
                throw IOException(readErrorMessage(payload) ?: fallbackMessage)
            }

            if (payload.isBlank()) {
                throw IOException(fallbackMessage)
            }

            return json.decodeFromString<T>(payload)
        }
    }

    private fun buildHeaders(hasBody: Boolean): Headers {
        val builder = Headers.Builder()
            .add("Accept", "application/json")

        if (hasBody) {
            builder.add("Content-Type", "application/json")
        }

        config.headers.forEach { (name, value) ->
            builder.set(name, value)
        }

        return builder.build()
    }
}

fun parseInitializationValues(source: String): JsonObject? =
    runCatching {
        val parsed = Json.parseToJsonElement(source)
        parsed as? JsonObject
    }.getOrNull()

fun shortIdentifier(value: String?): String =
    when {
        value.isNullOrBlank() -> "Not configured"
        value.length <= 20 -> value
        else -> "${value.take(12)}...${value.takeLast(6)}"
    }

fun emptyInitializationValues(): JsonObject = buildJsonObject {}

private fun resolveEndpoint(baseUrl: String, endpoint: String): String {
    if (endpoint.matches(Regex("^[a-z][a-z0-9+.-]*://.*", RegexOption.IGNORE_CASE))) {
        return endpoint
    }

    val trimmedBaseUrl = baseUrl.trim()
    require(trimmedBaseUrl.isNotEmpty()) {
        "A baseUrl is required when using a relative endpoint ($endpoint)."
    }

    val normalizedEndpoint = if (endpoint.startsWith('/')) endpoint else "/$endpoint"
    return trimmedBaseUrl.removeSuffix("/") + normalizedEndpoint
}

private fun readErrorMessage(rawPayload: String): String? {
    if (rawPayload.isBlank()) {
        return null
    }

    val parsed = runCatching { Json.parseToJsonElement(rawPayload) }.getOrNull()
    val error = (parsed as? JsonObject)?.get("error") as? JsonPrimitive
    return error?.contentOrNull ?: rawPayload.takeIf { it.isNotBlank() }
}

private suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        enqueue(
            object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isCancelled) {
                        return
                    }
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

@Serializable
private data class VoiceRoomRequest(
    val initializationValues: JsonObject,
)

@Serializable
private data class ChatStartRequest(
    val initializationValues: JsonObject,
    val sendWelcomeMessage: Boolean = true,
)

@Serializable
private data class ChatSendRequest(
    val ticketId: String,
    val text: String,
    val messageId: String? = null,
)

@Serializable
private data class ChatEndRequest(
    val ticketId: String,
)

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
