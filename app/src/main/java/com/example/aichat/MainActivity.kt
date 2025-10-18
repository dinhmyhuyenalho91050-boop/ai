package com.example.aichat

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var isContentReady = false
    private var lastImeDispatch: Pair<Int, Int>? = null
    private var baseViewportHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        setTheme(R.style.Theme_AIChatApp)
        super.onCreate(savedInstanceState)
        isContentReady = savedInstanceState != null
        splashScreen.setOnExitAnimationListener { provider ->
            val iconView = provider.iconView
            val targetView = iconView ?: provider.view
            targetView.animate()
                .setDuration(420)
                .setInterpolator(OvershootInterpolator())
                .scaleX(1.18f)
                .scaleY(1.18f)
                .alpha(0f)
                .withEndAction { provider.remove() }
                .start()
        }
        splashScreen.setKeepOnScreenCondition { !isContentReady }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        setupWebView()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
            isContentReady = true
        } else {
            webView.loadUrl("file:///android_asset/www/index.html")
        }

        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowUniversalAccessFromFileURLs = true
            allowFileAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.isHorizontalScrollBarEnabled = false
        webView.isVerticalScrollBarEnabled = false
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return false
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                isContentReady = true
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                isContentReady = true
            }
        }

        webView.addJavascriptInterface(AndroidStorageBridge(this), "AndroidStorage")

        ViewCompat.setOnApplyWindowInsetsListener(webView) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val systemInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topInset = systemInsets.top
            val systemBottom = systemInsets.bottom

            val rawHeight = when {
                view.height > 0 -> view.height
                view.measuredHeight > 0 -> view.measuredHeight
                else -> 0
            }

            val viewportWithoutIme = if (rawHeight > 0) {
                max(0, rawHeight - topInset - systemBottom)
            } else {
                0
            }

            if (viewportWithoutIme > 0) {
                if (baseViewportHeight == 0 || viewportWithoutIme > baseViewportHeight) {
                    baseViewportHeight = viewportWithoutIme
                } else if (imeInsets.bottom <= systemBottom) {
                    baseViewportHeight = viewportWithoutIme
                }
            }

            val baselineViewport = if (baseViewportHeight > 0) baseViewportHeight else viewportWithoutIme
            val consumedByResize = max(0, baselineViewport - viewportWithoutIme)
            val effectiveImeInset = max(0, imeInsets.bottom - consumedByResize)
            val bottomInset = systemBottom + effectiveImeInset

            view.setPadding(
                view.paddingLeft,
                topInset,
                view.paddingRight,
                bottomInset
            )

            val viewportHeight = if (rawHeight > 0) {
                max(0, rawHeight - topInset - bottomInset)
            } else {
                viewportWithoutIme
            }

            if (effectiveImeInset == 0 && viewportHeight > 0) {
                baseViewportHeight = viewportHeight
            }

            dispatchImeInsets(bottomInset, viewportHeight)

            insets
        }

        hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = ViewCompat.getWindowInsetsController(window.decorView) ?: return
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun dispatchImeInsets(bottomInset: Int, viewportHeight: Int) {
        val lastDispatch = lastImeDispatch
        val current = bottomInset to viewportHeight
        if (lastDispatch == current) {
            return
        }

        lastImeDispatch = current
        val script = "window.__NATIVE_IME__ && window.__NATIVE_IME__.update({bottom:$bottomInset,viewport:$viewportHeight});"
        webView.evaluateJavascript(script, null)
    }
}
