# Keep default rules for WebView asset loading
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void onConsoleMessage(...);
}
