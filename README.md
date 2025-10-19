# AI Chat Android Wrapper

This project packages the existing AI Chat HTML experience inside a full-screen Android WebView container with edge-to-edge layout, proper IME handling and cleartext HTTP support. The HTML lives in `app/src/main/assets/index.html` so it is bundled offline inside the APK.

## Development

```bash
# Build release artifact
gradle assembleRelease
```

The project does not ship with the Gradle wrapper JAR to avoid adding binary files. Use a local Gradle installation or the `gradle/gradle-build-action` in CI (see the workflow below).

## Continuous Integration

The [GitHub Actions workflow](.github/workflows/android.yml) builds the release APK on every push and pull request targeting `main` or `work`, then uploads the artifact named `release-apk`.

## Runtime Features

* Edge-to-edge immersive WebView with dynamic system bar and IME insets.
* Keyboard-aware layout driven by the VirtualKeyboard API (with fallbacks).
* Cleartext HTTP allowed via `network_security_config.xml`.
* JavaScript, DOM storage, and mixed content enabled for the bundled page.
