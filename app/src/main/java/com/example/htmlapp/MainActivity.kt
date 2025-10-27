package com.example.htmlapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.max
import kotlin.text.Charsets

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var downloadBridge: DownloadBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<android.content.Intent>
    private var isAppInForeground = true
    private var isStreaming = false
    private var areTimersPaused = false
    private var areImagesBlocked = false
    private var lastVisibilityState: String? = null
    private var lastRendererPriority: Int? = null
    private var lastRendererWaived: Boolean? = null
    private var isPageReady = false
    private var pendingVisibilityState: String? = null
    private val appVisibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleAppVisibility(true)
        }

        override fun onStop(owner: LifecycleOwner) {
            handleAppVisibility(false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = findViewById<View>(R.id.root_container)
        webView = findViewById(R.id.webview)
        isPageReady = false

        fileChooserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            if (callback == null) {
                return@registerForActivityResult
            }

            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                val uris = mutableListOf<Uri>()
                val clipData = data.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                    }
                } else {
                    data.data?.let { uris.add(it) }
                }
                callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
            } else {
                callback.onReceiveValue(null)
            }
            filePathCallback = null
        }

        configureWebView()
        setupWindowInsets(root)
        setupBackNavigation()
        handleAppVisibility(true)
        notifyWebVisibility("foreground")
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibilityObserver)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")
        }
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(this))
            .build()

        val settings = webView.settings
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            offscreenPreRaster = false
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        } else if (WebViewFeature.isFeatureSupported(WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            WebSettingsCompat.setForceDark(settings, WebSettingsCompat.FORCE_DARK_ON)
        }

        webView.isVerticalScrollBarEnabled = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val method = WebView::class.java.getMethod(
                    "setSafeBrowsingEnabled",
                    Boolean::class.javaPrimitiveType
                )
                method.invoke(null, false)
            } catch (_: Throwable) {
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, true)
        }
        downloadBridge = DownloadBridge(this)
        webView.addJavascriptInterface(downloadBridge, "HtmlAppNative")
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val intent = try {
                    fileChooserParams?.createIntent()
                        ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        }
                } catch (e: Exception) {
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "*/*"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    }
                }

                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    this@MainActivity.filePathCallback?.onReceiveValue(null)
                    this@MainActivity.filePathCallback = null
                    false
                }
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageReady = false
                lastVisibilityState = null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) {
                    return false
                }
                val url = request.url
                val scheme = url.scheme?.lowercase(Locale.ROOT)
                if (scheme == "http" || scheme == "https") {
                    return false
                }
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.w("MainActivity", "No activity found to handle $url", e)
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                isPageReady = true
                dispatchPendingVisibility()
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                val state = if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    "foreground"
                } else {
                    "background"
                }
                notifyWebVisibility(state)
            }

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
    }

    private fun setupWindowInsets(root: View) {
        val controller = WindowInsetsControllerCompat(window, root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                max(systemBars.bottom, imeInsets.bottom)
            )
            insets
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            if (this@MainActivity::webView.isInitialized && webView.canGoBack()) {
                webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val root = findViewById<View>(R.id.root_container)
        setupWindowInsets(root)
        if (this::webView.isInitialized) {
            webView.postInvalidate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val root = findViewById<View>(R.id.root_container)
            WindowInsetsControllerCompat(window, root).let {
                it.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsetsCompat.Type.systemBars())
            }
            refreshWebViewInputConnection()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (this::webView.isInitialized) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            webView.apply {
                loadUrl("about:blank")
                stopLoading()
                clearHistory()
                removeAllViews()
                destroy()
            }
        }
        if (this::downloadBridge.isInitialized) {
            downloadBridge.dispose()
        }
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibilityObserver)
        super.onDestroy()
    }

    private fun notifyWebVisibility(state: String) {
        if (!this::webView.isInitialized) return
        pendingVisibilityState = state
        if (!isPageReady) {
            return
        }
        dispatchVisibilityState(state)
    }

    private fun dispatchVisibilityState(state: String) {
        if (!this::webView.isInitialized) return
        if (state == lastVisibilityState) {
            return
        }
        lastVisibilityState = state
        webView.post {
            webView.evaluateJavascript(
                "window.__setNativeVisibility && window.__setNativeVisibility('$state');",
                null
            )
        }
    }

    private fun dispatchPendingVisibility() {
        val state = pendingVisibilityState ?: return
        dispatchVisibilityState(state)
    }

    private fun handleAppVisibility(isForeground: Boolean) {
        val changed = isAppInForeground != isForeground
        isAppInForeground = isForeground
        updateWebViewActivityState()
        if (changed) {
            if (isForeground) {
                resumeWebViewAfterBackground()
            } else {
                prepareWebViewForBackground()
            }
            notifyWebVisibility(if (isForeground) "foreground" else "background")
        }
    }

    private fun prepareWebViewForBackground() {
        if (!this::webView.isInitialized) return
        if (!shouldKeepWebViewActive()) {
            try {
                webView.onPause()
            } catch (_: Throwable) {
            }
        }
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        webView.post {
            if (!webView.isAttachedToWindow) {
                return@post
            }
            webView.clearFocus()
            imm?.hideSoftInputFromWindow(webView.windowToken, InputMethodManager.HIDE_NOT_ALWAYS)
        }
    }

    private fun resumeWebViewAfterBackground() {
        if (!this::webView.isInitialized) return
        try {
            webView.onResume()
        } catch (_: Throwable) {
        }
        refreshWebViewInputConnection()
    }

    private fun refreshWebViewInputConnection() {
        if (!this::webView.isInitialized) return
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            ?: return
        webView.post {
            if (!webView.isAttachedToWindow) {
                return@post
            }
            if (!webView.hasFocus()) {
                webView.requestFocus(View.FOCUS_DOWN)
                webView.requestFocusFromTouch()
            }
            imm.restartInput(webView)
        }
    }

    fun onStreamingStateChanged(active: Boolean) {
        if (!this::webView.isInitialized) return
        if (isStreaming == active) return
        isStreaming = active
        updateWebViewActivityState()
    }

    private fun updateWebViewActivityState() {
        if (!this::webView.isInitialized) return
        val shouldKeepActive = shouldKeepWebViewActive()
        if (shouldKeepActive) {
            if (areImagesBlocked) {
                webView.settings.blockNetworkImage = false
                areImagesBlocked = false
            }
            if (areTimersPaused) {
                webView.resumeTimers()
                areTimersPaused = false
            }
        } else {
            if (!areImagesBlocked) {
                webView.settings.blockNetworkImage = true
                areImagesBlocked = true
            }
            if (!areTimersPaused) {
                webView.pauseTimers()
                areTimersPaused = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val desiredPriority = if (shouldKeepActive) {
                WebView.RENDERER_PRIORITY_IMPORTANT
            } else {
                WebView.RENDERER_PRIORITY_BOUND
            }
            val desiredWaived = !shouldKeepActive
            if (desiredPriority != lastRendererPriority || desiredWaived != lastRendererWaived) {
                webView.setRendererPriorityPolicy(desiredPriority, desiredWaived)
                lastRendererPriority = desiredPriority
                lastRendererWaived = desiredWaived
            }
        }
    }

    private fun shouldKeepWebViewActive(): Boolean {
        return isAppInForeground || isStreaming
    }
}

