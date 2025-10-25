package com.example.htmlapp.network

import com.example.htmlapp.data.model.MessageRole
import com.example.htmlapp.data.model.ModelProvider

data class ChatMessagePayload(
    val role: MessageRole,
    val content: String,
)

data class ChatRequest(
    val modelId: String,
    val provider: ModelProvider,
    val apiKey: String,
    val baseUrl: String,
    val messages: List<ChatMessagePayload>,
    val temperature: Double? = null,
    val reasoningEffort: String? = null,
    val maxOutputTokens: Int? = null,
)

data class ChatStreamDelta(
    val contentDelta: String? = null,
    val thinkingDelta: String? = null,
    val usage: Usage? = null,
    val isComplete: Boolean = false,
)

data class Usage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
)

interface ChatStreamingClient {
    suspend fun stream(
        request: ChatRequest,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    )
}

