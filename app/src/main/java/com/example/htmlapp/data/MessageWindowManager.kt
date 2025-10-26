package com.example.htmlapp.data

import com.example.htmlapp.data.model.ChatMessage

class MessageWindowManager(
    private val initialWindowSize: Int = 40,
    private val expandStep: Int = 40,
) {
    private val windowSizes = mutableMapOf<String, Int>()

    fun snapshot(sessionId: String, messages: List<ChatMessage>): MessageWindowSnapshot {
        val window = windowSizes.getOrPut(sessionId) { initialWindowSize }
        val startIndex = (messages.size - window).coerceAtLeast(0)
        val displayed = messages.drop(startIndex)
        val canLoadMore = startIndex > 0
        return MessageWindowSnapshot(
            messages = displayed,
            canLoadMore = canLoadMore,
        )
    }

    fun expand(sessionId: String) {
        val current = windowSizes.getOrPut(sessionId) { initialWindowSize }
        windowSizes[sessionId] = current + expandStep
    }

    fun reset(sessionId: String) {
        windowSizes[sessionId] = initialWindowSize
    }
}

data class MessageWindowSnapshot(
    val messages: List<ChatMessage>,
    val canLoadMore: Boolean,
)
