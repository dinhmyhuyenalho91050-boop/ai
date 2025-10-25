package com.example.htmlapp.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

class MessageWindowManager(
    private val defaultWindow: Int = 80,
    private val step: Int = 40,
) {
    private val windows = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun windowSize(sessionId: String): Flow<Int> = windows.asStateFlow().map { map ->
        map[sessionId] ?: defaultWindow
    }

    fun reset(sessionId: String) {
        windows.update(sessionId, defaultWindow)
    }

    fun expand(sessionId: String) {
        windows.update(sessionId) { current ->
            val next = current + step
            if (next < defaultWindow) defaultWindow else next
        }
    }

    fun shrink(sessionId: String) {
        windows.update(sessionId, defaultWindow)
    }

    private fun MutableStateFlow<Map<String, Int>>.update(
        sessionId: String,
        value: Int,
    ) {
        val current = value
        this.value = this.value.toMutableMap().apply { put(sessionId, current) }
    }

    private fun MutableStateFlow<Map<String, Int>>.update(
        sessionId: String,
        block: (Int) -> Int,
    ) {
        val map = value.toMutableMap()
        val current = map[sessionId] ?: defaultWindow
        map[sessionId] = block(current)
        value = map
    }
}
