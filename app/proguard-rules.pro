# Keep the WebView JavaScript interface names
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
