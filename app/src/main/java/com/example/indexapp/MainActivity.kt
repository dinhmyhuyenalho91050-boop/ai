package com.example.indexapp

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity() {

    private var insetsController: WindowInsetsControllerCompat? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_App)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root_container)
        val webView = findViewById<WebView>(R.id.web_view)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        insetsController = WindowCompat.getInsetsController(window, root)?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        hideSystemBars()

        configureInsets(root)
        configureWebView(webView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    private fun configureInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomPadding = maxOf(systemBars.bottom, ime.bottom)
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.mediaPlaybackRequiresUserGesture = false

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }
        }

        webView.loadUrl("file:///android_asset/index.html")

        webView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                hideSystemBars()
            }
            false
        }
    }

    private fun hideSystemBars() {
        insetsController?.hide(WindowInsetsCompat.Type.systemBars())
    }
}
