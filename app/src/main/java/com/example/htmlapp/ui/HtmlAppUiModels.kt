package com.example.htmlapp.ui

data class ChatSessionUi(
    val id: String,
    val title: String,
    val subtitle: String,
    val isActive: Boolean = false,
)

enum class ChatRole { User, Assistant }

data class ChatMessageUi(
    val id: String,
    val role: ChatRole,
    val content: String,
    val modelLabel: String? = null,
    val thinking: String? = null,
    val isStreaming: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
)

data class ModelPresetUi(
    val id: String,
    val displayName: String,
    val isActive: Boolean = false,
)

data class HtmlAppUiState(
    val sessions: List<ChatSessionUi>,
    val selectedSessionId: String?,
    val availableModels: List<ModelPresetUi>,
    val selectedModelId: String,
    val messages: List<ChatMessageUi>,
    val composerText: String,
    val isSending: Boolean,
    val canLoadMore: Boolean,
    val isSettingsVisible: Boolean,
    val toastMessage: String?,
    val isBackupInProgress: Boolean,
    val apiKey: String,
    val baseUrlOverride: String?,
    val enableMockResponses: Boolean,
)
