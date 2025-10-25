package com.example.htmlapp.data.repository

import com.example.htmlapp.data.db.ChatMessageDao
import com.example.htmlapp.data.db.ChatSessionDao
import com.example.htmlapp.data.model.ChatMessage
import com.example.htmlapp.data.model.ChatMessageEntity
import com.example.htmlapp.data.model.ChatRole
import com.example.htmlapp.data.model.ChatSession
import com.example.htmlapp.data.model.ChatSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private const val DEFAULT_SESSION_TITLE = "新的对话"

class ChatRepository(
    private val sessionDao: ChatSessionDao,
    private val messageDao: ChatMessageDao,
) {
    fun observeSessions(): Flow<List<ChatSession>> {
        return sessionDao.observeSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    fun observeRecentMessages(sessionId: String, limit: Int): Flow<List<ChatMessage>> {
        return messageDao.observeRecentMessages(sessionId, limit).map { entities ->
            entities.map { it.toDomain() }.sortedBy { it.createdAt }
        }
    }

    suspend fun upsertSession(session: ChatSession) {
        sessionDao.upsert(session.toEntity())
    }

    suspend fun createSession(title: String = DEFAULT_SESSION_TITLE): String {
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = newId,
            title = title.ifBlank { DEFAULT_SESSION_TITLE },
            createdAt = now,
            updatedAt = now,
        )
        sessionDao.upsert(session)
        return newId
    }

    suspend fun appendMessage(
        sessionId: String,
        role: ChatRole,
        content: String,
        modelLabel: String?,
        thinking: String?,
        isStreaming: Boolean,
        messageId: String = UUID.randomUUID().toString(),
        createdAt: Long = System.currentTimeMillis(),
    ): String {
        val session = sessionDao.getById(sessionId)
        val entity = ChatMessageEntity(
            id = messageId,
            sessionId = sessionId,
            role = role.name,
            content = content,
            modelLabel = modelLabel,
            thinking = thinking,
            isStreaming = isStreaming,
            createdAt = createdAt,
        )
        messageDao.insert(entity)
        sessionDao.upsert(
            ChatSessionEntity(
                id = sessionId,
                title = session?.title ?: DEFAULT_SESSION_TITLE,
                createdAt = session?.createdAt ?: createdAt,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return messageId
    }

    suspend fun updateMessageContent(
        messageId: String,
        content: String,
        thinking: String?,
        isStreaming: Boolean,
    ) {
        val entity = messageDao.getById(messageId) ?: return
        messageDao.update(
            entity.copy(
                content = content,
                thinking = thinking,
                isStreaming = isStreaming,
            ),
        )
    }

    suspend fun getMessageCount(sessionId: String): Int {
        return messageDao.countMessages(sessionId)
    }

    suspend fun getAllMessages(sessionId: String): List<ChatMessage> {
        return messageDao.getAllForSession(sessionId).map { it.toDomain() }
    }

    suspend fun deleteSession(sessionId: String) {
        messageDao.deleteForSession(sessionId)
        sessionDao.delete(sessionId)
    }
}

private fun ChatSessionEntity.toDomain(): ChatSession =
    ChatSession(id = id, title = title, createdAt = createdAt, updatedAt = updatedAt)

private fun ChatSession.toEntity(): ChatSessionEntity =
    ChatSessionEntity(id = id, title = title, createdAt = createdAt, updatedAt = updatedAt)

private fun ChatMessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = ChatRole.valueOf(role),
    content = content,
    modelLabel = modelLabel,
    thinking = thinking,
    isStreaming = isStreaming,
    createdAt = createdAt,
)
