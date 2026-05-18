---
applyTo: "**"
---
<!-- Source: AGENTS.md -->

# Android SDK Code Review Instructions

Use these instructions for GitHub Copilot code review and coding-agent work in
this repository.

- Treat `gigasdk-livekit/` as the published SDK surface. Flag public API changes
  unless the pull request explicitly calls them out.
- Prioritize correctness around Android audio focus, audio routing, microphone
  permissions, coroutine lifecycle, LiveKit room cleanup, and user-visible
  error state.
- Check that Gradle and publishing changes preserve the Maven Central
  coordinate `ai.giga:gigasdk-livekit` and do not expose signing keys,
  credentials, or local release material.
- Verify that sample-app changes continue to work both against the project
  dependency and the Maven Local artifact via `-PuseMavenLocalSdk=true`.
- For GitHub Actions changes, check that required status check names stay stable
  because repository rules use those names as merge gates.
- For TypeScript reference-backend changes, require `npm run typecheck` and keep
  it clearly scoped as sample infrastructure, not a production backend.
- Prefer small, maintainable changes that match the existing Kotlin, Gradle,
  Compose, and TypeScript style.
- If local Android SDK validation is unavailable, call that out and rely on the
  Android Quality workflow for the full Android-backed checks.
