package com.example.htmlapp.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "session_id")
    val sessionId: String,
    val role: String,
    val content: String,
    @ColumnInfo(name = "model_label")
    val modelLabel: String?,
    val thinking: String?,
    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)

data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatRole,
    val content: String,
    val modelLabel: String?,
    val thinking: String?,
    val isStreaming: Boolean,
    val createdAt: Long,
)

enum class ChatRole { USER, ASSISTANT }

data class ModelPreset(
    val id: String,
    val displayName: String,
    val description: String,
)

data class MessageWindow(
    val sessionId: String,
    val messages: List<ChatMessage>,
    val hasMoreHistory: Boolean,
)

sealed interface ChatEvent {
    data class StreamDelta(val contentDelta: String?, val thinkingDelta: String?) : ChatEvent
    data class Completed(val totalUsageTokens: Int?) : ChatEvent
}

interface ChatDataSource {
    fun observeSessions(): Flow<List<ChatSession>>
    fun observeMessages(sessionId: String, limit: Int): Flow<List<ChatMessage>>
}
