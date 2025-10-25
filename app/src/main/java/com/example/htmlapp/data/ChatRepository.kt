package com.example.htmlapp.data

import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatSession
import com.example.htmlapp.data.model.ChatStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChatRepository(
    private val localDataSource: ChatLocalDataSource,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private val store = MutableStateFlow(ChatStore())

    val storeFlow: StateFlow<ChatStore> = store

    suspend fun initialize() {
        val loaded = withContext(dispatcher) { localDataSource.load() }
        store.value = if (loaded.sessions.isEmpty()) {
            val session = ChatSession(
                id = generateId(),
                title = "新的对话",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            ChatStore(
                sessions = listOf(session),
                messages = mapOf(session.id to emptyList()),
                selectedSessionId = session.id,
            )
        } else {
            val messages = loaded.messages.mapValues { (_, value) -> value.sortedBy { it.createdAt } }
            loaded.copy(messages = messages)
        }
        persistAsync(store.value)
    }

    fun messagesFlow(sessionId: String?): Flow<List<ChatMessage>> = store
        .map { state ->
            val id = sessionId ?: state.selectedSessionId
            if (id == null) emptyList() else state.messages[id].orEmpty().sortedBy { it.createdAt }
        }
        .distinctUntilChanged()

    suspend fun createSession(title: String = "新的对话"): String {
        val timestamp = System.currentTimeMillis()
        val newSession = ChatSession(
            id = generateId(),
            title = title.ifBlank { "新的对话" },
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        updateStore {
            val sessions = listOf(newSession) + it.sessions
            val messages = it.messages + (newSession.id to emptyList())
            it.copy(
                sessions = sessions,
                messages = messages,
                selectedSessionId = newSession.id,
            )
        }
        return newSession.id
    }

    suspend fun selectSession(id: String) {
        updateStore { current ->
            if (current.selectedSessionId == id) current else current.copy(selectedSessionId = id)
        }
    }

    suspend fun renameSession(id: String, title: String) {
        updateStore { current ->
            val sessions = current.sessions.map { session ->
                if (session.id == id) session.copy(title = title, updatedAt = System.currentTimeMillis()) else session
            }
            current.copy(sessions = sessions)
        }
    }

    suspend fun appendMessage(message: ChatMessage) {
        updateStore { current ->
            val existing = current.messages[message.sessionId].orEmpty()
            val updatedMessages = existing + message
            val updatedSession = current.sessions.map { session ->
                if (session.id == message.sessionId) {
                    session.copy(updatedAt = System.currentTimeMillis())
                } else session
            }
            current.copy(
                sessions = updatedSession,
                messages = current.messages + (message.sessionId to updatedMessages),
            )
        }
    }

    suspend fun updateMessage(sessionId: String, messageId: String, transform: (ChatMessage) -> ChatMessage) {
        updateStore { current ->
            val currentMessages = current.messages[sessionId].orEmpty()
            val updated = currentMessages.map { existing ->
                if (existing.id == messageId) transform(existing) else existing
            }
            current.copy(messages = current.messages + (sessionId to updated))
        }
    }

    suspend fun clearStreamingFlags(sessionId: String) {
        updateStore { current ->
            val updated = current.messages[sessionId].orEmpty().map { message ->
                if (message.isStreaming) message.copy(isStreaming = false) else message
            }
            current.copy(messages = current.messages + (sessionId to updated))
        }
    }

    suspend fun deleteMessage(sessionId: String, messageId: String) {
        updateStore { current ->
            val updated = current.messages[sessionId].orEmpty().filterNot { it.id == messageId }
            current.copy(messages = current.messages + (sessionId to updated))
        }
    }

    suspend fun deleteSession(sessionId: String) {
        updateStore { current ->
            val sessions = current.sessions.filterNot { it.id == sessionId }
            val messages = current.messages - sessionId
            val selected = if (current.selectedSessionId == sessionId) sessions.firstOrNull()?.id else current.selectedSessionId
            current.copy(sessions = sessions, messages = messages, selectedSessionId = selected)
        }
    }

    private suspend fun updateStore(transform: (ChatStore) -> ChatStore) {
        val updated = mutex.withLock {
            val next = transform(store.value)
            store.value = next
            next
        }
        persistAsync(updated)
    }

    private fun persistAsync(store: ChatStore) {
        scope.launch { localDataSource.save(store) }
    }

    private fun generateId(): String = java.util.UUID.randomUUID().toString()
}
