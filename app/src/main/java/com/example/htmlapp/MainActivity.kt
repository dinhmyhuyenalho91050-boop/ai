package com.example.htmlapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.webkit.WebSettingsCompat
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlin.math.max
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import android.util.Base64
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var downloadBridge: DownloadBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var fileChooserLauncher: ActivityResultLauncher<android.content.Intent>
    private var notificationPermissionLauncher: ActivityResultLauncher<String>? = null
    private var isAppInForeground = true
    private var isStreaming = false
    private var isConnectionServiceEnabled = false
    private var areTimersPaused = false
    private var areImagesBlocked = false
    private var lastVisibilityState: String? = null
    private var lastRendererPriority: Int? = null
    private var lastRendererWaived: Boolean? = null
    private var isPageReady = false
    private var pendingVisibilityState: String? = null
    private var connectionService: ConnectionService? = null
    private var connectionServiceBound = false
    private var connectionJob: Job? = null
    private var connectionCallbackName: String? = null
    private val pendingConnectionEvents = ArrayDeque<String>()
    private var isConnectionBinding = false
    private var isNotificationPermissionRequestInFlight = false
    private var hasShownNotificationPermissionWarning = false
    private var hasShownConnectionStartError = false
    private var lastKnownConnectionVisibility: Boolean? = null
    private var connectionServiceRequested = false
    private var isConnectionServiceRunning = false
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
            val binder = service as? ConnectionService.ConnectionBinder ?: return
            val connection = binder.getService()
            connectionService = connection
            connectionServiceBound = true
            isConnectionBinding = false
            isConnectionServiceRunning = true
            connection.setClientVisibility(isAppInForeground)
            connectionJob?.cancel()
            connectionJob = lifecycleScope.launch {
                connection.events.collect { event ->
                    handleConnectionEvent(event)
                }
            }
            connection.getCurrentStatus()?.let { status ->
                handleConnectionEvent(status)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            connectionJob?.cancel()
            connectionJob = null
            connectionServiceBound = false
            isConnectionBinding = false
            connectionService = null
            isConnectionServiceRunning = false
        }
    }
    private val connectionEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ConnectionService.ACTION_EVENT) {
                return
            }
            if (connectionServiceBound) {
                return
            }
            val type = intent.getStringExtra(ConnectionService.EXTRA_EVENT_TYPE) ?: return
            val payload = intent.getStringExtra(ConnectionService.EXTRA_PAYLOAD)
            deliverConnectionEvent(type, payload)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher = registerForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                isNotificationPermissionRequestInFlight = false
                if (granted) {
                    enableConnectionService()
                } else {
                    isConnectionServiceEnabled = false
                    lastKnownConnectionVisibility = null
                    connectionServiceRequested = false
                    pendingConnectionEvents.clear()
                    isConnectionServiceRunning = false
                    refreshConnectionServiceState()
                    showConnectionPermissionWarning(false)
                }
            }
        }

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
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(connectionEventReceiver, IntentFilter(ConnectionService.ACTION_EVENT))
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
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(connectionEventReceiver)
        } catch (_: IllegalArgumentException) {
        }
        connectionServiceRequested = false
        pendingConnectionEvents.clear()
        connectionService?.setClientVisibility(false)
        lastKnownConnectionVisibility = null
        if (isConnectionServiceRunning) {
            ConnectionService.stop(applicationContext)
        }
        isConnectionServiceRunning = false
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

    private fun handleConnectionEvent(event: ConnectionService.ConnectionEvent) {
        deliverConnectionEvent(event.type, event.payload)
        if (
            event is ConnectionService.ConnectionEvent.Error &&
            isAppInForeground &&
            connectionServiceRequested
        ) {
            Toast.makeText(
                this,
                getString(R.string.service_retrying) + " (" + (event.payload ?: "") + ")",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun deliverConnectionEvent(type: String, payload: String?) {
        if (!connectionServiceRequested) {
            return
        }
        val json = JSONObject().apply {
            put("type", type)
            if (payload != null) {
                put("payload", payload)
            }
        }.toString()
        if (!tryDeliverToWebView(json)) {
            if (pendingConnectionEvents.size >= 32) {
                pendingConnectionEvents.removeFirst()
            }
            pendingConnectionEvents.addLast(json)
        }
    }

    private fun tryDeliverToWebView(payload: String): Boolean {
        if (!this::webView.isInitialized) {
            return false
        }
        val handler = connectionCallbackName
        if (handler.isNullOrBlank()) {
            return false
        }
        if (!shouldKeepWebViewActive()) {
            return false
        }
        webView.post {
            webView.evaluateJavascript("$handler(${JSONObject.quote(payload)})", null)
        }
        return true
    }

    private fun flushPendingConnectionEvents() {
        if (!connectionServiceRequested || pendingConnectionEvents.isEmpty()) return
        while (pendingConnectionEvents.isNotEmpty()) {
            val payload = pendingConnectionEvents.first()
            if (tryDeliverToWebView(payload)) {
                pendingConnectionEvents.removeFirst()
            } else {
                break
            }
        }
    }

    fun registerConnectionHandler(handlerName: String?) {
        connectionCallbackName = handlerName?.takeIf { it.isNotBlank() }
        flushPendingConnectionEvents()
    }

    fun sendMessageThroughConnection(payload: String) {
        val message = payload.ifBlank { return }
        requestConnectionService()
        if (!isConnectionServiceEnabled) {
            if (!isNotificationPermissionRequestInFlight) {
                showConnectionPermissionWarning(true)
            }
            return
        }
        val service = connectionService
        if (service != null && connectionServiceBound) {
            service.sendMessage(message)
        } else {
            if (!ConnectionService.enqueueSend(applicationContext, message)) {
                handleConnectionServiceStartFailure()
            }
        }
    }

    private fun bindConnectionService() {
        if (connectionServiceBound || isConnectionBinding) {
            return
        }
        isConnectionBinding = true
        val bound = bindService(Intent(this, ConnectionService::class.java), connectionServiceConnection, Context.BIND_AUTO_CREATE)
        if (!bound) {
            isConnectionBinding = false
        }
    }

    private fun unbindConnectionService() {
        if (!connectionServiceBound && !isConnectionBinding) {
            return
        }
        if (connectionServiceBound) {
            try {
                unbindService(connectionServiceConnection)
            } catch (_: IllegalArgumentException) {
            }
        }
        connectionJob?.cancel()
        connectionJob = null
        connectionServiceBound = false
        isConnectionBinding = false
        connectionService = null
    }

    private fun handleAppVisibility(isForeground: Boolean) {
        val changed = isAppInForeground != isForeground
        isAppInForeground = isForeground
        if (isForeground && connectionServiceRequested && !isConnectionServiceEnabled) {
            maybeStartConnectionService()
        }
        refreshConnectionServiceState()
        updateWebViewActivityState()
        if (changed) {
            notifyWebVisibility(if (isForeground) "foreground" else "background")
        }
    }

    fun onStreamingStateChanged(active: Boolean) {
        if (!this::webView.isInitialized) return
        if (isStreaming == active) return
        isStreaming = active
        if (!active) {
            if (connectionServiceRequested) {
                connectionServiceRequested = false
                pendingConnectionEvents.clear()
            }
        }
        refreshConnectionServiceState()
        if (shouldKeepWebViewActive()) {
            flushPendingConnectionEvents()
        }
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

    private fun requestConnectionService() {
        if (!connectionServiceRequested) {
            connectionServiceRequested = true
        }
        maybeStartConnectionService()
    }

    private fun maybeStartConnectionService() {
        if (!connectionServiceRequested) {
            return
        }
        if (isConnectionServiceRunning) {
            return
        }
        if (isConnectionServiceEnabled) {
            if (ConnectionService.startAndConnect(applicationContext)) {
                isConnectionServiceRunning = true
                hasShownConnectionStartError = false
            } else {
                handleConnectionServiceStartFailure()
            }
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)) {
                PackageManager.PERMISSION_GRANTED -> enableConnectionService()
                else -> {
                    if (!isNotificationPermissionRequestInFlight) {
                        notificationPermissionLauncher?.let {
                            isNotificationPermissionRequestInFlight = true
                            it.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
            }
        } else {
            enableConnectionService()
        }
    }

    private fun enableConnectionService() {
        if (isConnectionServiceEnabled) {
            if (ConnectionService.startAndConnect(applicationContext)) {
                isConnectionServiceRunning = true
                hasShownConnectionStartError = false
            } else {
                handleConnectionServiceStartFailure()
            }
            return
        }
        if (ConnectionService.startAndConnect(applicationContext)) {
            isConnectionServiceEnabled = true
            isConnectionServiceRunning = true
            hasShownConnectionStartError = false
            lastKnownConnectionVisibility = null
            refreshConnectionServiceState()
        } else {
            Log.w("MainActivity", "Unable to start ConnectionService")
            isConnectionServiceEnabled = false
            connectionServiceRequested = false
            isConnectionServiceRunning = false
            handleConnectionServiceStartFailure()
        }
    }

    private fun refreshConnectionServiceState() {
        if (!connectionServiceRequested || !isConnectionServiceEnabled) {
            connectionService?.setClientVisibility(false)
            lastKnownConnectionVisibility = null
            if (connectionServiceBound || isConnectionBinding) {
                unbindConnectionService()
            }
            if (isConnectionServiceRunning) {
                ConnectionService.stop(applicationContext)
                isConnectionServiceRunning = false
            }
            if (!isConnectionServiceEnabled) {
                connectionServiceRequested = false
            }
            return
        }

        if (!isConnectionServiceRunning) {
            if (ConnectionService.startAndConnect(applicationContext)) {
                isConnectionServiceRunning = true
                hasShownConnectionStartError = false
            } else {
                handleConnectionServiceStartFailure()
                return
            }
        }

        val shouldBind = shouldKeepWebViewActive()
        val shouldBeVisible = isAppInForeground
        val lastVisible = lastKnownConnectionVisibility
        if (lastVisible == null || lastVisible != shouldBeVisible) {
            if (!ConnectionService.updateClientVisibility(applicationContext, shouldBeVisible)) {
                handleConnectionServiceStartFailure()
                return
            }
            connectionService?.setClientVisibility(shouldBeVisible)
            lastKnownConnectionVisibility = shouldBeVisible
        } else if (connectionServiceBound) {
            connectionService?.setClientVisibility(shouldBeVisible)
        }

        if (shouldBind) {
            bindConnectionService()
            flushPendingConnectionEvents()
        } else {
            connectionService?.setClientVisibility(false)
            if (connectionServiceBound || isConnectionBinding) {
                unbindConnectionService()
            }
        }
    }

    private fun shouldKeepWebViewActive(): Boolean {
        return isAppInForeground || isStreaming
    }

    private fun handleConnectionServiceStartFailure() {
        connectionService?.setClientVisibility(false)
        lastKnownConnectionVisibility = null
        if (connectionServiceBound || isConnectionBinding) {
            unbindConnectionService()
        }
        isConnectionServiceRunning = false
        isConnectionServiceEnabled = false
        connectionServiceRequested = false
        pendingConnectionEvents.clear()
        if (!hasShownConnectionStartError && isAppInForeground && !(isFinishing || isDestroyed)) {
            Toast.makeText(this, getString(R.string.service_start_failed), Toast.LENGTH_LONG).show()
            hasShownConnectionStartError = true
        }
    }

    private fun showConnectionPermissionWarning(short: Boolean) {
        if (!short && hasShownNotificationPermissionWarning) {
            return
        }
        val duration = if (short) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
        Toast.makeText(this, getString(R.string.service_permission_required), duration).show()
        if (!short) {
            hasShownNotificationPermissionWarning = true
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

    @JavascriptInterface
    fun registerConnectionHandler(handlerName: String?) {
        activityRef.get()?.registerConnectionHandler(handlerName)
    }

    @JavascriptInterface
    fun sendConnectionMessage(payload: String) {
        activityRef.get()?.sendMessageThroughConnection(payload)
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
