package com.example.htmlapp

import android.app.Activity
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class WebAppBridge(private val activity: Activity) {

    @JavascriptInterface
    fun saveJson(filename: String, content: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveToDownloads(filename, content)
            } else {
                saveToAppFiles(filename, content)
            }
            true
        } catch (e: Exception) {
            showToast("导出失败: ${e.message}")
            false
        }
    }

    private fun saveToDownloads(filename: String, content: String) {
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AI Chat")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建文件")
        try {
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(content.toByteArray(StandardCharsets.UTF_8))
            } ?: throw IOException("无法写入文件")
            showToast("已保存到下载/AI Chat 目录")
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppFiles(filename: String, content: String) {
        val dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: activity.filesDir
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("无法创建目录")
        }
        val file = File(dir, filename)
        FileOutputStream(file).use { it.write(content.toByteArray(StandardCharsets.UTF_8)) }
        showToast("已保存到: ${file.absolutePath}")
    }

    private fun showToast(message: String) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
        }
    }
}
