package com.example.htmlapp.data

import android.content.Context
import com.example.htmlapp.data.model.ChatStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ChatLocalDataSource(
    context: Context,
    private val json: Json,
) {
    private val stateFile: File = File(context.filesDir, "chat_state.json")

    suspend fun load(): ChatStore = withContext(Dispatchers.IO) {
        if (!stateFile.exists()) {
            return@withContext ChatStore()
        }
        runCatching {
            stateFile.readText()
        }.mapCatching { raw ->
            json.decodeFromString(ChatStore.serializer(), raw)
        }.getOrElse { ChatStore() }
    }

    suspend fun save(store: ChatStore) = withContext(Dispatchers.IO) {
        val encoded = json.encodeToString(ChatStore.serializer(), store)
        stateFile.writeText(encoded)
    }
}
