package com.example.htmlapp

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.IBinder
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.max
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import android.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
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
    private var connectionService: ConnectionService? = null
    private var connectionServiceIntent: Intent? = null
    private var connectionServiceStarted = false
    private var isServiceBound = false
    private var isBindingService = false
    private var serviceEventsJob: Job? = null
    private val pendingServiceScripts = mutableListOf<String>()
    private val appVisibilityObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            handleAppVisibility(true)
        }

        override fun onStop(owner: LifecycleOwner) {
            handleAppVisibility(false)
        }
    }

    private val connectionServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? ConnectionService.LocalBinder ?: return
            connectionService = binder.getService()
            isServiceBound = true
            isBindingService = false
            connectionService?.setClientVisible(isAppInForeground)
            connectionService?.setStreamingActive(isStreaming)
            connectionService?.let { subscribeToServiceEvents(it) }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceEventsJob?.cancel()
            serviceEventsJob = null
            connectionService = null
            isServiceBound = false
            isBindingService = false
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                isPageReady = false
            }

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
                isPageReady = true
                dispatchPendingVisibility()
                flushPendingServiceScripts()
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
        pendingServiceScripts.clear()
        unbindConnectionService()
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

    private fun startConnectionServiceIfNeeded() {
        if (connectionServiceStarted) return
        val intent = Intent(this, ConnectionService::class.java)
        connectionServiceIntent = intent
        ContextCompat.startForegroundService(this, intent)
        connectionServiceStarted = true
    }

    private fun bindConnectionServiceIfNeeded() {
        startConnectionServiceIfNeeded()
        if (isServiceBound || isBindingService) return
        val intent = connectionServiceIntent ?: Intent(this, ConnectionService::class.java).also {
            connectionServiceIntent = it
        }
        isBindingService = true
        val bound = bindService(intent, connectionServiceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            isBindingService = false
        }
    }

    private fun unbindConnectionService() {
        serviceEventsJob?.cancel()
        serviceEventsJob = null
        if (isServiceBound || isBindingService) {
            try {
                unbindService(connectionServiceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        connectionService?.setClientVisible(false)
        connectionService = null
        isServiceBound = false
        isBindingService = false
    }

    private fun subscribeToServiceEvents(service: ConnectionService) {
        serviceEventsJob?.cancel()
        serviceEventsJob = lifecycleScope.launch {
            service.events().collectLatest { event ->
                handleServiceEvent(event)
            }
        }
    }

    private fun handleServiceEvent(event: ConnectionService.ServiceEvent) {
        when (event) {
            is ConnectionService.ServiceEvent.Message -> {
                val raw = JSONObject.quote(event.payload)
                val script = """
                    (function(){
                      const raw = $raw;
                      let data = raw;
                      try { data = JSON.parse(raw); } catch(e){}
                      try { window.dispatchEvent(new CustomEvent('native-connection',{detail:data})); } catch(e){}
                    })();
                """.trimIndent()
                postServiceScript(script)
            }
            is ConnectionService.ServiceEvent.ConnectionState -> {
                val script = """
                    (function(){
                      try { window.dispatchEvent(new CustomEvent('native-connection-state',{detail:{connected:${if (event.connected) "true" else "false"}}})); } catch(e){}
                    })();
                """.trimIndent()
                postServiceScript(script)
            }
            is ConnectionService.ServiceEvent.Error -> {
                val message = JSONObject.quote(event.throwable.message ?: "")
                val name = JSONObject.quote(event.throwable::class.java.simpleName)
                val script = """
                    (function(){
                      try { window.dispatchEvent(new CustomEvent('native-connection-error',{detail:{name:$name,message:$message}})); } catch(e){}
                    })();
                """.trimIndent()
                postServiceScript(script)
            }
        }
    }

    private fun postServiceScript(script: String) {
        if (!this::webView.isInitialized || !isPageReady) {
            pendingServiceScripts.add(script)
            return
        }
        webView.post {
            webView.evaluateJavascript(script, null)
        }
    }

    private fun flushPendingServiceScripts() {
        if (!this::webView.isInitialized || !isPageReady) return
        if (pendingServiceScripts.isEmpty()) return
        val scripts = pendingServiceScripts.toList()
        pendingServiceScripts.clear()
        scripts.forEach { script ->
            webView.post {
                webView.evaluateJavascript(script, null)
            }
        }
    }

    private fun handleAppVisibility(isForeground: Boolean) {
        val changed = isAppInForeground != isForeground
        isAppInForeground = isForeground
        connectionService?.setClientVisible(isForeground)
        if (isForeground) {
            bindConnectionServiceIfNeeded()
        } else {
            unbindConnectionService()
        }
        updateWebViewActivityState()
        if (changed) {
            notifyWebVisibility(if (isForeground) "foreground" else "background")
        }
    }

    fun onStreamingStateChanged(active: Boolean) {
        if (!this::webView.isInitialized) return
        if (isStreaming == active) return
        isStreaming = active
        connectionService?.setStreamingActive(active)
        updateWebViewActivityState()
    }

    private fun updateWebViewActivityState() {
        if (!this::webView.isInitialized) return
        val isForeground = isAppInForeground
        if (isForeground) {
            if (areImagesBlocked) {
                webView.settings.blockNetworkImage = false
                areImagesBlocked = false
            }
            if (areTimersPaused) {
                webView.onResume()
                webView.resumeTimers()
                areTimersPaused = false
            }
        } else {
            if (!areImagesBlocked) {
                webView.settings.blockNetworkImage = true
                areImagesBlocked = true
            }
            if (!areTimersPaused) {
                webView.onPause()
                webView.pauseTimers()
                areTimersPaused = true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val desiredPriority = if (isForeground) {
                WebView.RENDERER_PRIORITY_IMPORTANT
            } else {
                WebView.RENDERER_PRIORITY_BOUND
            }
            val desiredWaived = !isForeground
            if (desiredPriority != lastRendererPriority || desiredWaived != lastRendererWaived) {
                webView.setRendererPriorityPolicy(desiredPriority, desiredWaived)
                lastRendererPriority = desiredPriority
                lastRendererWaived = desiredWaived
            }
        }
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

    @JavascriptInterface
    fun notifyStreamingState(active: Boolean) {
        activityRef.get()?.let { activity ->
            activity.runOnUiThread {
                activity.onStreamingStateChanged(active)
            }
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
