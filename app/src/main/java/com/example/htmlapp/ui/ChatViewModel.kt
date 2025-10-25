package com.example.htmlapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlapp.data.backup.BackupManager
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.MessageRole
import com.example.htmlapp.data.repository.ChatRepository
import com.example.htmlapp.data.repository.ChatRepositoryState
import com.example.htmlapp.network.ChatMessagePayload
import com.example.htmlapp.network.ChatRequest
import com.example.htmlapp.network.ChatStreamDelta
import com.example.htmlapp.network.ChatStreamingClient
import com.example.htmlapp.network.OpenAiStreamingClient
import com.example.htmlapp.settings.AppSettings
import com.example.htmlapp.settings.ProviderSettings
import com.example.htmlapp.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import java.util.UUID

data class SettingsDialogState(
    val openAiKey: String,
    val openAiBaseUrl: String,
    val deepSeekKey: String,
    val deepSeekBaseUrl: String,
    val geminiKey: String,
    val geminiBaseUrl: String,
    val requestTimeoutSeconds: Int,
    val reasoningEffort: String,
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
    val totalMessages: Int,
    val errorMessage: String?,
    val settings: SettingsDialogState?,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder().build()
    }

    private val repository = ChatRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val backupManager = BackupManager(application, repository)
    private val streamingClient: ChatStreamingClient = OpenAiStreamingClient(httpClient)

    private val composer = MutableStateFlow("")
    private val isSending = MutableStateFlow(false)
    private val streamingMessageIds = MutableStateFlow<Set<String>>(emptySet())
    private val thinkingBuffer = MutableStateFlow<Map<String, String>>(emptyMap())
    private val errorMessage = MutableStateFlow<String?>(null)
    private val showSettings = MutableStateFlow(false)

    private val settingsFlow = settingsRepository.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AppSettings(),
    )

    val uiState: StateFlow<HtmlAppUiState> = combine(
        repository.state,
        composer,
        isSending,
        streamingMessageIds,
        thinkingBuffer,
        errorMessage,
        showSettings,
        settingsFlow,
    ) { repoState, composerText, sending, streamingIds, thinking, error, settingsVisible, settings ->
        repoState.toUiState(
            composer = composerText,
            isSending = sending,
            streamingIds = streamingIds,
            thinking = thinking,
            error = error,
            settingsVisible = settingsVisible,
            settings = settings,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = repository.currentState().toUiState(
            composer = composer.value,
            isSending = isSending.value,
            streamingIds = streamingMessageIds.value,
            thinking = thinkingBuffer.value,
            error = errorMessage.value,
            settingsVisible = showSettings.value,
            settings = settingsFlow.value,
        ),
    )

    private var streamingJob: Job? = null

    fun onComposerChange(text: String) {
        composer.value = text
    }

    fun onSelectSession(sessionId: String) {
        viewModelScope.launch {
            repository.selectSession(sessionId)
            composer.value = ""
        }
    }

    fun onSelectModel(modelId: String) {
        viewModelScope.launch { repository.selectModel(modelId) }
    }

    fun onNewChat() {
        viewModelScope.launch {
            repository.createSession()
            composer.value = ""
        }
    }

    fun onLoadOlder() {
        val state = repository.currentState()
        viewModelScope.launch { repository.loadOlderMessages(state.selectedSessionId) }
    }

    fun onSendMessage() {
        if (isSending.value) return
        val text = composer.value.trim()
        if (text.isEmpty()) return

        val repoState = repository.currentState()
        val sessionId = repoState.selectedSessionId
        val modelId = repoState.selectedModelId
        val modelPreset = repoState.modelPresets.firstOrNull { it.id == modelId }
        val settings = settingsFlow.value
        val providerSettings = modelPreset?.let { settings.forProvider(it.provider) }
        if (modelPreset == null || providerSettings == null || providerSettings.apiKey.isBlank()) {
            errorMessage.value = "请先在设置中配置模型的 API Key"
            return
        }

        viewModelScope.launch {
            isSending.value = true
            val userMessage = repository.appendUserMessage(sessionId, text, modelId)
            composer.value = ""
            val requestMessages = repository
                .messagesForSession(sessionId)
                .map { payloadForMessage(it) }
            val assistantId = UUID.randomUUID().toString()
            var assistantMessage = ChatMessage(
                id = assistantId,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                thinking = null,
                modelId = modelId,
            )
            repository.upsertAssistantMessage(assistantMessage)
            streamingMessageIds.update { it + assistantId }
            thinkingBuffer.update { it - assistantId }

            streamingJob?.cancel()
            streamingJob = viewModelScope.launch {
                val request = ChatRequest(
                    modelId = modelId,
                    provider = modelPreset.provider,
                    apiKey = providerSettings.apiKey,
                    baseUrl = providerSettings.baseUrl.ifBlank { defaultBaseUrl(modelPreset.provider) },
                    messages = requestMessages,
                    reasoningEffort = settings.reasoningEffort,
                )
                try {
                    streamingClient.stream(request) { delta ->
                        handleDelta(delta, assistantId, sessionId)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (t: Throwable) {
                    errorMessage.value = t.message ?: "发送失败"
                } finally {
                    streamingMessageIds.update { it - assistantId }
                    isSending.value = false
                }
            }
        }
    }

    private suspend fun handleDelta(delta: ChatStreamDelta, messageId: String, sessionId: String) {
        if (delta.contentDelta != null || delta.thinkingDelta != null) {
            val existing = repository.messagesForSession(sessionId).firstOrNull { it.id == messageId }
            var updated = existing ?: ChatMessage(
                id = messageId,
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
            )
            if (delta.contentDelta != null) {
                updated = updated.copy(content = updated.content + delta.contentDelta)
            }
            if (delta.thinkingDelta != null) {
                val newThinking = (thinkingBuffer.value[messageId] ?: updated.thinking.orEmpty()) + delta.thinkingDelta
                thinkingBuffer.update { it + (messageId to newThinking) }
                updated = updated.copy(thinking = newThinking)
            }
            repository.upsertAssistantMessage(updated)
        }
        if (delta.isComplete) {
            streamingMessageIds.update { it - messageId }
            isSending.value = false
        }
    }

    fun onStopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        isSending.value = false
    }

    fun onDismissError() {
        errorMessage.value = null
    }

    fun openSettings() {
        showSettings.value = true
    }

    fun closeSettings() {
        showSettings.value = false
    }

    fun saveSettings(state: SettingsDialogState) {
        viewModelScope.launch {
            settingsRepository.updateOpenAi(state.openAiKey, state.openAiBaseUrl)
            settingsRepository.updateDeepSeek(state.deepSeekKey, state.deepSeekBaseUrl)
            settingsRepository.updateGemini(state.geminiKey, state.geminiBaseUrl)
            settingsRepository.updateTimeout(state.requestTimeoutSeconds)
            settingsRepository.updateReasoningEffort(state.reasoningEffort)
        }
    }

    suspend fun exportBackup(uri: Uri): Result<Unit> = backupManager.exportToUri(uri)

    suspend fun importBackup(uri: Uri): Result<Unit> = backupManager.importFromUri(uri)

    private fun payloadForMessage(message: ChatMessage): ChatMessagePayload =
        ChatMessagePayload(role = message.role, content = message.content)

    private fun ChatRepositoryState.toUiState(
        composer: String,
        isSending: Boolean,
        streamingIds: Set<String>,
        thinking: Map<String, String>,
        error: String?,
        settingsVisible: Boolean,
        settings: AppSettings,
    ): HtmlAppUiState {
        val sessionsUi = sessions.map { session ->
            ChatSessionUi(
                id = session.id,
                title = session.title,
                subtitle = formatSubtitle(session.updatedAt),
                isActive = session.id == selectedSessionId,
            )
        }
        val models = modelPresets.map { preset ->
            ModelPresetUi(id = preset.id, displayName = preset.displayName)
        }
        val messagesUi = messageWindow.messages.map { message ->
            ChatMessageUi(
                id = message.id,
                role = if (message.role == MessageRole.USER) ChatRole.User else ChatRole.Assistant,
                content = message.content,
                modelLabel = message.modelId,
                thinking = thinking[message.id] ?: message.thinking,
                isStreaming = streamingIds.contains(message.id),
            )
        }
        val settingsState = if (settingsVisible) {
            SettingsDialogState(
                openAiKey = settings.openAi.apiKey,
                openAiBaseUrl = settings.openAi.baseUrl,
                deepSeekKey = settings.deepSeek.apiKey,
                deepSeekBaseUrl = settings.deepSeek.baseUrl,
                geminiKey = settings.gemini.apiKey,
                geminiBaseUrl = settings.gemini.baseUrl,
                requestTimeoutSeconds = settings.requestTimeoutSeconds,
                reasoningEffort = settings.reasoningEffort,
            )
        } else null

        return HtmlAppUiState(
            sessions = sessionsUi,
            selectedSessionId = selectedSessionId,
            availableModels = models,
            selectedModelId = selectedModelId,
            messages = messagesUi,
            composerText = composer,
            isSending = isSending,
            canLoadMore = messageWindow.hasMoreBefore,
            totalMessages = messageWindow.totalCount,
            errorMessage = error,
            settings = settingsState,
        )
    }

    private fun formatSubtitle(updatedAt: Long): String {
        val delta = System.currentTimeMillis() - updatedAt
        val minutes = (delta / 60000L).coerceAtLeast(0)
        return if (minutes < 1) "刚刚" else "${minutes} 分钟前"
    }

    private fun AppSettings.forProvider(provider: com.example.htmlapp.data.model.ModelProvider): ProviderSettings = when (provider) {
        com.example.htmlapp.data.model.ModelProvider.OPENAI -> openAi
        com.example.htmlapp.data.model.ModelProvider.DEEPSEEK -> deepSeek
        com.example.htmlapp.data.model.ModelProvider.GEMINI -> gemini
    }

    private fun defaultBaseUrl(provider: com.example.htmlapp.data.model.ModelProvider): String = when (provider) {
        com.example.htmlapp.data.model.ModelProvider.OPENAI -> "https://api.openai.com/v1"
        com.example.htmlapp.data.model.ModelProvider.DEEPSEEK -> "https://api.deepseek.com/v1"
        com.example.htmlapp.data.model.ModelProvider.GEMINI -> "https://generativelanguage.googleapis.com/v1beta"
    }
}

