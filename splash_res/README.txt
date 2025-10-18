Splash Screen Assets (Android)
=================================
What's included:
- drawable*/splash_icon.png (mdpi~xxxhdpi) — transparent icon centered with padding
- drawable/splash_background.xml — warm gradient background
- drawable/launch_screen_legacy.xml — pre-Android 12 layer-list splash
- values-v31/themes.xml — Android 12+ theme snippet
- values/themes.xml — legacy theme snippet

How to use:
1) Copy folders into app/src/main/res/
2) Android 12+:
   - Use Theme.App (values-v31/themes.xml) or merge items into your existing theme.
3) Pre-Android 12:
   - Set Activity theme to Theme.App.Launch in AndroidManifest.xml for the launcher activity.
   - After content is ready, switch to Theme.App in code (or via manifest for other activities).

Optional:
- Add a branding image at bottom (Android 12+) by providing a drawable and uncommenting windowSplashScreenBrandingImage.
