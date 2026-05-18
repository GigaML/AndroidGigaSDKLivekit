# Agent Instructions

This is the repo-wide instruction source for AI coding agents and automated
code review.

## Repo Shape

- `gigasdk-livekit/` is the published Android SDK module.
- `sample/` is the Android sample app used to verify SDK integration.
- `server/` is a local TypeScript reference backend for sample development.
- Public SDK releases are Maven Central releases; keep release and publishing
  changes aligned with `PUBLISHING.md`.

## Engineering Policy

- Preserve the public SDK API unless the Linear ticket explicitly asks for an
  API change.
- Prefer small, reviewable changes over broad refactors.
- Keep Android behavior compatible with `minSdk` 26 and `compileSdk` 35.
- Use existing Kotlin, Gradle, and Compose patterns before adding new
  abstractions or plugins.
- Do not commit local secrets, signing keys, Maven Central credentials, or GPG
  material. Release secrets belong in GitHub Actions secrets only.

## Android Review Focus

When reviewing Android SDK changes, check for:

- Runtime permission assumptions, especially `RECORD_AUDIO` and
  `BLUETOOTH_CONNECT`.
- Audio focus, audio mode, routing, mute state, and cleanup behavior.
- Coroutine cancellation and lifecycle leaks in voice/chat session managers.
- Network timeout, auth header, and error-surfacing behavior in API clients.
- Backwards-compatible Gradle publishing metadata and Maven coordinates.
- Sample app coverage for any integration-facing SDK behavior.

## Required Validation

Run the smallest relevant subset locally, and expect CI to run the full Android
Quality workflow on pull requests:

```bash
./gradlew :gigasdk-livekit:lintDebug
./gradlew :gigasdk-livekit:testDebugUnitTest
./gradlew :gigasdk-livekit:assemble :sample:assembleDebug
./gradlew :gigasdk-livekit:publishToMavenLocal
./gradlew :sample:assembleDebug -PuseMavenLocalSdk=true
cd server && npm ci && npm run typecheck
pre-commit run --all-files
```

If the local machine does not have the Android SDK configured, say so clearly
and rely on GitHub Actions for Android SDK-backed validation.

## Linear

- Default Linear team: Engineering.
- Link implementation branches, commits, and pull requests back to the Linear
  ticket.
- For follow-up work, include context, acceptance criteria, risks, and rollout
  notes in the issue.