private class DownloadBridge(activity: MainActivity) {
    private val activityRef = WeakReference(activity)
    private val appContext = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bg-io").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }

    fun dispose() {
        ioExecutor.shutdownNow()
        activityRef.clear()
    }

    @JavascriptInterface
    fun saveFile(filename: String, jsonText: String): String {
        val safeName = filename.ifBlank { "backup-${UUID.randomUUID()}.json" }
        return try {
            ioExecutor.execute {
                val (success, location) = performSave(safeName, jsonText)
                notify(success, location)
            }
            SAVE_PENDING
        } catch (e: RejectedExecutionException) {
            val (success, location) = performSave(safeName, jsonText)
            notify(success, location)
            location ?: ""
        }
    }

    @JavascriptInterface
    fun notifyStreamingState(active: Boolean) {
        activityRef.get()?.let { activity ->
            activity.runOnUiThread {
                activity.onStreamingStateChanged(active)
            }
        }
    }

    private fun performSave(filename: String, jsonText: String): Pair<Boolean, String?> {
        return try {
            val data = jsonText.toByteArray(Charsets.UTF_8)
            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToMediaStore(filename, data)
            } else {
                saveToLegacyStorage(filename, data)
            }
            Pair(!location.isNullOrBlank(), location)
        } catch (e: Exception) {
            Pair(false, null)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(filename: String, data: ByteArray): String? {
        val resolver = appContext.contentResolver
        val targetDir = Environment.DIRECTORY_DOWNLOADS + "/AIChat"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.IS_PENDING, 1)
            put(MediaStore.Downloads.RELATIVE_PATH, targetDir)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { output ->
                output.write(data)
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "内部存储/Download/AIChat/$filename"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToLegacyStorage(filename: String, data: ByteArray): String? {
        return try {
            val baseDir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
            if (!baseDir.exists()) {
                baseDir.mkdirs()
            }
            val targetDir = File(baseDir, "AIChat")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            val file = File(targetDir, filename)
            FileOutputStream(file).use { it.write(data) }
            file.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun notify(success: Boolean, location: String?) {
        mainHandler.post {
            val message = if (success) {
                if (!location.isNullOrBlank()) {
                    "备份已保存: $location"
                } else {
                    "备份已保存"
                }
            } else {
                "保存备份失败"
            }
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val SAVE_PENDING = "__NATIVE_PENDING__"
    }
}
