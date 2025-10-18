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
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AndroidStorageBridge(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("storage_manager", Context.MODE_PRIVATE)
    private val exportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

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
    fun exportJson(filename: String, content: String): Boolean {
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
        return try {
            exportScope.launch {
                var savedPath: String? = null
                try {
                    val bytes = content.toByteArray(Charsets.UTF_8)
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
                            false
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

                    if (success) {
                        val message = savedPath?.let { "已导出: $it" }
                            ?: "已导出到下载目录: $safeName"
                        mainHandler.post {
                            Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        mainHandler.post {
                            Toast.makeText(appContext, "导出失败: 未能写入文件", Toast.LENGTH_LONG)
                                .show()
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        Toast.makeText(appContext, "导出失败: ${e.message}", Toast.LENGTH_LONG)
                            .show()
                    }
                }
            }
            true
        } catch (e: Exception) {
            mainHandler.post {
                Toast.makeText(appContext, "导出失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
            false
        }
    }
}
