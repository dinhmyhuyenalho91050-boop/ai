package com.example.htmlapp.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.htmlapp.data.backup.BackupManager
import com.example.htmlapp.data.db.HtmlAppDatabase
import com.example.htmlapp.data.model.ChatEvent
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatRole
import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.data.network.AiStreamingClient
import com.example.htmlapp.data.repository.ChatRepository
import com.example.htmlapp.data.repository.ModelRepository
import com.example.htmlapp.ui.HtmlAppUiState.Companion.DEFAULT_WINDOW_LIMIT
import com.example.htmlapp.ui.HtmlAppUiState.Companion.WINDOW_INCREMENT
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

class HtmlAppViewModel(application: Application) : AndroidViewModel(application) {
    private val database = HtmlAppDatabase.getInstance(application)
    private val chatRepository = ChatRepository(
        sessionDao = database.chatSessionDao(),
        messageDao = database.chatMessageDao(),
    )
    private val modelRepository = ModelRepository()
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
        .build()
    private val streamingClient = AiStreamingClient(httpClient)
    private val backupManager = BackupManager(application)

    private val selectedSessionId = MutableStateFlow<String?>(null)
    private val messageWindowLimit = MutableStateFlow(DEFAULT_WINDOW_LIMIT)
    private val composerText = MutableStateFlow("")
    private val isSending = MutableStateFlow(false)
    private val hasMoreHistory = MutableStateFlow(false)

    private var streamingJob: Job? = null

    private val sessionsFlow = chatRepository.observeSessions()

    private val messagesFlow = selectedSessionId
        .filterNotNull()
        .flatMapLatest { sessionId ->
            messageWindowLimit.flatMapLatest { limit ->
                chatRepository.observeRecentMessages(sessionId, limit)
            }
        }

    val uiState: StateFlow<HtmlAppUiState> = combine(
        sessionsFlow,
        selectedSessionId,
        modelRepository.selectedModelId(),
        messagesFlow,
        composerText,
        isSending,
        messageWindowLimit,
        hasMoreHistory,
    ) { sessions, selectedId, modelId, messages, text, sending, limit, hasMore ->
        val sessionUis = sessions.map { session ->
            ChatSessionUi(
                id = session.id,
                title = session.title,
                subtitle = formatSessionSubtitle(session),
                isActive = session.id == selectedId,
            )
        }
        HtmlAppUiState(
            sessions = sessionUis,
            selectedSessionId = selectedId,
            availableModels = modelRepository.presets().map { it.toUi() },
            selectedModelId = modelId,
            messages = messages.map { it.toUi() },
            composerText = text,
            isSending = sending,
            hasMoreHistory = hasMore,
            messageWindowLimit = limit,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HtmlAppUiState.empty(),
    )

    init {
        viewModelScope.launch {
            sessionsFlow.collect { sessions ->
                if (sessions.isEmpty()) {
                    val id = chatRepository.createSession()
                    selectedSessionId.value = id
                } else if (selectedSessionId.value == null) {
                    selectedSessionId.value = sessions.first().id
                } else if (sessions.none { it.id == selectedSessionId.value }) {
                    selectedSessionId.value = sessions.first().id
                }
            }
        }

        viewModelScope.launch {
            combine(
                selectedSessionId.filterNotNull(),
                messageWindowLimit,
                messagesFlow,
            ) { sessionId, limit, messages -> Triple(sessionId, limit, messages.size) }
                .collect { (sessionId, limit, _) ->
                    val total = chatRepository.getMessageCount(sessionId)
                    hasMoreHistory.value = total > limit
                }
        }
    }

    fun selectSession(sessionId: String) {
        selectedSessionId.value = sessionId
        messageWindowLimit.value = DEFAULT_WINDOW_LIMIT
    }

    fun selectModel(modelId: String) {
        modelRepository.selectModel(modelId)
    }

    fun onComposerChanged(text: String) {
        composerText.value = text
    }

    fun loadMoreMessages() {
        messageWindowLimit.update { it + WINDOW_INCREMENT }
    }

    fun newChat() {
        viewModelScope.launch {
            val newId = chatRepository.createSession()
            selectedSessionId.value = newId
            composerText.value = ""
            messageWindowLimit.value = DEFAULT_WINDOW_LIMIT
        }
    }

    fun exportBackup(uri: Uri) {
        viewModelScope.launch {
            val sessions = sessionsFlow.first()
            val messages = sessions.associate { session ->
                session.id to chatRepository.getAllMessages(session.id)
            }
            backupManager.export(uri, sessions, messages)
        }
    }

    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        val sessionId = selectedSessionId.value
        if (sessionId != null) {
            viewModelScope.launch {
                val streamingMessage = chatRepository.getAllMessages(sessionId).lastOrNull { it.isStreaming }
                if (streamingMessage != null) {
                    chatRepository.updateMessageContent(
                        streamingMessage.id,
                        content = streamingMessage.content,
                        thinking = streamingMessage.thinking,
                        isStreaming = false,
                    )
                }
            }
        }
        isSending.value = false
    }

