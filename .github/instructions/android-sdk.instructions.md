---
applyTo: "{gigasdk-livekit,sample}/**/*.{kt,kts,xml,pro}"
---

# Android SDK Review Focus

- Preserve the public SDK API unless the PR explicitly documents an API change.
- Check permission handling for `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, and
  `BLUETOOTH_CONNECT`.
- Review audio focus, audio mode, speakerphone, wired headset, and Bluetooth
  routing behavior carefully.
- Look for coroutine leaks, missing cancellation, and LiveKit room cleanup gaps.
- Require the Maven Local sample-consumption gate when SDK publishing or Gradle
  dependency wiring changes.
- Keep Android compatibility aligned with `minSdk` 26 and `compileSdk` 35.
