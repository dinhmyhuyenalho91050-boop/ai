package com.example.htmlapp.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.htmlapp.data.model.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatBackupManager(
    private val context: Context,
    private val json: Json,
) {
    suspend fun export(store: ChatStore): Uri? = withContext(Dispatchers.IO) {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        val fileName = "ai_chat_backup_${formatter.format(Date())}.json"
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return@withContext null
        resolver.openOutputStream(uri)?.use { output ->
            output.writer().use { writer ->
                val payload = json.encodeToString(ChatStore.serializer(), store)
                writer.write(payload)
                writer.flush()
            }
        }
        uri
    }

    suspend fun import(uri: Uri): ChatStore? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        resolver.openInputStream(uri)?.use { input ->
            input.reader().use { reader ->
                val payload = reader.readText()
                runCatching {
                    json.decodeFromString(ChatStore.serializer(), payload)
                }.getOrNull()
            }
        }
    }
}
