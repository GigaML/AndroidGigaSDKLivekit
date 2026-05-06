# Android Giga LiveKit

Native Android wrapper and sample app for Giga voice and chat flows built on top of LiveKit.

## What Is In This Repo

- `gigasdk-livekit/`: reusable Android library module
- `sample/`: minimal Jetpack Compose sample app that exercises the library
- `server/`: local example backend for the sample app

The sample app mirrors the same backend contract used by the React Native reference projects:

- `GET /api/config`
- `POST /api/voice/create-room`
- `POST /api/chat/start`
- `POST /api/chat/send`
- `POST /api/chat/end`

## Requirements

- Android Studio
- JDK 17
- Android SDK
  - Android SDK Platform 34
  - Android SDK Build-Tools 34.0.0
  - Android SDK Platform-Tools
  - Android Emulator

If Android Studio prompts for paths, use:

- JDK: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`
- Android SDK: `/Users/<your-user>/Library/Android/sdk`

## Open The Project

Open `AndroidGigaSDKLivekit` itself in Android Studio, not the parent `gigaml` folder.

Gradle should use the checked-in wrapper and sync automatically.

## Build From Terminal

```bash
cd AndroidGigaSDKLivekit
./gradlew :gigasdk-livekit:assemble :sample:assembleDebug
```

## Run The Sample App

1. Open `AndroidGigaSDKLivekit` in Android Studio.
2. Let Gradle sync.
3. Create an emulator, for example:
   - `Pixel 8`
   - `Android 14 / API 34`
   - `ARM 64 v8a` system image
4. Select the `sample` run configuration.
5. Run the app on the emulator.

The sample app defaults to:

- Android emulator backend URL: `http://10.0.2.2:8787`

`10.0.2.2` is the emulator alias for your host machine's `localhost`.

## Backend Requirement

This repo includes a local example backend under `server/`. To get voice and chat working, you need to run that backend on port `8787` with your Giga credentials and one agent identifier configured.

Create `server/.env` from `server/.env.example` and set:

```dotenv
PORT=8787
GIGA_CHAT_URL=https://agents.gigaml.com/v1/chat/rest
GIGA_ROOM_URL=https://giga-support.prod-aws-porter.giga.ai/agents/external/voice/get_room_id
GIGA_API_KEY=your_giga_org_api_key
GIGA_AGENT_ID=
GIGA_AGENT_TEMPLATE_ID=agent_template_your_default_template
# Optional
# GIGA_INITIALIZATION_OPTIONS=[{"id":"default","label":"Default session","description":"No extra initialization values","values":{}},{"id":"vip","label":"VIP support","description":"Routes the launcher to a VIP flow","values":{"tier":"vip"}}]
```

Use exactly one of:

- `GIGA_AGENT_ID`
- `GIGA_AGENT_TEMPLATE_ID`

Use the `get_room_id` room endpoint when you want voice room creation to work with `GIGA_AGENT_TEMPLATE_ID`.

If you want the launcher to show a dropdown instead of raw initialization JSON, set `GIGA_INITIALIZATION_OPTIONS` to a JSON array of preset objects. Each preset supports `id`, `label`, optional `description`, and `values`.

Then start the reference backend:

```bash
cd server
npm install
npm run dev
```

Quick verification from your Mac:

```bash
curl http://127.0.0.1:8787/api/config
```

If that returns JSON, the Android sample should be able to connect.

## Local HTTP Note

The sample app allows cleartext HTTP traffic so the emulator can talk to a local development backend at `http://10.0.2.2:8787`.

For production or shared environments, prefer an HTTPS backend.
