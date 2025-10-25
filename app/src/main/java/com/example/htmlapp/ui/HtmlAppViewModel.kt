package com.example.htmlapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlapp.data.AiSettingsRepository
import com.example.htmlapp.data.ChatBackupManager
import com.example.htmlapp.data.ChatRepository
import com.example.htmlapp.data.MessageWindowManager
import com.example.htmlapp.data.MessageWindowSnapshot
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatMessageRole
import com.example.htmlapp.data.model.ChatSession
import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.data.model.ModelProvider
import com.example.htmlapp.network.StreamEvent
import com.example.htmlapp.network.StreamMessage
import com.example.htmlapp.network.StreamRequest
import com.example.htmlapp.network.StreamingClient
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HtmlAppViewModel(
    private val chatRepository: ChatRepository,
    private val settingsRepository: AiSettingsRepository,
    private val backupManager: ChatBackupManager,
    private val streamingClient: StreamingClient,
    private val messageWindowManager: MessageWindowManager,
    private val modelPresets: List<ModelPreset>,
) : ViewModel() {
    private val composer = MutableStateFlow("")
    private val isSettingsVisible = MutableStateFlow(false)
    private val isBackupInProgress = MutableStateFlow(false)
    private val toastMessage = MutableStateFlow<String?>(null)
    private val windowVersion = MutableStateFlow(0)
    private val streamingMessageId = MutableStateFlow<String?>(null)
    private var streamingJob: Job? = null

    val uiState: StateFlow<HtmlAppUiState> = combine(
        chatRepository.storeFlow,
        settingsRepository.settingsFlow,
        composer,
        isSettingsVisible,
        isBackupInProgress,
        toastMessage,
        windowVersion,
        streamingMessageId,
    ) { store, settings, composerText, showSettings, backup, toast, _, streamingId ->
        val sessions = store.sessions.sortedByDescending { it.updatedAt }
        val selectedSessionId = store.selectedSessionId ?: sessions.firstOrNull()?.id
        val snapshot: MessageWindowSnapshot = if (selectedSessionId != null) {
            val raw = store.messages[selectedSessionId].orEmpty().sortedBy { it.createdAt }
            messageWindowManager.snapshot(selectedSessionId, raw)
        } else {
            MessageWindowSnapshot(emptyList(), canLoadMore = false)
        }
        HtmlAppUiState(
            sessions = sessions.map { it.toUi(selectedSessionId) },
            selectedSessionId = selectedSessionId,
            availableModels = modelPresets.map { it.toUi(settings.activeModelId) },
            selectedModelId = settings.activeModelId,
            messages = snapshot.messages.map { it.toUi(modelPresets) },
            composerText = composerText,
            isSending = streamingId != null,
            canLoadMore = snapshot.canLoadMore,
            isSettingsVisible = showSettings,
            toastMessage = toast,
            isBackupInProgress = backup,
            apiKey = settings.apiKey,
            baseUrlOverride = settings.baseUrlOverride,
            enableMockResponses = settings.enableMockResponses,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        HtmlAppUiState(
            sessions = emptyList(),
            selectedSessionId = null,
            availableModels = modelPresets.map { preset ->
                preset.toUi(modelPresets.firstOrNull()?.id ?: preset.id)
            },
            selectedModelId = modelPresets.firstOrNull()?.id ?: "",
            messages = emptyList(),
            composerText = "",
            isSending = false,
            canLoadMore = false,
            isSettingsVisible = false,
            toastMessage = null,
            isBackupInProgress = false,
            apiKey = "",
            baseUrlOverride = null,
            enableMockResponses = false,
        ),
    )

    init {
        viewModelScope.launch { chatRepository.initialize() }
    }

    fun onSelectSession(sessionId: String) {
        viewModelScope.launch {
            messageWindowManager.reset(sessionId)
            windowVersion.value += 1
            chatRepository.selectSession(sessionId)
        }
    }

    fun onSelectModel(modelId: String) {
        viewModelScope.launch { settingsRepository.setActiveModel(modelId) }
    }

    fun onComposerChange(text: String) {
        composer.value = text
    }

    fun onNewChat() {
        viewModelScope.launch {
            val sessionId = chatRepository.createSession()
            messageWindowManager.reset(sessionId)
            windowVersion.value += 1
            composer.value = ""
        }
    }

    fun onLoadMore() {
        val sessionId = uiState.value.selectedSessionId ?: return
        messageWindowManager.expand(sessionId)
        windowVersion.value += 1
    }

    fun onSendMessage() {
        val text = composer.value.trim()
        if (text.isEmpty()) return
        val state = uiState.value
        val sessionId = state.selectedSessionId ?: return
        val preset = modelPresets.firstOrNull { it.id == state.selectedModelId } ?: return
        composer.value = ""
        viewModelScope.launch {
            val timestamp = System.currentTimeMillis()
            val sessionIdSnapshot = uiState.value.selectedSessionId ?: return@launch
            val userMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                sessionId = sessionIdSnapshot,
                role = ChatMessageRole.User,
                content = text,
                model = preset.id,
                createdAt = timestamp,
            )
            chatRepository.appendMessage(userMessage)

            val assistantId = UUID.randomUUID().toString()
            val assistantMessage = ChatMessage(
                id = assistantId,
                sessionId = sessionIdSnapshot,
                role = ChatMessageRole.Assistant,
                content = "",
                model = preset.id,
                createdAt = System.currentTimeMillis(),
                isStreaming = true,
            )
            chatRepository.appendMessage(assistantMessage)
            windowVersion.value += 1

            val settings = settingsRepository.settingsFlow.first()
            val effectivePreset = when {
                preset.provider == ModelProvider.Mock -> preset
                settings.enableMockResponses -> modelPresets.first { it.provider == ModelProvider.Mock }
                else -> preset
            }

            val promptMessages = chatRepository.storeFlow.value.messages[sessionIdSnapshot]
                .orEmpty()
                .filter { it.id != assistantId }
                .sortedBy { it.createdAt }
                .map { existing ->
                    StreamMessage(
                        role = when (existing.role) {
                            ChatMessageRole.System -> "system"
                            ChatMessageRole.Assistant -> "assistant"
                            ChatMessageRole.User -> "user"
                        },
                        content = existing.content,
                    )
                }

            if (effectivePreset.provider != ModelProvider.Mock && settings.apiKey.isBlank()) {
                chatRepository.updateMessage(sessionIdSnapshot, assistantId) {
                    it.copy(
                        content = "未配置 API Key，无法发送请求。",
                        isStreaming = false,
                        error = "未配置 API Key",
                    )
                }
                streamingMessageId.value = null
                toastMessage.value = "请在设置中填写 API Key"
                return@launch
            }

            val request = StreamRequest(
                preset = effectivePreset,
                messages = promptMessages,
                apiKey = settings.apiKey,
                baseUrlOverride = settings.baseUrlOverride,
            )

            streamingMessageId.value = assistantId
            streamingJob?.cancel()
            streamingJob = launch {
                try {
                    streamingClient.stream(request).collect { event ->
                        when (event) {
                            is StreamEvent.Delta -> {
                                chatRepository.updateMessage(sessionIdSnapshot, assistantId) { message ->
                                    val updatedContent = event.content?.let { message.content + it } ?: message.content
                                    val updatedThinking = event.reasoning ?: message.thinking
                                    message.copy(content = updatedContent, thinking = updatedThinking)
                                }
                            }
                            StreamEvent.Complete -> {
                                chatRepository.updateMessage(sessionIdSnapshot, assistantId) {
                                    it.copy(isStreaming = false)
                                }
                            }
                            is StreamEvent.Error -> {
                                chatRepository.updateMessage(sessionIdSnapshot, assistantId) {
                                    it.copy(isStreaming = false, error = event.message)
                                }
                                toastMessage.value = event.message
                            }
                        }
                    }
                } finally {
                    streamingMessageId.value = null
                    streamingJob = null
                }
            }
        }
    }

    fun onOpenSettings() {
        isSettingsVisible.value = true
    }

    fun onDismissSettings() {
        isSettingsVisible.value = false
    }

    fun onUpdateSettings(apiKey: String, baseUrl: String?, enableMock: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateApiKey(apiKey)
            settingsRepository.updateBaseUrl(baseUrl)
            settingsRepository.setMockResponses(enableMock)
            isSettingsVisible.value = false
        }
    }

    fun onStopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        val sessionId = uiState.value.selectedSessionId ?: return
        val messageId = streamingMessageId.value ?: return
        viewModelScope.launch {
            chatRepository.updateMessage(sessionId, messageId) { it.copy(isStreaming = false) }
            streamingMessageId.value = null
        }
    }

    fun onExportBackup() {
        viewModelScope.launch {
            isBackupInProgress.value = true
            val store = chatRepository.storeFlow.value
            val uri = backupManager.export(store)
            isBackupInProgress.value = false
            toastMessage.value = if (uri != null) {
                "备份已保存: $uri"
            } else {
                "导出失败"
            }
        }
    }

    fun onDismissToast() {
        toastMessage.value = null
    }
}

private fun ChatSession.toUi(selectedId: String?): ChatSessionUi = ChatSessionUi(
    id = id,
    title = title,
    subtitle = formatTimestamp(updatedAt),
    isActive = id == selectedId,
)

private fun ModelPreset.toUi(selectedId: String): ModelPresetUi = ModelPresetUi(
    id = id,
    displayName = displayName,
    isActive = id == selectedId,
)

private fun ChatMessage.toUi(presets: List<ModelPreset>): ChatMessageUi {
    val label = model?.let { id -> presets.firstOrNull { it.id == id }?.displayName ?: id }
    return ChatMessageUi(
        id = id,
        role = when (role) {
            ChatMessageRole.User -> ChatRole.User
            else -> ChatRole.Assistant
        },
        content = content,
        modelLabel = label,
        thinking = thinking,
        isStreaming = isStreaming,
        isError = error?.isNotEmpty() == true,
        errorMessage = error,
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val minutes = (System.currentTimeMillis() - timestamp) / 1000 / 60
    return if (minutes <= 0) {
        "刚刚"
    } else if (minutes < 60) {
        "${minutes} 分钟前"
    } else {
        val hours = minutes / 60
        "${hours} 小时前"
    }
}
