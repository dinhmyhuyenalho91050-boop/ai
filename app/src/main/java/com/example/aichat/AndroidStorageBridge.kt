package com.example.aichat

import android.content.Context
import android.content.SharedPreferences
import android.webkit.JavascriptInterface

class AndroidStorageBridge(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("storage_manager", Context.MODE_PRIVATE)

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
}
