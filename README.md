# Index Immersive Android App

This project wraps the provided `index.html` experience inside an Android application with:

- Edge-to-edge immersive fullscreen WebView rendering
- Automatic resizing when the soft keyboard is visible so input fields stay accessible
- Custom adaptive launcher icons and a gradient splash screen
- A GitHub Actions workflow that builds a release APK artifact on every push and pull request

## Project structure

```
app/
├── build.gradle.kts        # Android application module configuration
├── src/main/
│   ├── AndroidManifest.xml # Application manifest
│   ├── assets/index.html   # Web content loaded into the WebView
│   ├── java/com/example/indexapp/MainActivity.kt
│   └── res/                # Launcher icons, splash assets, layouts and themes
├── proguard-rules.pro
```

## Building locally

1. Install Android Studio (or the Android command line tools) with Android SDK Platform 34 and Build Tools 34.0.0.
2. From the project root, run:

   ```bash
   gradle assembleRelease
   ```

   The unsigned release APK will be available at `app/build/outputs/apk/release/`.

## Continuous integration

The workflow in `.github/workflows/android.yml` builds the release APK on GitHub Actions and uploads it as an artifact so it can be downloaded without committing binary files to the repository.
