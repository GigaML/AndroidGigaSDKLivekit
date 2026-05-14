# Giga Android SDK (LiveKit)

Native Android SDK for Giga voice and chat agent flows, built on top of [LiveKit](https://livekit.io/). Provides a Kotlin API for starting and managing voice rooms, sending and receiving chat messages, surfacing live transcripts, and handling the Android audio session correctly across speakerphone, wired headsets, and Bluetooth devices.

## Requirements

- Android `minSdk` 26 (Android 8.0)
- Compiled against `compileSdk` 35 (Android 15)
- Kotlin 1.9+, JDK 17
- A backend that implements the Giga voice and chat REST contract (see [Backend contract](#backend-contract))

## Install

The intended customer distribution model for this SDK is source access through
this repository. Grant read-only access to `GigaML/AndroidGigaSDKLivekit`, have
the customer clone the repo, and follow the integration/setup docs from source.

The recommended integration pattern is to include `gigasdk-livekit/` as a local
Gradle module in the host Android app:

```kotlin
// settings.gradle.kts
include(":gigasdk-livekit")
project(":gigasdk-livekit").projectDir =
    file("../AndroidGigaSDKLivekit/gigasdk-livekit")

// app/build.gradle.kts
dependencies {
    implementation(project(":gigasdk-livekit"))
}
```

Adjust the relative path to wherever the checked-out SDK repository lives on
disk. The included `sample/` app in this repository demonstrates the same
project-module integration pattern.

The library declares the permissions it needs (`INTERNET`, `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `BLUETOOTH_CONNECT`) and they are merged into the host app's manifest automatically. The host app is still responsible for requesting `RECORD_AUDIO` at runtime when starting a voice session.

## Quick start

```kotlin
import com.gigaml.android.api.GigaApiClient
import com.gigaml.android.api.GigaApiClientConfig
import com.gigaml.android.api.emptyInitializationValues
import com.gigaml.android.chat.GigaChatSessionManager
import com.gigaml.android.voice.GigaVoiceSessionManager
import kotlinx.coroutines.flow.collect

// 1. Build the API client.
val apiClient = GigaApiClient(
    GigaApiClientConfig(
        baseUrl = "https://your-backend.example.com",
        // Optional: per-request auth header.
        headerProvider = { mapOf("Authorization" to "Bearer ${tokenStore.fresh()}") },
    ),
)

// 2. Start a voice session.
val voice = GigaVoiceSessionManager(applicationContext, apiClient)
lifecycleScope.launch {
    voice.start(emptyInitializationValues())
    voice.state.collect { state ->
        // Render connectionState, transcript, isMicrophoneEnabled, error.
    }
}

// 3. Or start a chat session.
val chat = GigaChatSessionManager(apiClient)
lifecycleScope.launch {
    chat.start(emptyInitializationValues())
    chat.send("Hi, I need help with my order.")
}
```

Before calling `voice.start(...)`, the host app must:

1. Have been granted `Manifest.permission.RECORD_AUDIO`.
2. Be prepared to call `voice.close()` when the lifecycle owner is destroyed.

The same applies to `chat.close()` for chat sessions.

## API surface

### `GigaApiClient`

Wraps the backend contract. Configure via `GigaApiClientConfig`:

| Field | Default | Purpose |
| --- | --- | --- |
| `baseUrl` | required | Origin of your backend that implements the Giga REST contract. |
| `endpoints` | `GigaApiEndpoints()` | Override individual endpoint paths if your backend differs from the reference contract. |
| `headers` | `emptyMap()` | Static headers added to every request. |
| `headerProvider` | `null` | `suspend () -> Map<String, String>` invoked per request, after the static headers. Use this for short-lived auth tokens. |
| `connectTimeoutMillis` | `10_000` | OkHttp connect timeout. |
| `readTimeoutMillis` | `30_000` | OkHttp read timeout. |
| `callTimeoutMillis` | `60_000` | OkHttp total call timeout. Prevents a hung backend from leaking the calling coroutine. |

### `GigaVoiceSessionManager`

Owns a LiveKit room and the Android audio session.

- `start(initializationValues): Boolean` — creates a voice room via the backend, connects, enables the microphone, returns `true` on success.
- `toggleMicrophone()` — flips local mute state.
- `stop()` — disconnects the room and restores the system audio mode/speakerphone state.
- `close()` — calls `stop()` and cancels the internal coroutine scope. Call this from your `onDestroy` / `DisposableEffect.onDispose`.
- `state: StateFlow<VoiceSessionState>` — `connectionState`, `transcript`, `isMicrophoneEnabled`, `error`.

The session listens for `AudioFocusChange` events: an incoming call or alarm automatically mutes the mic, and the GAIN event re-enables it (only if the SDK itself paused — a manual mute by the user is preserved). It also tracks `AudioDeviceCallback`s, so plugging in a wired headset mid-call switches output off the speaker.

### `GigaChatSessionManager`

REST-backed chat session.

- `start(initializationValues): Boolean` — issues `POST /api/chat/start`, populates the transcript with any welcome message.
- `send(text): Boolean` — sends a user message, optimistically appends it, replaces it with the server response.
- `end(): Boolean` — issues `POST /api/chat/end` and clears state.
- `state: StateFlow<GigaChatSessionState>` — `transcript`, `isSending`, `isClosing`, `isStarting`, `error`, `ticketId`.

## Backend contract

The SDK expects a backend exposing:

- `GET /api/config`
- `POST /api/voice/create-room`
- `POST /api/chat/start`
- `POST /api/chat/send`
- `POST /api/chat/end`

The contract matches the React Native reference projects. The exact shapes are defined by the response classes in `com.gigaml.android.api` (`AppConfigResponse`, `RoomResponse`, `ChatStartResponse`, `ChatSendResponse`, `ChatCloseResponse`).

A working reference backend (TypeScript/Express) lives in `server/` and proxies to Giga's hosted endpoints — see [Reference backend](#reference-backend) below.

## Sample app

The `sample/` module is a minimal Jetpack Compose app that exercises the SDK and demonstrates the integration patterns. To run it:

1. Open this repository in Android Studio.
2. Let Gradle sync.
3. Create an emulator (Pixel 8, API 35, ARM64 system image).
4. Run the `sample` configuration.

The sample defaults to `http://10.0.2.2:8787` (the emulator alias for the host machine's `localhost`), where the reference backend listens.

### Build from the terminal

```bash
./gradlew :gigasdk-livekit:assemble :sample:assembleDebug
```

## Reference backend

`server/` contains an Express server that implements the contract above by proxying to Giga's hosted endpoints. **It is a reference implementation, not a production backend.** Specifically:

- CORS is wide open (`cors()` with default options).
- It runs via `tsx` rather than compiled JavaScript.
- It does not implement rate limiting, request signing, or per-user authentication.

To run it locally for development:

```bash
cd server
cp .env.example .env
# Fill in GIGA_API_KEY and exactly one of GIGA_AGENT_ID / GIGA_AGENT_TEMPLATE_ID.
npm install
npm run dev
```

Verify with:

```bash
curl http://127.0.0.1:8787/api/config
```

## Local HTTP and `usesCleartextTraffic`

The sample app enables cleartext HTTP globally so the emulator can talk to `http://10.0.2.2:8787`. For production, use HTTPS and either remove `android:usesCleartextTraffic` or scope it via a `network_security_config.xml` that only permits cleartext for `localhost`/`10.0.2.2` in debug builds.

## License

Apache 2.0. See [LICENSE](LICENSE).
