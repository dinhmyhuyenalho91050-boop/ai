package com.example.htmlapp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ModelProvider {
    @SerialName("openai")
    OpenAI,
    @SerialName("deepseek")
    DeepSeek,
    @SerialName("gemini")
    Gemini,
    @SerialName("mock")
    Mock,
}

@Serializable
data class ModelPreset(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val model: String,
    val baseUrl: String = "",
    val reasoningEffort: String? = null,
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val supportsThinking: Boolean = false,
)
