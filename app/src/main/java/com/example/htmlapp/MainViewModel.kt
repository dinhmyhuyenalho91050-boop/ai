package com.example.htmlapp

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlapp.backup.BackupManager
import com.example.htmlapp.backup.BackupManager.BackupResult
import com.example.htmlapp.data.ChatMessageEntity
import com.example.htmlapp.data.ChatRepository
import com.example.htmlapp.data.ChatSessionEntity
import com.example.htmlapp.data.MessageRole
import com.example.htmlapp.network.ChatCompletionRequest
import com.example.htmlapp.network.ChatMessagePayload
import com.example.htmlapp.network.ChatStreamingClient
import com.example.htmlapp.network.EchoStreamingClient
import com.example.htmlapp.network.OpenAiStreamingClient
import com.example.htmlapp.network.StreamingEvent
import com.example.htmlapp.network.defaultOkHttpClient
import com.example.htmlapp.ui.ChatMessageUi
import com.example.htmlapp.ui.ChatRole
import com.example.htmlapp.ui.ChatSessionUi
import com.example.htmlapp.ui.HtmlAppUiState
import com.example.htmlapp.ui.ModelPresetUi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatRepository(application.applicationContext)
    private val streamingClient: ChatStreamingClient = OpenAiStreamingClient(defaultOkHttpClient())
    private val fallbackClient: ChatStreamingClient = EchoStreamingClient()
    private val backupManager = BackupManager(application.applicationContext, repository)

    private val availableModels = listOf(
        ModelPresetUi("gpt-4o", "GPT-4o"),
        ModelPresetUi("gpt-4o-mini", "GPT-4o mini"),
        ModelPresetUi("deepseek-chat", "DeepSeek"),
    )

    private val _uiState = MutableStateFlow(
        HtmlAppUiState(
            sessions = emptyList(),
            selectedSessionId = null,
            availableModels = availableModels,
            selectedModelId = availableModels.first().id,
            messages = emptyList(),
            composerText = "",
            isSending = false,
            canLoadMore = false,
            isSettingsVisible = false,
            statusMessage = null,
            apiKey = "",
        ),
    )
    val uiState: StateFlow<HtmlAppUiState> = _uiState.asStateFlow()

    private var messagesJob: Job? = null
    private var loadMoreJob: Job? = null
    private var streamingJob: Job? = null
    private var activeAssistantMessageId: String? = null

    private val relativeTimeFormatter = RelativeTimeFormatter()

    init {
        viewModelScope.launch {
            repository.ensureSeedData()
        }
        viewModelScope.launch {
            repository.observeUiState()
                .distinctUntilChanged()
                .collectLatest { state ->
                    val selectedId = state.selectedSessionId ?: state.sessions.firstOrNull()?.id
                    if (selectedId != null && selectedId != uiState.value.selectedSessionId) {
                        observeMessages(selectedId)
                        observeLoadMore(selectedId)
                    }
                    _uiState.update { current ->
                        current.copy(
                            sessions = state.sessions.map { it.toUi(selectedId) },
                            selectedSessionId = selectedId,
                        )
                    }
                }
        }
    }

    fun onSelectSession(sessionId: String) {
        viewModelScope.launch {
            repository.selectSession(sessionId)
        }
    }

    fun onNewChat() {
        viewModelScope.launch {
            val sessionId = repository.createSession("新的对话")
            repository.selectSession(sessionId)
        }
    }

    fun onSelectModel(modelId: String) {
        _uiState.update { it.copy(selectedModelId = modelId) }
    }

    fun onComposerChange(text: String) {
        _uiState.update { it.copy(composerText = text) }
    }

    fun onApiKeyChange(apiKey: String) {
        _uiState.update { it.copy(apiKey = apiKey) }
    }

    fun onToggleSettings(show: Boolean) {
        _uiState.update { it.copy(isSettingsVisible = show, statusMessage = null) }
    }

    fun onDismissStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun onLoadMore() {
        val sessionId = uiState.value.selectedSessionId ?: return
        viewModelScope.launch { repository.loadOlder(sessionId) }
    }

    fun onSendMessage() {
        val state = uiState.value
        if (state.isSending || state.composerText.isBlank()) return
        val apiKey = state.apiKey
        val sessionId = state.selectedSessionId
        if (sessionId == null) {
            viewModelScope.launch {
                val newId = repository.createSession("新的对话")
                repository.selectSession(newId)
                sendMessageInternal(newId, apiKey)
            }
        } else {
            viewModelScope.launch { sendMessageInternal(sessionId, apiKey) }
        }
    }

    fun onStopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        activeAssistantMessageId?.let { messageId ->
            viewModelScope.launch { repository.updateMessage(messageId, streaming = false) }
            activeAssistantMessageId = null
        }
        _uiState.update { it.copy(isSending = false) }
    }

    fun onExportBackup() {
        viewModelScope.launch {
            when (val result = backupManager.exportBackup()) {
                is BackupResult.Success -> _uiState.update {
                    it.copy(statusMessage = "已导出到下载目录：${result.uri.path}")
                }
                is BackupResult.Failure -> _uiState.update {
                    it.copy(statusMessage = result.message)
                }
            }
        }
    }

    fun onImportBackup(uri: Uri) {
        viewModelScope.launch {
            when (val result = backupManager.importBackup(uri)) {
                is BackupResult.Success -> _uiState.update {
                    it.copy(statusMessage = "导入成功", isSettingsVisible = false)
                }
                is BackupResult.Failure -> _uiState.update {
                    it.copy(statusMessage = result.message)
                }
            }
        }
    }

    private suspend fun sendMessageInternal(sessionId: String, apiKey: String) {
        val prompt = uiState.value.composerText
        if (prompt.isBlank()) return
        _uiState.update { it.copy(composerText = "", isSending = true) }
        val modelId = uiState.value.selectedModelId
        repository.appendMessage(
            sessionId = sessionId,
            role = MessageRole.USER,
            content = prompt,
            modelId = modelId,
        )
        val assistantMessage = repository.appendMessage(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = "",
            modelId = modelId,
            streaming = true,
        )
        activeAssistantMessageId = assistantMessage.id
        observeMessages(sessionId)
        streamingJob?.cancel()
        streamingJob = viewModelScope.launch {
            val history = repository.messagesForSession(sessionId)
            val client = if (apiKey.isBlank()) fallbackClient else streamingClient
            val contentBuilder = StringBuilder()
            val thinkingBuilder = StringBuilder()
            try {
                client.streamCompletion(
                    ChatCompletionRequest(
                        model = modelId,
                        messages = history.map { it.toPayload() },
                        apiKey = apiKey,
                    ),
                ).collect { event ->
                    when (event) {
                        is StreamingEvent.Delta -> {
                            event.contentDelta?.let { contentBuilder.append(it) }
                            event.thinkingDelta?.let { thinkingBuilder.append(it) }
                            repository.updateMessage(
                                messageId = assistantMessage.id,
                                content = contentBuilder.toString(),
                                thinking = thinkingBuilder.toString().takeIf { it.isNotBlank() },
                                streaming = true,
                            )
                        }
                        is StreamingEvent.Completed -> {
                            val finalContent = event.totalContent.ifBlank { contentBuilder.toString() }
                            val finalThinking = event.totalThinking ?: thinkingBuilder.toString().takeIf { it.isNotBlank() }
                            repository.updateMessage(
                                messageId = assistantMessage.id,
                                content = finalContent,
                                thinking = finalThinking,
                                streaming = false,
                            )
                            _uiState.update { it.copy(isSending = false) }
                            activeAssistantMessageId = null
                        }
                        is StreamingEvent.Error -> {
                            repository.updateMessage(
                                messageId = assistantMessage.id,
                                content = event.throwable.message ?: "网络错误",
                                streaming = false,
                            )
                            _uiState.update { it.copy(isSending = false, statusMessage = "生成失败：${event.throwable.message}") }
                            activeAssistantMessageId = null
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                repository.updateMessage(
                    messageId = assistantMessage.id,
                    streaming = false,
                )
                throw cancellation
            } finally {
                _uiState.update { it.copy(isSending = false) }
                if (activeAssistantMessageId == assistantMessage.id) {
                    activeAssistantMessageId = null
                }
            }
        }
    }

    private fun mergeThinking(existing: String?, delta: String?): String? {
        if (existing.isNullOrBlank() && delta.isNullOrBlank()) return existing
        val builder = StringBuilder()
        if (!existing.isNullOrBlank()) builder.append(existing)
        if (!delta.isNullOrBlank()) builder.append(delta)
        return builder.toString()
    }

    private fun observeMessages(sessionId: String) {
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            repository.messages(sessionId).collectLatest { messages ->
                _uiState.update { state ->
                    state.copy(messages = messages.map { it.toUi() })
                }
            }
        }
    }

    private fun observeLoadMore(sessionId: String) {
        loadMoreJob?.cancel()
        loadMoreJob = viewModelScope.launch {
            repository.canLoadMore(sessionId).collectLatest { canLoad ->
                _uiState.update { it.copy(canLoadMore = canLoad) }
            }
        }
    }

    private fun ChatMessageEntity.toUi(): ChatMessageUi = ChatMessageUi(
        id = id,
        role = if (role == MessageRole.USER) ChatRole.User else ChatRole.Assistant,
        content = content,
        modelLabel = modelId,
        thinking = thinking,
        isStreaming = isStreaming,
    )

    private fun ChatMessageEntity.toPayload(): ChatMessagePayload = ChatMessagePayload(
        role = when (role) {
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
            MessageRole.SYSTEM -> "system"
        },
        content = content,
    )

    private fun ChatSessionEntity.toUi(selectedId: String?): ChatSessionUi {
        val subtitle = relativeTimeFormatter.format(updatedAt)
        return ChatSessionUi(
            id = id,
            title = title,
            subtitle = subtitle,
            isActive = id == selectedId,
        )
    }
}

private class RelativeTimeFormatter {
    fun format(timestamp: Long): String {
        val diff = System.currentTimeMillis() - timestamp
        val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "${minutes} 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
    }
}
