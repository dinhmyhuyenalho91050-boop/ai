package com.example.htmlapp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ChatMessageRole {
    @SerialName("user")
    User,
    @SerialName("assistant")
    Assistant,
    @SerialName("system")
    System,
}

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val archived: Boolean = false,
)

@Serializable
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: ChatMessageRole,
    val content: String,
    val model: String? = null,
    val thinking: String? = null,
    val createdAt: Long,
    val isStreaming: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ChatStore(
    val sessions: List<ChatSession> = emptyList(),
    val messages: Map<String, List<ChatMessage>> = emptyMap(),
    val selectedSessionId: String? = null,
)
