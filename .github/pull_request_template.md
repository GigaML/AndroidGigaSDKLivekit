## Summary

- 

## Testing

- [ ] `./gradlew :gigasdk-livekit:lintDebug`
- [ ] `./gradlew :gigasdk-livekit:testDebugUnitTest`
- [ ] `./gradlew :gigasdk-livekit:assemble :sample:assembleDebug`
- [ ] `./gradlew :gigasdk-livekit:publishToMavenLocal`
- [ ] `./gradlew :sample:assembleDebug -PuseMavenLocalSdk=true`
- [ ] `cd server && npm ci && npm run typecheck`
- [ ] `pre-commit run --all-files`

## Review Notes

- [ ] Copilot/code-agent review is useful for this change, or the PR explains
      why it is docs-only/trivial.
- [ ] Public SDK API changes are called out explicitly, if any.
- [ ] Release/publishing changes are reflected in `PUBLISHING.md`, if any.
