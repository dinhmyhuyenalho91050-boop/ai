package com.example.htmlapp.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

class BackupManager(
    private val context: Context,
    private val contentResolver: ContentResolver = context.contentResolver,
) {
    suspend fun export(uri: Uri, sessions: List<ChatSession>, messages: Map<String, List<ChatMessage>>) {
        withContext(Dispatchers.IO) {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                BufferedWriter(OutputStreamWriter(outputStream, StandardCharsets.UTF_8)).use { writer ->
                    writer.write(buildBackupJson(sessions, messages).toString(2))
                    writer.flush()
                }
            } ?: error("无法打开导出文件")
        }
    }

    private fun buildBackupJson(
        sessions: List<ChatSession>,
        messages: Map<String, List<ChatMessage>>,
    ): JSONObject {
        val root = JSONObject()
        val sessionsArray = JSONArray()
        sessions.forEach { session ->
            val sessionNode = JSONObject()
            sessionNode.put("id", session.id)
            sessionNode.put("title", session.title)
            sessionNode.put("created_at", session.createdAt)
            sessionNode.put("updated_at", session.updatedAt)
            val messageArray = JSONArray()
            messages[session.id].orEmpty().forEach { message ->
                val node = JSONObject()
                node.put("id", message.id)
                node.put("role", message.role.name.lowercase())
                node.put("content", message.content)
                node.put("model_label", message.modelLabel)
                node.put("thinking", message.thinking)
                node.put("is_streaming", message.isStreaming)
                node.put("created_at", message.createdAt)
                messageArray.put(node)
            }
            sessionNode.put("messages", messageArray)
            sessionsArray.put(sessionNode)
        }
        root.put("sessions", sessionsArray)
        return root
    }
}
