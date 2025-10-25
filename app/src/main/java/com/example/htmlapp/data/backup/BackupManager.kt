package com.example.htmlapp.data.backup

import android.content.Context
import android.net.Uri
import com.example.htmlapp.data.model.PersistedState
import com.example.htmlapp.data.repository.ChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupManager(
    private val context: Context,
    private val repository: ChatRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun exportToUri(uri: Uri): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                val payload = repository.persistedSnapshot().toJson().encodeToByteArray()
                output.write(payload)
            } ?: error("无法打开导出目标")
        }
    }

    suspend fun exportAsString(): String = withContext(ioDispatcher) {
        repository.persistedSnapshot().toJson()
    }

    suspend fun importFromUri(uri: Uri): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val json = context.contentResolver.openInputStream(uri)?.use { input ->
                input.readBytes().decodeToString()
            } ?: error("无法读取备份文件")
            val state = PersistedState.fromJson(json)
            repository.importSnapshot(state)
        }
    }

    suspend fun importFromString(json: String): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            val state = PersistedState.fromJson(json)
            repository.importSnapshot(state)
        }
    }
}