    fun sendMessage() {
        val text = composerText.value.trim()
        if (text.isEmpty()) return
        val sessionId = selectedSessionId.value
        viewModelScope.launch {
            val targetSessionId = sessionId ?: chatRepository.createSession().also {
                selectedSessionId.value = it
            }
            composerText.value = ""
            val selectedModel = modelRepository.selectedModelId().value
            chatRepository.appendMessage(
                sessionId = targetSessionId,
                role = ChatRole.USER,
                content = text,
                modelLabel = selectedModel,
                thinking = null,
                isStreaming = false,
            )
            val assistantId = chatRepository.appendMessage(
                sessionId = targetSessionId,
                role = ChatRole.ASSISTANT,
                content = "",
                modelLabel = selectedModel,
                thinking = null,
                isStreaming = true,
            )
            isSending.value = true

            val messages = chatRepository.getAllMessages(targetSessionId)
            streamingJob?.cancel()
            streamingJob = viewModelScope.launch {
                var contentBuffer = ""
                var thinkingBuffer = ""
                streamingClient.streamChat(
                    endpoint = AiStreamingClient.StreamingEndpoint(
                        url = "https://api.openai.com/v1/chat/completions",
                        apiKey = null,
                    ),
                    payload = AiStreamingClient.ChatCompletionRequest(
                        model = selectedModel,
                        messages = messages.map { message ->
                            AiStreamingClient.ChatCompletionRequest.Message(
                                role = message.role.name.lowercase(),
                                content = message.content,
                            )
                        },
                    ),
                ).collect { event ->
                    when (event) {
                        is ChatEvent.StreamDelta -> {
                            contentBuffer += event.contentDelta.orEmpty()
                            thinkingBuffer += event.thinkingDelta.orEmpty()
                            chatRepository.updateMessageContent(
                                assistantId,
                                content = contentBuffer,
                                thinking = thinkingBuffer.ifBlank { null },
                                isStreaming = true,
                            )
                        }

                        is ChatEvent.Completed -> {
                            chatRepository.updateMessageContent(
                                assistantId,
                                content = contentBuffer,
                                thinking = thinkingBuffer.ifBlank { null },
                                isStreaming = false,
                            )
                            isSending.value = false
                        }
                    }
                }
                isSending.value = false
            }
        }
    }

    private fun ChatMessage.toUi(): ChatMessageUi = ChatMessageUi(
        id = id,
        role = role,
        content = content,
        modelLabel = modelLabel,
        thinking = thinking,
        isStreaming = isStreaming,
    )

    private fun ModelPreset.toUi(): ModelPresetUi = ModelPresetUi(
        id = id,
        displayName = displayName,
    )

    private fun formatSessionSubtitle(session: com.example.htmlapp.data.model.ChatSession): String {
        val minutes = ((System.currentTimeMillis() - session.updatedAt) / 60000L).toInt()
        return if (minutes <= 0) "刚刚" else "${minutes} 分钟前"
    }
}
