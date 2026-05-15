# Publishing the Android SDK

This repository is configured to publish `ai.giga:gigasdk-livekit` to Maven
Central for public customer distribution.

## One-time setup

### 1. Create the Maven Central namespace

1. Create or log in to a [Central Portal](https://central.sonatype.com/) account.
2. Register and verify the `ai.giga` namespace.
3. Wait for the namespace to be approved before attempting the first release.

The namespace must match a domain you control. If `ai.giga` cannot be approved,
update `GROUP` in `gradle.properties` before publishing.

### 2. Generate a Central Portal user token

Create a publishing token in the Central Portal UI. Save the generated username
and password as GitHub repository secrets:

- `MAVEN_CENTRAL_USERNAME`
- `MAVEN_CENTRAL_PASSWORD`

### 3. Create and distribute a GPG signing key

Maven Central requires signed release artifacts.

1. Create a GPG key pair.
2. Publish the public key to a public keyserver.
3. Export the private key in ASCII-armored format:

```bash
gpg --export-secret-keys --armor <key-id>
```

Save the signing material as GitHub repository secrets:

- `SIGNING_PRIVATE_KEY`
- `SIGNING_PASSWORD`
- `SIGNING_KEY_ID` (optional, but recommended)

Signing is enabled by the GitHub Actions workflow at publish time, so local
`publishToMavenLocal` runs do not require the private key to be installed on
every maintainer machine.

## GitHub Actions release flow

Public releases are published by `.github/workflows/publish-maven-central.yml`.

The workflow:

1. Resolves the release version from a Git tag like `v0.1.0`
2. Runs `:gigasdk-livekit:testDebugUnitTest` and `:gigasdk-livekit:assemble`
3. Publishes the SDK to Maven Central

## Publishing a release

1. Merge the release-ready changes into `main`.
2. Create and push a semver tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

3. Watch the GitHub Actions run complete successfully.
4. Wait for Maven Central indexing. New artifacts can take 10 to 30 minutes to
   become visible to consumers.

You can also trigger the workflow manually from GitHub Actions and provide a
`version` input if you need to publish without creating a tag first.

## Local verification

Use these commands before cutting a release:

```bash
./gradlew :gigasdk-livekit:testDebugUnitTest :gigasdk-livekit:assemble
./gradlew :gigasdk-livekit:publishToMavenLocal
```

## Customer install snippet

Once a release is live on Maven Central, customers can install it with:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

dependencies {
    implementation("ai.giga:gigasdk-livekit:<version>")
}
```
