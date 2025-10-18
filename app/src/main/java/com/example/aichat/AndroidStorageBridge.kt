package com.example.aichat

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.webkit.WebView
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

class AndroidStorageBridge(context: Context, webView: WebView) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("storage_manager", Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewRef = WeakReference(webView)
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @JavascriptInterface
    fun getItem(key: String): String? = prefs.getString(key, null)

    @JavascriptInterface
    fun setItem(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
        editor.apply()
    }

    @JavascriptInterface
    fun removeItem(key: String) {
        prefs.edit().remove(key).apply()
    }

    @JavascriptInterface
    fun clear() {
        prefs.edit().clear().apply()
    }

    @JavascriptInterface
    fun exportJson(filename: String, content: String): String {
        val trimmed = filename.trim()
        val baseName = if (trimmed.isEmpty()) {
            "chat-export-${System.currentTimeMillis()}"
        } else {
            trimmed
        }
        val sanitized = baseName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val safeName = if (sanitized.endsWith(".json", ignoreCase = true)) {
            sanitized
        } else {
            "$sanitized.json"
        }
        val bytes = content.toByteArray(Charsets.UTF_8)
        val requestId = "android_export_${UUID.randomUUID()}"

        exportScope.launch {
            val result = runCatching { performExport(safeName, bytes) }
            val (success, savedPath) = result.getOrElse { false to null }
            val errorMessage = result.exceptionOrNull()?.message

            var failureForJs: String? = null
            if (success) {
                val message = savedPath?.let { "已导出: $it" }
                    ?: "已导出到下载目录: $safeName"
                mainHandler.post {
                    Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                }
            } else {
                val failureMessage = errorMessage?.let { "导出失败: $it" }
                    ?: "导出失败: 未能写入文件"
                failureForJs = failureMessage
                mainHandler.post {
                    Toast.makeText(appContext, failureMessage, Toast.LENGTH_LONG).show()
                }
            }

            notifyJs(requestId, success, savedPath, failureForJs)
        }

        return requestId
    }

    private fun notifyJs(
        requestId: String,
        success: Boolean,
        savedPath: String?,
        errorMessage: String?
    ) {
        val js = buildString {
            append("if(window.__handleAndroidExportResult){window.__handleAndroidExportResult(")
            append(JSONObject.quote(requestId))
            append(',')
            append(if (success) "true" else "false")
            append(',')
            append(savedPath?.let { JSONObject.quote(it) } ?: "null")
            append(',')
            append(errorMessage?.let { JSONObject.quote(it) } ?: "null")
            append(");}")
        }
        webViewRef.get()?.post {
            it.evaluateJavascript(js, null)
        }
    }

    private fun performExport(safeName: String, bytes: ByteArray): Pair<Boolean, String?> {
        var savedPath: String? = null
        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, "application/json")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { output ->
                    output.write(bytes)
                } ?: throw IllegalStateException("Output stream unavailable")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                savedPath = "下载/$safeName"
                true
            } else {
                throw IllegalStateException("无法创建下载文件")
            }
        } else {
            val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: appContext.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val file = File(dir, safeName)
            FileOutputStream(file).use { output ->
                output.write(bytes)
            }
            savedPath = file.absolutePath
            true
        }
        return success to savedPath
    }
}
