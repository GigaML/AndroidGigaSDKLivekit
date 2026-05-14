package com.gigaml.android.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Backend-provided configuration returned from `GET /api/config`.
 *
 * Describes which capabilities (`chat`, `voice`) the backend supports,
 * the default agent identifiers that will be used when a session
 * request does not specify its own, and any preset initialization
 * options the host app may surface as a picker.
 */
@Serializable
data class AppConfigResponse(
    val chatEndpoint: String,
    val defaultAgentId: String? = null,
    val defaultAgentTemplateId: String? = null,
    val initializationOptions: List<InitializationOption> = emptyList(),
    val roomEndpoint: String,
    val supports: Supports = Supports(),
) {
    /** Capability flags reported by the backend. */
    @Serializable
    data class Supports(
        val chat: Boolean = true,
        val voice: Boolean = true,
    )
}

/**
 * A preset bundle of initialization values that the host app can
 * surface to users (e.g. "VIP support", "Spanish") and pass to
 * [GigaVoiceSessionManager.start] or [GigaChatSessionManager.start].
 */
@Serializable
data class InitializationOption(
    val id: String,
    val label: String,
    val description: String? = null,
    val values: JsonObject = buildJsonObject {},
)

/**
 * Response from `POST /api/voice/create-room`. Contains the LiveKit
 * server URL and short-lived participant token used to join the room.
 */
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

/**
 * Response from `POST /api/chat/start`. `ticketId` is the handle used
 * by subsequent [ChatSendResponse] and end-session requests.
 */
@Serializable
data class ChatStartResponse(
    val agentId: String? = null,
    val agentTemplateId: String? = null,
    val ticketId: String,
    val welcomeMessage: String? = null,
)

/**
 * A single chat turn returned by the backend. `imageUrls` is parsed
 * out of the message body when the backend sends inline image markup.
 */
@Serializable
data class ChatMessage(
    val messageId: String? = null,
    val role: String,
    val text: String,
    val imageUrls: List<String> = emptyList(),
)

/** Response from `POST /api/chat/send`. */
@Serializable
data class ChatSendResponse(
    val message: ChatMessage,
    val responseType: String,
)

/** Response from `POST /api/chat/end`. */
@Serializable
data class ChatCloseResponse(
    val message: String? = null,
    val success: Boolean,
)

/**
 * Endpoint paths used by [GigaApiClient]. Override individual fields
 * if your backend deviates from the reference contract.
 */
data class GigaApiEndpoints(
    val closeChatSession: String = "/api/chat/end",
    val config: String = "/api/config",
    val createVoiceRoom: String = "/api/voice/create-room",
    val sendChatText: String = "/api/chat/send",
    val startChatSession: String = "/api/chat/start",
)

/**
 * Configuration for [GigaApiClient].
 *
 * @property baseUrl Origin of the backend that implements the Giga
 *   REST contract. Required.
 * @property endpoints Override endpoint paths if your backend deviates
 *   from the reference contract.
 * @property headers Static headers applied to every request.
 * @property headerProvider Optional `suspend` callback invoked once per
 *   request, after the static headers. Returned values override the
 *   static ones. Use this for short-lived authentication tokens (JWTs,
 *   signed timestamps) that must be fetched on demand.
 * @property connectTimeoutMillis OkHttp `connectTimeout`. Defaults to 10s.
 * @property readTimeoutMillis OkHttp `readTimeout`. Defaults to 30s.
 * @property callTimeoutMillis OkHttp total-call timeout. Defaults to 60s.
 *   Prevents a hung backend from leaking the calling coroutine.
 */
data class GigaApiClientConfig(
    val baseUrl: String,
    val endpoints: GigaApiEndpoints = GigaApiEndpoints(),
    val headers: Map<String, String> = emptyMap(),
    val headerProvider: (suspend () -> Map<String, String>)? = null,
    val connectTimeoutMillis: Long = 10_000L,
    val readTimeoutMillis: Long = 30_000L,
    val callTimeoutMillis: Long = 60_000L,
)

/**
 * HTTP client for the Giga voice and chat REST contract.
 *
 * All methods are `suspend` and throw `IOException` (or a subclass)
 * when the backend returns a non-2xx response or the connection fails.
 * Callers are responsible for catching and surfacing errors to the UI.
 *
 * Instances are thread-safe and intended to be long-lived — share one
 * client across your voice and chat managers.
 */
class GigaApiClient(
    private val config: GigaApiClientConfig,
    private val httpClient: OkHttpClient,
    private val json: Json,
) {
    constructor(config: GigaApiClientConfig) : this(
        config = config,
        httpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(config.callTimeoutMillis, TimeUnit.MILLISECONDS)
            .build(),
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
        val headers = buildHeaders(hasBody = false)
        val request = Request.Builder()
            .url(resolveEndpoint(config.baseUrl, endpoint))
            .headers(headers)
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
        val headers = buildHeaders(hasBody = bodyJson != null)
        val request = Request.Builder()
            .url(resolveEndpoint(config.baseUrl, endpoint))
            .headers(headers)
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
        val payload = withContext(Dispatchers.IO) {
            response.use { nextResponse ->
                val nextPayload = nextResponse.body?.string().orEmpty()
                if (!nextResponse.isSuccessful) {
                    throw IOException(readErrorMessage(nextPayload) ?: fallbackMessage)
                }

                if (nextPayload.isBlank()) {
                    throw IOException(fallbackMessage)
                }

                nextPayload
            }
        }

        return json.decodeFromString(payload)
    }

    private suspend fun buildHeaders(hasBody: Boolean): Headers {
        val builder = Headers.Builder()
            .set("Accept", "application/json")

        if (hasBody) {
            builder.set("Content-Type", "application/json")
        }

        config.headers.forEach { (name, value) ->
            builder.set(name, value)
        }

        config.headerProvider?.invoke()?.forEach { (name, value) ->
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
