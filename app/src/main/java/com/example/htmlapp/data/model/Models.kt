package com.example.htmlapp.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val PersistedJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = true
}

internal val ChatJsonFormat: Json
    get() = PersistedJson

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
data class ChatMessage(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val thinking: String? = null,
    val modelId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
enum class ModelProvider {
    @SerialName("openai")
    OPENAI,

    @SerialName("deepseek")
    DEEPSEEK,

    @SerialName("gemini")
    GEMINI,
}

@Serializable
data class ModelPreset(
    val id: String,
    val displayName: String,
    val provider: ModelProvider,
    val description: String = "",
)

@Serializable
data class PromptPreset(
    val id: String,
    val title: String,
    val description: String,
    val prompt: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class PersistedState(
    val sessions: List<ChatSession> = emptyList(),
    val messagesBySession: Map<String, List<ChatMessage>> = emptyMap(),
    val presets: List<ModelPreset> = emptyList(),
    val promptPresets: List<PromptPreset> = emptyList(),
    val selectedSessionId: String? = null,
    val selectedModelId: String? = null,
) {
    companion object {
        val Empty = PersistedState()

        fun fromJson(raw: String): PersistedState =
            PersistedJson.decodeFromString(serializer(), raw)
    }

    fun toJson(): String = PersistedJson.encodeToString(serializer(), this)
}

fun defaultModelPresets(): List<ModelPreset> = listOf(
    ModelPreset(id = "gpt-4o", displayName = "GPT-4o", provider = ModelProvider.OPENAI),
    ModelPreset(id = "gpt-4o-mini", displayName = "GPT-4o mini", provider = ModelProvider.OPENAI),
    ModelPreset(id = "deepseek-chat", displayName = "DeepSeek", provider = ModelProvider.DEEPSEEK),
    ModelPreset(id = "gemini-1.5-pro", displayName = "Gemini 1.5 Pro", provider = ModelProvider.GEMINI),
)

fun defaultPromptPresets(): List<PromptPreset> = listOf(
    PromptPreset(
        id = "brainstorm",
        title = "头脑风暴",
        description = "辅助快速生成创意想法",
        prompt = "请以要点形式提供 5 个创意方向，并给出各自的亮点。",
    ),
    PromptPreset(
        id = "analyze",
        title = "深度分析",
        description = "帮助拆解问题的成因与影响",
        prompt = "从用户、业务、技术三个角度深入分析以下问题，并给出建议方案。",
    ),
)

