# Publishing the Android SDK

This repository is configured to publish `ai.giga:gigasdk-livekit` to private
GitHub Packages for customer distribution.

## Choose the target repository

GitHub's Maven registry uses repository-scoped permissions. That means package
access follows the target GitHub repository's access model.

There are two workable setups:

1. Publish to this source repository (`GigaML/AndroidGigaSDKLivekit`).
   Customers who need the package also need access to this repo.
2. Publish to a separate private "distribution-only" repository.
   Customers get access to that repo and package feed without needing access to
   the source repository.

The target feed is controlled by these properties in `gradle.properties`:

- `GITHUB_PACKAGES_OWNER`
- `GITHUB_PACKAGES_REPOSITORY`

## One-time setup

### 1. Keep the target repository private

The GitHub Packages Maven feed is private only if the backing repository is
private.

### 2. Create a GitHub token for publishing

If you publish to the same repository, GitHub Actions can use the built-in
`GITHUB_TOKEN`.

If you publish to a different private distribution repository, create a
personal access token (classic) that has:

- `write:packages`
- `read:packages`
- `repo` access to the target private repository

Then add these optional GitHub repository secrets:

- `GITHUB_PACKAGES_ACTOR`
- `GITHUB_PACKAGES_TOKEN`

If those secrets are omitted, the workflow falls back to the built-in
GitHub Actions token for same-repo publishing.

### 3. Tell customers how to read the package

Customers installing from GitHub Packages need:

- a GitHub username
- a personal access token (classic) with `read:packages`
- access to the target GitHub repository

## GitHub Actions release flow

Customer releases are published by `.github/workflows/publish-github-packages.yml`.

The workflow:

1. Resolves the release version from a Git tag like `v0.1.0`
2. Runs `:gigasdk-livekit:testDebugUnitTest` and `:gigasdk-livekit:assemble`
3. Publishes the `release` artifact to the configured GitHub Packages feed

## Publishing a release

1. Merge the release-ready changes into `main`.
2. Create and push a semver tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

3. Watch the GitHub Actions run complete successfully.
4. Share the repository URL, package coordinate, and customer auth instructions.

You can also trigger the workflow manually from GitHub Actions and provide a
`version` input if you need to publish without creating a tag first.

## Local verification

Use these commands before cutting a release:

```bash
./gradlew :gigasdk-livekit:testDebugUnitTest :gigasdk-livekit:assemble
./gradlew :gigasdk-livekit:publishToMavenLocal
```

For a local dry run against GitHub Packages task wiring, you can also run:

```bash
./gradlew :gigasdk-livekit:publishReleasePublicationToGitHubPackagesRepository
```

That command requires `gpr.user` and `gpr.key` Gradle properties or
`GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables.

## Customer install snippet

Once a release is live on the private GitHub Packages feed, customers can
install it with:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/GigaML/AndroidGigaSDKLivekit")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_USERNAME")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}

dependencies {
    implementation("ai.giga:gigasdk-livekit:<version>")
}
```
