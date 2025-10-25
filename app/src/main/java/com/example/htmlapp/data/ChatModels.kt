package com.example.htmlapp.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class MessageRole {
    @SerialName("user")
    USER,
    @SerialName("assistant")
    ASSISTANT,
    @SerialName("system")
    SYSTEM,
}

@Serializable
data class ChatSessionEntity(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

@Serializable
data class ChatMessageEntity(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val modelId: String?,
    val thinking: String? = null,
    val createdAt: Long,
    val isStreaming: Boolean = false,
)

@Serializable
data class ChatStore(
    val sessions: List<ChatSessionEntity> = emptyList(),
    val messages: List<ChatMessageEntity> = emptyList(),
    val selectedSessionId: String? = null,
)
