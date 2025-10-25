package com.example.htmlapp.network

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

sealed interface StreamingEvent {
    data class Delta(
        val contentDelta: String?,
        val thinkingDelta: String?,
    ) : StreamingEvent

    data class Completed(
        val totalContent: String,
        val totalThinking: String?,
    ) : StreamingEvent

    data class Error(val throwable: Throwable) : StreamingEvent
}

data class ChatMessagePayload(
    val role: String,
    val content: String,
)

data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessagePayload>,
    val apiKey: String,
    val baseUrl: String = "https://api.openai.com/v1",
    val temperature: Double = 0.7,
    val reasoningEffort: String? = null,
)

interface ChatStreamingClient {
    fun streamCompletion(request: ChatCompletionRequest): Flow<StreamingEvent>
}

class OpenAiStreamingClient(
    private val okHttpClient: OkHttpClient,
) : ChatStreamingClient {

    private val json = Json { ignoreUnknownKeys = true }

    override fun streamCompletion(request: ChatCompletionRequest): Flow<StreamingEvent> = callbackFlow {
        val payloadElement = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("stream", true)
            request.reasoningEffort?.let { effort ->
                put("reasoning_effort", effort)
            }
            putJsonArray("messages") {
                request.messages.forEach { message ->
                    add(
                        buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        },
                    )
                }
            }
        }
        val payload = json.encodeToString(JsonElement.serializer(), payloadElement)

        val httpRequest = Request.Builder()
            .url("${request.baseUrl.trimEnd('/')}/chat/completions")
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        val call = okHttpClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
            .newCall(httpRequest)

        val job = launch {
            try {
                val response = call.execute()
                if (!response.isSuccessful) {
                    trySend(StreamingEvent.Error(IOException("HTTP ${response.code}")))
                    response.close()
                    return@launch
                }
                val body = response.body ?: run {
                    trySend(StreamingEvent.Error(IOException("Empty body")))
                    response.close()
                    return@launch
                }
                body.source().use { source ->
                    var accumulatedContent = StringBuilder()
                    var accumulatedThinking = StringBuilder()
                    while (isActive && !source.exhausted()) {
                        val line = source.readUtf8Line() ?: continue
                        if (line.isBlank()) continue
                        if (!line.startsWith("data:")) continue
                        val rawData = line.removePrefix("data:").trim()
                        if (rawData == "[DONE]") {
                            trySend(
                                StreamingEvent.Completed(
                                    totalContent = accumulatedContent.toString(),
                                    totalThinking = accumulatedThinking.toString().takeIf { it.isNotBlank() },
                                ),
                            )
                            break
                        }
                        val event = parseChunk(rawData)
                        event?.let { delta ->
                            delta.contentDelta?.let { accumulatedContent.append(it) }
                            delta.thinkingDelta?.let { accumulatedThinking.append(it) }
                            trySend(delta)
                        }
                    }
                }
                response.close()
            } catch (error: Throwable) {
                if (error is IOException || error is IllegalStateException) {
                    trySend(StreamingEvent.Error(error))
                } else {
                    throw error
                }
            }
            close()
        }

        awaitClose {
            if (!call.isCanceled()) {
                call.cancel()
            }
            job.cancel()
        }
    }

    private fun parseChunk(raw: String): StreamingEvent.Delta? {
        return try {
            val element = json.parseToJsonElement(raw)
            val choices = element.jsonObject["choices"] as? JsonArray ?: return null
            val delta = choices.firstOrNull()?.jsonObject?.get("delta") as? JsonObject ?: return null
            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            val thinking = extractThinking(delta)
            StreamingEvent.Delta(content, thinking)
        } catch (error: Throwable) {
            null
        }
    }

    private fun extractThinking(delta: JsonObject): String? {
        val explicit = delta["thinking"]?.jsonPrimitive?.contentOrNull
        if (!explicit.isNullOrBlank()) return explicit
        val reasoningField = delta["reasoning"]
        if (reasoningField is JsonArray) {
            return reasoningField.joinToString(separator = "") { item ->
                when (item) {
                    is JsonObject -> item["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    is JsonElement -> item.jsonPrimitive.contentOrNull ?: ""
                    else -> ""
                }
            }.ifBlank { null }
        }
        return null
    }

    private val JsonElement.contentOrNull: String?
        get() = try {
            this.jsonPrimitive.content
        } catch (_: Exception) {
            null
        }
}

fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

class EchoStreamingClient : ChatStreamingClient {
    override fun streamCompletion(request: ChatCompletionRequest): Flow<StreamingEvent> = flow {
        val lastMessage = request.messages.lastOrNull()?.content.orEmpty()
        if (lastMessage.isBlank()) {
            emit(StreamingEvent.Completed("", null))
            return@flow
        }
        var accumulated = StringBuilder()
        lastMessage.chunked(24).forEach { chunk ->
            delay(60)
            accumulated.append(chunk)
            emit(StreamingEvent.Delta(contentDelta = chunk, thinkingDelta = null))
        }
        emit(StreamingEvent.Completed(accumulated.toString(), null))
    }
}
