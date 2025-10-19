package com.example.htmlapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.annotation.RequiresApi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.webkit.WebSettingsCompat
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import android.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var downloadBridge: DownloadBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<android.content.Intent>
    private val visibilityHandler = Handler(Looper.getMainLooper())
    private val visibilityDispatch = Runnable { dispatchPendingVisibility() }
    private var pendingVisibilityState: String? = null
    private var lastVisibilityState: String? = null
    private val appVisibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            notifyWebVisibility("foreground")
        }

        override fun onStop(owner: LifecycleOwner) {
            notifyWebVisibility("background")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = findViewById<View>(R.id.root_container)
        webView = findViewById(R.id.webview)

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
        notifyWebVisibility("foreground")
        ProcessLifecycleOwner.get().lifecycle.addObserver(appVisibilityObserver)

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl("file:///android_asset/index.html")
        }
    }

    private fun configureWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT)
        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            offscreenPreRaster = false
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WebSettingsCompat.setForceDark(webView.settings, WebSettingsCompat.FORCE_DARK_ON)
            try {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
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
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return if (request.isForMainFrame) {
                    view.loadUrl(request.url.toString())
                    true
                } else {
                    false
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                val lifecycle = ProcessLifecycleOwner.get().lifecycle
                val state = if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    "foreground"
                } else {
                    "background"
                }
                notifyWebVisibility(state)
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
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
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
        visibilityHandler.removeCallbacks(visibilityDispatch)
        pendingVisibilityState = null
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appVisibilityObserver)
        super.onDestroy()
    }

    private fun notifyWebVisibility(state: String) {
        if (!this::webView.isInitialized) return
        if (state == lastVisibilityState && pendingVisibilityState == null) {
            return
        }
        pendingVisibilityState = state
        visibilityHandler.removeCallbacks(visibilityDispatch)
        visibilityHandler.post(visibilityDispatch)
    }

    private fun dispatchPendingVisibility() {
        if (!this::webView.isInitialized) {
            pendingVisibilityState = null
            return
        }
        val desiredState = pendingVisibilityState
        if (desiredState.isNullOrEmpty() || desiredState == lastVisibilityState) {
            pendingVisibilityState = null
            return
        }
        pendingVisibilityState = null
        webView.post {
            if (!this::webView.isInitialized) {
                return@post
            }
            if (desiredState == lastVisibilityState) {
                return@post
            }
            webView.evaluateJavascript(
                "window.__setNativeVisibility && window.__setNativeVisibility('$desiredState');",
                null
            )
            lastVisibilityState = desiredState
        }
    }
}

private class DownloadBridge(activity: MainActivity) {
    private val appContext = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = ThreadPoolExecutor(
        1,
        1,
        15L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue()
    ) { runnable ->
        Thread(runnable, "bg-io").apply {
            priority = Thread.MIN_PRIORITY
            isDaemon = true
        }
    }.apply {
        allowCoreThreadTimeOut(true)
    }

    fun dispose() {
        ioExecutor.shutdownNow()
    }

    @JavascriptInterface
    fun saveFile(filename: String, base64Data: String): String {
        val safeName = filename.ifBlank { "backup-${UUID.randomUUID()}.json" }
        return try {
            ioExecutor.execute {
                val (success, location) = performSave(safeName, base64Data)
                notify(success, location)
            }
            SAVE_PENDING
        } catch (e: RejectedExecutionException) {
            val (success, location) = performSave(safeName, base64Data)
            notify(success, location)
            location ?: ""
        }
    }

    private fun performSave(filename: String, base64Data: String): Pair<Boolean, String?> {
        return try {
            val data = Base64.decode(base64Data, Base64.DEFAULT)
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
