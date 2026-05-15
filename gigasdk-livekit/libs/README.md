`audioswitch-fork.jar` is a vendored copy of the classes from
`com.github.davidliu:audioswitch:89582c47c9a04c62f90aa5e57251af4800a62c9a`.

Why it exists:
- `io.livekit:livekit-android` still depends on that forked AudioSwitch artifact
  from JitPack.
- We vendor the jar into this SDK so public consumers only need `mavenCentral()`
  and do not have to add `jitpack.io` themselves.

Source and license:
- Upstream source: `https://github.com/twilio/audioswitch`
- Published fork coordinate: `com.github.davidliu:audioswitch:89582c47c9a04c62f90aa5e57251af4800a62c9a`
- License: Apache 2.0

If you ever need to refresh this jar, download the upstream AAR from JitPack and
extract `classes.jar` into this directory as `audioswitch-fork.jar`.
