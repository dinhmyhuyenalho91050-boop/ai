package com.example.htmlapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.dataStoreFile
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatRepository(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val dataStore: DataStore<ChatStore> = DataStoreFactory.create(
        serializer = ChatStoreSerializer,
        scope = scope,
        produceFile = { context.dataStoreFile("chat_store.json") },
    )

    private val windowManager = MessageWindowManager()

    val store: Flow<ChatStore> = dataStore.data

    val sessions: Flow<List<ChatSessionEntity>> = store.map { store ->
        store.sessions.sortedByDescending { it.updatedAt }
    }

    val selectedSessionId: Flow<String?> = store.map { it.selectedSessionId }

    fun messages(sessionId: String): Flow<List<ChatMessageEntity>> =
        combine(store, windowManager.windowSize(sessionId)) { store, window ->
            val all = store.messages
                .asSequence()
                .filter { it.sessionId == sessionId }
                .sortedBy { it.createdAt }
                .toList()
            if (all.size <= window) {
                all
            } else {
                all.takeLast(window)
            }
        }

    fun canLoadMore(sessionId: String): Flow<Boolean> =
        combine(store, windowManager.windowSize(sessionId)) { store, window ->
            store.messages.count { it.sessionId == sessionId } > window
        }

    suspend fun ensureSeedData() {
        mutex.withLock {
            val current = store.first()
            if (current.sessions.isNotEmpty()) return
            val sessionId = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val session = ChatSessionEntity(
                id = sessionId,
                title = "新的对话",
                createdAt = now,
                updatedAt = now,
            )
            val welcome = ChatMessageEntity(
                id = UUID.randomUUID().toString(),
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "欢迎使用原生 AI 对话应用！",
                modelId = null,
                createdAt = now,
            )
            dataStore.updateData { store ->
                store.copy(
                    sessions = listOf(session),
                    messages = listOf(welcome),
                    selectedSessionId = sessionId,
                )
            }
            windowManager.reset(sessionId)
        }
    }

    suspend fun selectSession(sessionId: String) {
        dataStore.updateData { store ->
            if (store.selectedSessionId == sessionId) return@updateData store
            store.copy(selectedSessionId = sessionId)
        }
        windowManager.reset(sessionId)
    }

    suspend fun createSession(title: String): String {
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dataStore.updateData { store ->
            val newSession = ChatSessionEntity(
                id = sessionId,
                title = title,
                createdAt = now,
                updatedAt = now,
            )
            store.copy(
                sessions = listOf(newSession) + store.sessions,
                selectedSessionId = sessionId,
            )
        }
        windowManager.reset(sessionId)
        return sessionId
    }

    suspend fun renameSession(sessionId: String, title: String) {
        dataStore.updateData { store ->
            val updated = store.sessions.map { session ->
                if (session.id == sessionId) {
                    session.copy(title = title, updatedAt = System.currentTimeMillis())
                } else {
                    session
                }
            }
            store.copy(sessions = updated)
        }
    }

    suspend fun appendMessage(
        sessionId: String,
        role: MessageRole,
        content: String,
        modelId: String?,
        thinking: String? = null,
        streaming: Boolean = false,
    ): ChatMessageEntity {
        val message = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = role,
            content = content,
            modelId = modelId,
            thinking = thinking,
            createdAt = System.currentTimeMillis(),
            isStreaming = streaming,
        )
        dataStore.updateData { store ->
            val updatedSessions = store.sessions.map { session ->
                if (session.id == sessionId) {
                    session.copy(updatedAt = message.createdAt)
                } else {
                    session
                }
            }
            store.copy(
                messages = store.messages + message,
                sessions = updatedSessions,
            )
        }
        return message
    }

    suspend fun markStreaming(messageId: String, streaming: Boolean) {
        dataStore.updateData { store ->
            store.copy(messages = store.messages.map { msg ->
                if (msg.id == messageId) msg.copy(isStreaming = streaming) else msg
            })
        }
    }

    suspend fun updateMessage(
        messageId: String,
        content: String? = null,
        thinking: String? = null,
        streaming: Boolean? = null,
    ) {
        dataStore.updateData { store ->
            store.copy(
                messages = store.messages.map { msg ->
                    if (msg.id == messageId) {
                        msg.copy(
                            content = content ?: msg.content,
                            thinking = thinking ?: msg.thinking,
                            isStreaming = streaming ?: msg.isStreaming,
                        )
                    } else {
                        msg
                    }
                },
            )
        }
    }

    suspend fun loadOlder(sessionId: String) {
        windowManager.expand(sessionId)
    }

    suspend fun messagesForSession(sessionId: String): List<ChatMessageEntity> {
        return store.first().messages.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }
    }

    suspend fun replaceStore(newStore: ChatStore) {
        dataStore.updateData { newStore }
        newStore.selectedSessionId?.let { windowManager.reset(it) }
    }

    suspend fun deleteEmptySessions() {
        dataStore.updateData { store ->
            store.copy(sessions = store.sessions.filterNot { session ->
                store.messages.none { it.sessionId == session.id }
            })
        }
    }

    fun observeUiState(): Flow<RepositoryState> = store.map { store ->
        RepositoryState(
            sessions = store.sessions.sortedByDescending { it.updatedAt },
            selectedSessionId = store.selectedSessionId,
            totalMessages = store.messages.groupBy { it.sessionId }.mapValues { it.value.size },
        )
    }

    data class RepositoryState(
        val sessions: List<ChatSessionEntity>,
        val selectedSessionId: String?,
        val totalMessages: Map<String, Int>,
    )
}
