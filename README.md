# AI Chat Android Wrapper

An Android app that packages a bundled AI chat web app inside a full-screen WebView shell.

The HTML client lives in `app/src/main/assets/index.html` and is loaded through `WebViewAssetLoader`, so the UI ships inside the APK while still being able to call external AI APIs over the network.

## What This Project Includes

- Edge-to-edge Android WebView container with immersive system bar handling
- Keyboard-aware layout and IME insets support
- Bundled HTML chat UI with local storage support
- Native bridge for file picking and backup export
- Background and foreground lifecycle syncing between Android and the web app
- GitHub Actions workflow that builds a release APK

## Project Structure

```text
.
|-- .github/workflows/android.yml
|-- app/
|   |-- build.gradle.kts
|   `-- src/main/
|       |-- assets/index.html
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
- Android WebView
- AndroidX AppCompat, Activity, Lifecycle, WebKit
- Material Components
- OkHttp

## App Behavior

`MainActivity` hosts the web app in a single `WebView` and configures:

- JavaScript, DOM storage, database storage, and mixed content support
- `WebViewAssetLoader` for loading local app assets from `appassets.androidplatform.net`
- file chooser support for uploads from the HTML app
- a JavaScript bridge named `HtmlAppNative`
- backup export to Downloads on supported Android versions
- visibility and streaming state coordination so the WebView behaves better in background/foreground transitions

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

GitHub Actions restores `AI_CHAT_KEYSTORE_BASE64` from repository secrets and signs the uploaded release APK with that stable key. If signing secrets are absent, the build falls back to the debug signing config for development builds.

## Continuous Integration

GitHub Actions builds the release APK on pushes and pull requests targeting `main` or `work`.

Workflow file:

- `.github/workflows/android.yml`

Uploaded artifact:

- `release-apk`

## Notes

- The app currently uses `com.example.htmlapp` as its package namespace and application ID.
- Cleartext traffic is enabled through `network_security_config.xml`.
- The chat UI title in the bundled HTML currently identifies itself as `AI Chat v9.3`.

## Next Improvements

1. Replace the placeholder package name and app ID with a production identifier.
2. Add a Gradle wrapper so local builds are easier and more reproducible.
3. Document required API configuration for the bundled chat client.
4. Add screenshots and release installation steps to this README.
