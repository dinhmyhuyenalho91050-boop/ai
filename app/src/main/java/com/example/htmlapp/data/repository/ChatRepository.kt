package com.example.htmlapp.data.repository

import android.content.Context
import android.util.AtomicFile
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatSession
import com.example.htmlapp.data.model.MessageRole
import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.data.model.PersistedState
import com.example.htmlapp.data.model.defaultModelPresets
import com.example.htmlapp.data.model.defaultPromptPresets
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.UUID

private const val STORAGE_FILE = "chat_state.json"
private const val DEFAULT_WINDOW_SIZE = 50
private const val WINDOW_CHUNK = 30

data class MessageWindowState(
    val sessionId: String,
    val messages: List<ChatMessage>,
    val hasMoreBefore: Boolean,
    val totalCount: Int,
)

data class ChatRepositoryState(
    val sessions: List<ChatSession>,
    val selectedSessionId: String,
    val selectedModelId: String,
    val modelPresets: List<ModelPreset>,
    val messageWindow: MessageWindowState,
)

private data class MessageWindowRange(val start: Int, val size: Int) {
    fun clamp(total: Int): MessageWindowRange {
        if (total == 0) return copy(start = 0)
        val maxStart = (total - size).coerceAtLeast(0)
        val newStart = start.coerceIn(0, maxStart)
        return if (newStart == start) this else copy(start = newStart)
    }
}

class ChatRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val applicationContext = context.applicationContext
    private val atomicFile = AtomicFile(File(applicationContext.filesDir, STORAGE_FILE))
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val mutex = Mutex()

    private val windowVersion = MutableStateFlow(0)

    private val stateFlow = MutableStateFlow(loadInitialState())

    private val pendingWrites = MutableSharedFlow<PersistedState>(extraBufferCapacity = 1)

    private val windows = mutableMapOf<String, MessageWindowRange>()

    init {
        scope.launch {
            pendingWrites
                .debounce(250)
                .collect { persistInternal(it) }
        }
    }

    val state: StateFlow<ChatRepositoryState> = combine(stateFlow, windowVersion) { state, _ ->
        buildViewState(state)
    }.stateIn(
        scope,
        started = SharingStarted.Eagerly,
        initialValue = buildViewState(stateFlow.value),
    )

    fun currentState(): ChatRepositoryState = buildViewState(stateFlow.value)

    suspend fun selectSession(sessionId: String) {
        updateState { state -> state.copy(selectedSessionId = sessionId) }
    }

    suspend fun selectModel(modelId: String) {
        updateState { state -> state.copy(selectedModelId = modelId) }
    }

    fun persistedSnapshot(): PersistedState = stateFlow.value

    fun messagesForSession(sessionId: String): List<ChatMessage> =
        stateFlow.value.messagesBySession[sessionId].orEmpty()

    suspend fun importSnapshot(snapshot: PersistedState) {
        val ensured = snapshot.ensureDefaults()
        mutex.withLock {
            stateFlow.value = ensured
            windows.clear()
        }
        pendingWrites.tryEmit(ensured)
        windowVersion.update { it + 1 }
    }

    suspend fun createSession(title: String = "新的对话"): String {
        val now = System.currentTimeMillis()
        val newSession = ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            createdAt = now,
            updatedAt = now,
        )
        updateState { state ->
            val sessions = listOf(newSession) + state.sessions
            state.copy(
                sessions = sessions,
                selectedSessionId = newSession.id,
                messagesBySession = state.messagesBySession + (newSession.id to emptyList()),
            )
        }
        return newSession.id
    }

    suspend fun renameSession(sessionId: String, newTitle: String) {
        updateState { state ->
            state.copy(
                sessions = state.sessions.map { session ->
                    if (session.id == sessionId) session.copy(title = newTitle, updatedAt = System.currentTimeMillis()) else session
                },
            )
        }
    }

    suspend fun deleteSession(sessionId: String) {
        updateState { state ->
            if (state.sessions.size <= 1) return@updateState state
            val sessions = state.sessions.filterNot { it.id == sessionId }
            val messages = state.messagesBySession - sessionId
            val selected = if (state.selectedSessionId == sessionId) sessions.firstOrNull()?.id else state.selectedSessionId
            windows -= sessionId
            state.copy(
                sessions = sessions,
                messagesBySession = messages,
                selectedSessionId = selected,
            )
        }
    }

    suspend fun appendUserMessage(sessionId: String, content: String, modelId: String): ChatMessage {
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = MessageRole.USER,
            content = content,
            modelId = modelId,
        )
        appendMessageInternal(message)
        return message
    }

    suspend fun upsertAssistantMessage(message: ChatMessage) {
        updateState { state ->
            val messages = state.messagesBySession[message.sessionId].orEmpty()
            val index = messages.indexOfFirst { it.id == message.id }
            val updatedMessages = if (index >= 0) {
                messages.toMutableList().also { it[index] = message.copy(updatedAt = System.currentTimeMillis()) }
            } else {
                (messages + message)
            }
            state.copy(
                messagesBySession = state.messagesBySession + (message.sessionId to updatedMessages),
                sessions = state.sessions.map { session ->
                    if (session.id == message.sessionId) {
                        session.copy(updatedAt = System.currentTimeMillis())
                    } else session
                },
            )
        }
    }

    suspend fun loadOlderMessages(sessionId: String) {
        mutex.withLock {
            val messages = stateFlow.value.messagesBySession[sessionId].orEmpty()
            val current = windows[sessionId] ?: defaultWindow(messages.size)
            if (!current.hasMore(messages.size)) return
            val newStart = (current.start - WINDOW_CHUNK).coerceAtLeast(0)
            val delta = current.start - newStart
            windows[sessionId] = MessageWindowRange(newStart, current.size + delta)
        }
        windowVersion.update { it + 1 }
    }

    private suspend fun appendMessageInternal(message: ChatMessage) {
        updateState { state ->
            val messages = state.messagesBySession[message.sessionId].orEmpty() + message
            val updatedSessions = state.sessions.map { session ->
                if (session.id == message.sessionId) session.copy(updatedAt = message.createdAt) else session
            }
            state.copy(
                messagesBySession = state.messagesBySession + (message.sessionId to messages),
                sessions = updatedSessions,
            )
        }
    }

    private suspend fun updateState(transform: (PersistedState) -> PersistedState) {
        val newState = mutex.withLock {
            val updated = transform(stateFlow.value).ensureDefaults()
            stateFlow.value = updated
            updated
        }
        pendingWrites.tryEmit(newState)
        windowVersion.update { it + 1 }
    }

    private fun buildViewState(state: PersistedState): ChatRepositoryState {
        val sessions = if (state.sessions.isEmpty()) {
            val now = System.currentTimeMillis()
            listOf(
                ChatSession(
                    id = UUID.randomUUID().toString(),
                    title = "新的对话",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else state.sessions

        val sessionId = state.selectedSessionId ?: sessions.first().id
        val messages = state.messagesBySession[sessionId].orEmpty()
        val window = ensureWindow(sessionId, messages.size)
        val fromIndex = window.start.coerceAtLeast(0).coerceAtMost(messages.size)
        val windowMessages = messages.subList(fromIndex, messages.size)
        val hasMore = fromIndex > 0

        val selectedModelId = state.selectedModelId ?: state.presets.firstOrNull()?.id.orEmpty()

        return ChatRepositoryState(
            sessions = sessions,
            selectedSessionId = sessionId,
            selectedModelId = selectedModelId,
            modelPresets = state.presets,
            messageWindow = MessageWindowState(
                sessionId = sessionId,
                messages = windowMessages,
                hasMoreBefore = hasMore,
                totalCount = messages.size,
            ),
        )
    }

    private fun ensureWindow(sessionId: String, total: Int): MessageWindowRange {
        val current = windows[sessionId] ?: defaultWindow(total)
        val clamped = current.clamp(total)
        windows[sessionId] = clamped
        return clamped
    }

    private fun MessageWindowRange.hasMore(total: Int): Boolean = start > 0 && total > size

    private fun defaultWindow(total: Int): MessageWindowRange {
        val size = DEFAULT_WINDOW_SIZE.coerceAtMost(total.coerceAtLeast(1))
        val start = (total - size).coerceAtLeast(0)
        return MessageWindowRange(start = start, size = DEFAULT_WINDOW_SIZE)
    }

    private fun PersistedState.ensureDefaults(): PersistedState {
        val ensuredPresets = if (presets.isEmpty()) defaultModelPresets() else presets
        val ensuredPrompts = if (promptPresets.isEmpty()) defaultPromptPresets() else promptPresets
        val sessionsEnsured = if (sessions.isEmpty()) {
            val now = System.currentTimeMillis()
            listOf(
                ChatSession(
                    id = UUID.randomUUID().toString(),
                    title = "新的对话",
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } else sessions
        val selectedSession = (selectedSessionId ?: sessionsEnsured.first().id)
        val selectedModel = (selectedModelId ?: ensuredPresets.firstOrNull()?.id)
        val messagesEnsured = messagesBySession + sessionsEnsured.associate { session ->
            session.id to messagesBySession[session.id].orEmpty()
        }
        return copy(
            sessions = sessionsEnsured,
            presets = ensuredPresets,
            promptPresets = ensuredPrompts,
            selectedSessionId = selectedSession,
            selectedModelId = selectedModel,
            messagesBySession = messagesEnsured,
        )
    }

    private fun loadInitialState(): PersistedState {
        if (!atomicFile.baseFile.exists()) {
            val defaultState = PersistedState.Empty.ensureDefaults()
            scope.launch { persistInternal(defaultState) }
            return defaultState
        }
        return try {
            FileInputStream(atomicFile.baseFile).use { input ->
                val raw = input.readBytes().decodeToString()
                PersistedState.fromJson(raw).ensureDefaults()
            }
        } catch (ioe: IOException) {
            PersistedState.Empty.ensureDefaults()
        }
    }

    private suspend fun persistInternal(state: PersistedState) {
        withContext(ioDispatcher) {
            try {
                val output = atomicFile.startWrite()
                try {
                    val payload = state.toJson().encodeToByteArray()
                    output.write(payload)
                    atomicFile.finishWrite(output)
                } catch (t: Throwable) {
                    atomicFile.failWrite(output)
                    throw t
                }
            } catch (_: IOException) {
                // ignore for now
            }
        }
    }
}

