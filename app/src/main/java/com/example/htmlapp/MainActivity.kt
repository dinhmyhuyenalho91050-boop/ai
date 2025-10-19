package com.example.htmlapp

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingExportBytes: ByteArray? = null

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val callback = filePathCallback
            if (callback != null) {
                if (uri != null) {
                    callback.onReceiveValue(arrayOf(uri))
                } else {
                    callback.onReceiveValue(null)
                }
            }
            filePathCallback = null
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val bytes = pendingExportBytes
            if (uri != null && bytes != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(bytes)
                    }
                    dispatchExportResult(true, "导出成功")
                } catch (e: Exception) {
                    dispatchExportResult(false, e.localizedMessage ?: "未知错误")
                }
            } else if (bytes != null) {
                dispatchExportResult(false, "已取消保存")
            }

            pendingExportBytes = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = findViewById<View>(R.id.root_container)
        webView = findViewById(R.id.webview)

        configureWebView()
        setupWindowInsets(root)
        setupBackNavigation()

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
        }

        webView.isVerticalScrollBarEnabled = false
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
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@MainActivity.filePathCallback?.onReceiveValue(null)
                this@MainActivity.filePathCallback = filePathCallback

                val accepted = fileChooserParams?.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?.toTypedArray()

                val types = if (accepted.isNullOrEmpty()) {
                    arrayOf("application/json", "text/*", "application/octet-stream", "*/*")
                } else {
                    accepted
                }

                openDocumentLauncher.launch(types)
                return true
            }
        }

        webView.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
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
        super.onDestroy()
    }

    private fun dispatchExportResult(success: Boolean, message: String) {
        if (!this::webView.isInitialized) return
        val script = """
            (function(){
              if(window.__androidExportResult){
                window.__androidExportResult($success, ${JSONObject.quote(message)});
              }else if(window.AndroidBridge && typeof window.AndroidBridge.onExportResult === 'function'){
                window.AndroidBridge.onExportResult($success, ${JSONObject.quote(message)});
              }
            })();
        """.trimIndent()
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private inner class AndroidBridge {
        @JavascriptInterface
        fun exportFile(filename: String?, content: String?) {
            if (content.isNullOrEmpty()) {
                dispatchExportResult(false, "数据为空")
                return
            }

            val safeName = if (filename.isNullOrBlank()) {
                "chat-backup.json"
            } else {
                filename
            }

            pendingExportBytes = content.toByteArray(Charsets.UTF_8)
            runOnUiThread {
                createDocumentLauncher.launch(safeName)
            }
        }
    }
}
