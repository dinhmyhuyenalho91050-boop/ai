# Keep default settings for WebView clients and javascript interfaces
-keepclassmembers class * extends android.webkit.WebViewClient {
    public *;
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public *;
}

-keep class com.example.htmlapp.** { *; }
