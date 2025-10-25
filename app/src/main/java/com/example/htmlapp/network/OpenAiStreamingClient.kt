package com.example.htmlapp.network

import com.example.htmlapp.data.model.ChatJsonFormat
import com.example.htmlapp.data.model.ModelProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

class OpenAiStreamingClient(
    private val httpClient: OkHttpClient,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ChatStreamingClient {

    override suspend fun stream(
        request: ChatRequest,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    ) {
        when (request.provider) {
            ModelProvider.OPENAI, ModelProvider.DEEPSEEK -> streamOpenAiCompatible(request, onEvent)
            ModelProvider.GEMINI -> streamGemini(request, onEvent)
        }
    }

    private suspend fun streamOpenAiCompatible(
        request: ChatRequest,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    ) = withContext(dispatcher) {
        val body = buildJsonObject {
            put("model", request.modelId)
            put("stream", true)
            request.temperature?.let { put("temperature", it) }
            request.maxOutputTokens?.let { put("max_tokens", it) }
            request.reasoningEffort?.let { effort ->
                put("reasoning", buildJsonObject { put("effort", effort) })
            }
            put("messages", buildJsonArray {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role.name.lowercase())
                            put("content", message.content)
                        }
                    )
                }
            })
        }

        val mediaType = "application/json".toMediaType()
        val httpRequest = Request.Builder()
            .url(request.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", mediaType.toString())
            .post(body.toString().toRequestBody(mediaType))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val source = response.body?.source() ?: return@use
            readSseStream(source, onEvent)
        }
    }

    private suspend fun streamGemini(
        request: ChatRequest,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    ) = withContext(dispatcher) {
        val url = buildString {
            append(request.baseUrl.trimEnd('/'))
            if (!request.baseUrl.endsWith("/")) append('/')
            append("models/")
            append(request.modelId)
            append(":streamGenerateContent?key=")
            append(request.apiKey)
        }

        val body = buildJsonObject {
            put("contents", buildJsonArray {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", when (message.role) {
                                com.example.htmlapp.data.model.MessageRole.USER -> "user"
                                com.example.htmlapp.data.model.MessageRole.ASSISTANT -> "model"
                                com.example.htmlapp.data.model.MessageRole.SYSTEM -> "system"
                            })
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", message.content) })
                            })
                        }
                    )
                }
            })
        }

        val mediaType = "application/json".toMediaType()
        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", mediaType.toString())
            .post(body.toString().toRequestBody(mediaType))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            val source = response.body?.source() ?: return@use
            readSseStream(source, onEvent)
        }
    }

    private suspend fun readSseStream(
        source: BufferedSource,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    ) {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: continue
            if (line.isBlank()) continue
            val payload = if (line.startsWith("data:")) {
                line.removePrefix("data:").trim()
            } else {
                line.trim()
            }
            if (payload == "[DONE]") {
                onEvent(ChatStreamDelta(isComplete = true))
                break
            }
            if (payload.isEmpty()) continue
            val element = ChatJsonFormat.parseToJsonElement(payload)
            dispatchDelta(element, onEvent)
        }
    }

    private suspend fun dispatchDelta(
        element: JsonElement,
        onEvent: suspend (ChatStreamDelta) -> Unit,
    ) {
        val obj = element as? JsonObject ?: return
        obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.let { choice ->
            val delta = choice["delta"]?.jsonObject
            val content = delta?.get("content")?.stringValue()
            val reasoning = delta?.get("reasoning_content")?.jsonArray
                ?.joinToString(separator = "") { part ->
                    part.jsonPrimitive.contentOrNull().orEmpty()
                }
            val usage = obj["usage"]?.jsonObject?.let { usageObj ->
                Usage(
                    promptTokens = usageObj["prompt_tokens"]?.intValue(),
                    completionTokens = usageObj["completion_tokens"]?.intValue(),
                    totalTokens = usageObj["total_tokens"]?.intValue(),
                )
            }
            if (content != null || reasoning != null || usage != null) {
                onEvent(
                    ChatStreamDelta(
                        contentDelta = content,
                        thinkingDelta = reasoning,
                        usage = usage,
                    )
                )
            }
        }
        val finishReason = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("finish_reason")?.stringValue()
        if (finishReason != null) {
            onEvent(ChatStreamDelta(isComplete = true))
        }
    }

    private fun JsonPrimitive.stringContentOrNull(): String? =
        if (isString) content else contentOrNull

    private fun JsonElement.stringValue(): String? =
        (this as? JsonPrimitive)?.stringContentOrNull()

    private fun JsonElement.intValue(): Int? =
        (this as? JsonPrimitive)?.intOrNull
}

