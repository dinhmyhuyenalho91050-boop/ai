package com.example.htmlapp.backup

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.example.htmlapp.data.ChatRepository
import com.example.htmlapp.data.ChatStoreSerializer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

class BackupManager(
    private val context: Context,
    private val repository: ChatRepository,
) {
    private val timeFormatter = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault())

    suspend fun exportBackup(): BackupResult = withContext(Dispatchers.IO) {
        val store = repository.store.firstOrNull() ?: return@withContext BackupResult.Failure("暂无数据可导出")
        val fileName = "ai-chat-backup-${timeFormatter.format(Date())}.json"
        val data = ChatStoreSerializer.encodeToString(store)
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
        }
        val uri = resolver.insert(collection, values) ?: return@withContext BackupResult.Failure("无法创建备份文件")
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(data.encodeToByteArray())
            BackupResult.Success(uri)
        } ?: BackupResult.Failure("无法写入备份文件")
    }

    suspend fun importBackup(uri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@withContext BackupResult.Failure("无法读取备份文件")
        val store = try {
            ChatStoreSerializer.decodeFromString(bytes.decodeToString())
        } catch (error: Throwable) {
            return@withContext BackupResult.Failure("备份文件格式错误")
        }
        repository.replaceStore(store)
        BackupResult.Success(uri)
    }

    sealed interface BackupResult {
        data class Success(val uri: Uri) : BackupResult
        data class Failure(val message: String) : BackupResult
    }
}
