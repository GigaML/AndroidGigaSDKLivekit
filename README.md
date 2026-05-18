# Giga Android SDK (LiveKit)

Native Android SDK for Giga voice and chat agent flows, built on top of [LiveKit](https://livekit.io/). Provides a Kotlin API for starting and managing voice rooms, sending and receiving chat messages, surfacing live transcripts, and handling the Android audio session correctly across speakerphone, wired headsets, and Bluetooth devices.

## Requirements

- Android `minSdk` 26 (Android 8.0)
- Compiled against `compileSdk` 35 (Android 15)
- Kotlin 1.9+, JDK 17
- A backend that implements the Giga voice and chat REST contract (see [Backend contract](#backend-contract))

## Install

Customer distribution is intended to happen through Maven Central. Once a public
release from this repository has been published, consuming apps only need
`mavenCentral()` plus the SDK coordinate:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencies {
    implementation("ai.giga:gigasdk-livekit:0.1.0")
}
```

> The coordinate will resolve after the first Maven Central release is
> published. Maintainers can use `./gradlew` for the full local build, test,
> and Maven Local publish flow before or between public releases.

Maintainers: see [PUBLISHING.md](PUBLISHING.md) for the one-time Central Portal,
GPG signing, and GitHub Actions setup used for public releases.

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
./gradlew
```

The default Gradle command runs `buildTestPublishToMavenLocal`: it builds the
SDK and sample, runs the SDK debug unit tests, and publishes the SDK to Maven
Local for downstream integration testing.

To verify that the sample consumes the published artifact instead of the local
project dependency, publish the SDK to Maven Local and rebuild the sample with
`useMavenLocalSdk` enabled:

```bash
./gradlew :gigasdk-livekit:publishToMavenLocal
./gradlew :sample:assembleDebug -PuseMavenLocalSdk=true
```

### Pull request quality checks

Pull requests that touch the Android SDK, sample, reference backend, or release
docs must pass the Android Quality workflow before merging. The workflow
builds, lints, tests, and publishes the SDK to Maven Local, verifies the sample
against that Maven Local artifact, typechecks the sample backend, runs
repository hygiene checks, and performs dependency review.

Dependency review is present in CI, but GitHub Dependency Graph must be enabled
for the repository before that check can enforce dependency policy. Until then,
the job reports the missing repository setting without blocking the rest of the
quality workflow.

Automated code review guidance lives in `AGENTS.md`,
`.github/copilot-instructions.md`, and `.github/instructions/`. The intended
GitHub ruleset for required checks, CODEOWNERS review, and automatic Copilot
code review is documented in `.github/REVIEW_AND_MERGE_GATES.md`.

Run the same core checks locally before opening a release-sensitive PR:

```bash
./gradlew :gigasdk-livekit:lintDebug
./gradlew :gigasdk-livekit:testDebugUnitTest
./gradlew :gigasdk-livekit:assemble :sample:assembleDebug
./gradlew :gigasdk-livekit:publishToMavenLocal
./gradlew :sample:assembleDebug -PuseMavenLocalSdk=true
cd server && npm ci && npm run typecheck
pre-commit run --all-files
```

### Publishing a Maven Central release

Public releases are published by GitHub Actions from tags that start with `v`
(for example `v0.1.0`). The workflow uses the tag value as `VERSION_NAME`, runs
the SDK checks, and publishes the `ai.giga:gigasdk-livekit` artifact to Maven
Central automatically.

See [PUBLISHING.md](PUBLISHING.md) for the one-time account, signing-key, and
GitHub secret setup.

### Publishing to Maven Local

```bash
./gradlew :gigasdk-livekit:publishToMavenLocal
```

This installs `ai.giga:gigasdk-livekit:0.1.0` into `~/.m2/repository`, which
downstream projects can consume by adding `mavenLocal()` to their repositories.
Plain `./gradlew` also runs this publish step after the default build and test
checks.

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
