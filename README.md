# AI Chat Android Native

An Android native remake of the AI Chat front-end. The app now renders the chat page with Android views instead of loading the bundled HTML through a WebView.

## What This Project Includes

- Edge-to-edge immersive Android chat screen
- Native header, session drawer, model selector, message cards, composer, settings dialog, and data layer
- Keyboard-aware layout through Android window insets and `adjustResize`
- Native API calls for OpenAI-compatible providers, DeepSeek, Kimi, Anthropic, Gemini, and Gemini proxy
- Native file-backed chat/session/preset storage plus JSON backup import/export
- A native app surface matching the original `AI Chat v9.3` web UI
- GitHub Actions workflow that builds a release APK

## Project Structure

```text
.
|-- .github/workflows/android.yml
|-- app/
|   |-- build.gradle.kts
|   `-- src/main/
|       |-- assets/index.html        # Original web UI kept as reference
|       |-- java/com/example/htmlapp/MainActivity.kt
|       |-- res/
|       `-- AndroidManifest.xml
|-- build.gradle.kts
|-- gradle.properties
`-- settings.gradle.kts
```

## Tech Stack

- Kotlin
- Android SDK 34
- Native Android Views
- AndroidX AppCompat, Activity, and Core
- Material Components theme
- OkHttp

## App Behavior

`MainActivity` builds the native AI Chat page:

- The top bar shows `AI Chat`, version, session list, and settings actions.
- The session drawer opens natively and mirrors the original side panel.
- The bottom composer includes the model tabs, multi-line input, and send button.
- Sending text persists the user message, calls the selected model provider from Kotlin, and stores the assistant reply.
- The settings dialog recreates the original model preset, prompt, and backup tabs as native controls.
- Backup import/export writes Android-native JSON files compatible with the original `version: 9.3` format.

Advanced web-only helpers such as complex multi-step runner editing remain represented in the backup schema so later native UI expansion can load existing data without losing it.

## Requirements

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Gradle 8.7 or another compatible local Gradle installation

This repository does not include the Gradle wrapper JAR, so builds should use a local Gradle install or CI.

## Build

```bash
gradle assembleRelease
```

For a debug build:

```bash
gradle assembleDebug
```

The generated APK is typically written to:

```text
app/build/outputs/apk/release/app-release.apk
```

## Release Signing

Release builds use a fixed signing key when these Gradle properties or environment variables are set:

```text
AI_CHAT_KEYSTORE_FILE
AI_CHAT_KEYSTORE_TYPE
AI_CHAT_KEYSTORE_PASSWORD
AI_CHAT_KEY_ALIAS
AI_CHAT_KEY_PASSWORD
```

GitHub Actions restores `AI_CHAT_KEYSTORE_BASE64` from repository secrets and signs the uploaded release APK with that stable key. CI release builds require the signing secrets so every uploaded APK keeps the same certificate and can update an existing install. Release builds no longer fall back to debug signing; local development builds can still use `assembleDebug`.

## Notes

- The app currently uses `com.example.htmlapp` as its package namespace and application ID.
- `app/src/main/assets/index.html` is retained only as the source UI reference for this migration stage.
- Cleartext traffic is still enabled through `network_security_config.xml` for the later API integration stage.
