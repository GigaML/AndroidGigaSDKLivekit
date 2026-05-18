# Publishing the Android SDK

This repository is configured to publish `ai.giga:gigasdk-livekit` to Maven
Central for public customer distribution.

## Current status

Michael Sugas (`michael.sugas@giga.ai`) has already completed the one-time
publishing setup described below. Questions about this release flow should go
through Michael first.

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

Use a dedicated release signing key for this SDK, not a personal GPG key. The
commands below keep the key in a separate local GPG home, create a random
passphrase, save that passphrase to macOS Keychain, publish only the public key,
and pipe the private key directly into GitHub Secrets without writing it to
disk.

Run from the repository root on a Mac:

```bash
brew install gnupg gh

gh auth status || gh auth login

export GNUPGHOME="$HOME/.gnupg-gigasdk-maven-central"

if [ -d "$GNUPGHOME" ] && [ -e "$GNUPGHOME/pubring.kbx" ]; then
  echo "ERROR: $GNUPGHOME already has keys. Stop to avoid creating duplicates."
  exit 1
fi

mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"

SIGNING_NAME="GigaML Maven Central Release"
printf "Signing key email, use an org email you control: "
read -r SIGNING_EMAIL

if [ -z "$SIGNING_EMAIL" ]; then
  echo "ERROR: signing key email is required"
  exit 1
fi

SIGNING_PASSWORD="$(openssl rand -base64 48)"
security add-generic-password \
  -a "$USER" \
  -s "gigasdk-maven-central-gpg-signing-password" \
  -w "$SIGNING_PASSWORD" \
  -U

printf '%s' "$SIGNING_PASSWORD" | gpg \
  --batch \
  --pinentry-mode loopback \
  --passphrase-fd 0 \
  --quick-generate-key "$SIGNING_NAME <$SIGNING_EMAIL>" rsa4096 sign 2y

KEY_FPR="$(gpg --with-colons --list-secret-keys --fingerprint "$SIGNING_EMAIL" | awk -F: '$1 == "fpr" { print $10; exit }')"

if [ -z "$KEY_FPR" ]; then
  echo "ERROR: could not find generated signing key fingerprint"
  exit 1
fi

SIGNING_KEY_ID="$(printf '%s\n' "$KEY_FPR" | awk '{ print substr($0, length($0)-7) }')"

echo "Fingerprint: $KEY_FPR"
echo "SIGNING_KEY_ID: $SIGNING_KEY_ID"

printf 'gigasdk signing test\n' > /tmp/gigasdk-signing-test.txt
printf '%s' "$SIGNING_PASSWORD" | gpg \
  --batch \
  --yes \
  --pinentry-mode loopback \
  --passphrase-fd 0 \
  --local-user "$KEY_FPR" \
  --armor \
  --detach-sign /tmp/gigasdk-signing-test.txt
gpg --verify /tmp/gigasdk-signing-test.txt.asc /tmp/gigasdk-signing-test.txt
rm /tmp/gigasdk-signing-test.txt /tmp/gigasdk-signing-test.txt.asc

gpg --keyserver hkps://keyserver.ubuntu.com --send-keys "$KEY_FPR"
gpg --keyserver hkps://keys.openpgp.org --send-keys "$KEY_FPR" || true

printf '%s' "$SIGNING_KEY_ID" | gh secret set SIGNING_KEY_ID
printf '%s' "$SIGNING_PASSWORD" | gh secret set SIGNING_PASSWORD

printf '%s' "$SIGNING_PASSWORD" | gpg \
  --batch \
  --yes \
  --pinentry-mode loopback \
  --passphrase-fd 0 \
  --armor \
  --export-secret-keys "$KEY_FPR" | gh secret set SIGNING_PRIVATE_KEY

gh secret list | grep SIGNING
```

The commands above create or update these GitHub repository secrets:

- `SIGNING_PRIVATE_KEY`
- `SIGNING_PASSWORD`
- `SIGNING_KEY_ID` (optional, but recommended)

Keep the `$GNUPGHOME` directory and the Keychain item available to release
maintainers, or store an encrypted backup in the company password manager. Do
not commit the private key, paste it into chat, or reuse the signing password
for anything else.

Wait a few minutes for the public keyserver upload to propagate before cutting
the first release.

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
semver `version` input without the leading `v` if you need to publish without
creating a tag first.

## Local verification

Use the default Gradle command before cutting a release:

```bash
./gradlew
```

The default command runs `buildTestPublishToMavenLocal`, which builds the SDK
and sample, runs the SDK debug unit tests, and publishes
`ai.giga:gigasdk-livekit` to Maven Local.

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
