package com.example.htmlapp.data.network

import com.example.htmlapp.data.model.ChatEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import org.json.JSONObject

private val JSON_MEDIA_TYPE = "application/json".toMediaType()

class AiStreamingClient(
    private val httpClient: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun streamChat(
        endpoint: StreamingEndpoint,
        payload: ChatCompletionRequest,
    ): Flow<ChatEvent> = channelFlow {
        val request = buildRequest(endpoint, payload)
        val call = httpClient.newCall(request)

        val scope = this
        val job = launch(ioDispatcher) {
            runCatching { call.execute() }
                .onFailure { scope.close(it) }
                .onSuccess { response ->
                    response.use {
                        if (!it.isSuccessful) {
                            scope.close(IllegalStateException("HTTP ${it.code}"))
                            return@launch
                        }
                        val source = it.body?.source() ?: run {
                            scope.close(IllegalStateException("Empty body"))
                            return@launch
                        }
                        parseStream(source, scope)
                    }
                }
        }

        awaitClose {
            call.cancel()
            job.cancel()
        }
    }

    private suspend fun parseStream(source: BufferedSource, collector: FlowCollectorWrapper) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") {
                collector.send(ChatEvent.Completed(totalUsageTokens = null))
                continue
            }
            val delta = runCatching { parseDelta(payload) }.getOrNull()
            if (delta != null) {
                collector.send(delta)
            }
        }
    }

    private fun buildRequest(
        endpoint: StreamingEndpoint,
        payload: ChatCompletionRequest,
    ): Request {
        val body = payload.toJson().toString().toRequestBody(JSON_MEDIA_TYPE)
        val builder = Request.Builder()
            .url(endpoint.url)
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(body)
        if (endpoint.apiKey != null) {
            builder.header(endpoint.authHeader, endpoint.apiKey)
        }
        endpoint.extraHeaders.forEach { (key, value) -> builder.header(key, value) }
        return builder.build()
    }

    private fun parseDelta(raw: String): ChatEvent.StreamDelta? {
        val json = JSONObject(raw)
        val choices = json.optJSONArray("choices") ?: return null
        if (choices.length() == 0) return null
        val choice = choices.getJSONObject(0)
        val deltaObj = choice.optJSONObject("delta") ?: JSONObject()
        val content = when {
            deltaObj.has("content") -> deltaObj.optString("content")
            deltaObj.has("text") -> deltaObj.optString("text")
            else -> null
        }
        val thinking = deltaObj.optString("thinking", null)
        return ChatEvent.StreamDelta(contentDelta = content, thinkingDelta = thinking)
    }

    data class StreamingEndpoint(
        val url: String,
        val apiKey: String?,
        val authHeader: String = "Authorization",
        val extraHeaders: Map<String, String> = emptyMap(),
    )

    data class ChatCompletionRequest(
        val model: String,
        val messages: List<Message>,
        val temperature: Double? = null,
        val reasoningEffort: String? = null,
    ) {
        data class Message(val role: String, val content: String)

        fun toJson(): JSONObject {
            val root = JSONObject()
            root.put("model", model)
            temperature?.let { root.put("temperature", it) }
            reasoningEffort?.let { root.put("reasoning_effort", it) }
            val messagesArray = messages.fold(org.json.JSONArray()) { acc, message ->
                val node = JSONObject()
                node.put("role", message.role)
                node.put("content", message.content)
                acc.put(node)
                acc
            }
            root.put("messages", messagesArray)
            root.put("stream", true)
            return root
        }
    }
}

private typealias FlowCollectorWrapper = kotlinx.coroutines.channels.ProducerScope<ChatEvent>
