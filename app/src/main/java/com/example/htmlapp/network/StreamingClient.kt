package com.example.htmlapp.network

import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.data.model.ModelProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import okio.IOException

sealed class StreamEvent {
    data class Delta(
        val content: String?,
        val reasoning: String? = null,
    ) : StreamEvent()

    object Complete : StreamEvent()
    data class Error(val message: String) : StreamEvent()
}

@Serializable
data class StreamMessage(
    val role: String,
    val content: String,
)

@Serializable
data class StreamRequest(
    val preset: ModelPreset,
    val messages: List<StreamMessage>,
    val apiKey: String,
    val baseUrlOverride: String? = null,
)

class StreamingClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    fun stream(request: StreamRequest): Flow<StreamEvent> = when (request.preset.provider) {
        ModelProvider.Mock -> mockStream()
        ModelProvider.OpenAI, ModelProvider.DeepSeek -> openAiCompatibleStream(request)
        ModelProvider.Gemini -> mockStream(message = "Gemini streaming 尚未实现，使用模拟结果。")
    }

    private fun mockStream(message: String = "这是一个示例响应，用于演示流式输出。\n原生客户端仍然需要配置真实 API Key 以连接模型服务。") = callbackFlow {
        launch {
            val chunks = message.chunked(12)
            for (chunk in chunks) {
                send(StreamEvent.Delta(content = chunk))
                delay(80)
            }
            send(StreamEvent.Complete)
            close()
        }
        awaitClose { }
    }

    private fun openAiCompatibleStream(request: StreamRequest) = callbackFlow {
        val url = buildUrl(request)
        val body = json.encodeToString(
            OpenAiRequest.serializer(),
            OpenAiRequest(
                model = request.preset.model,
                messages = request.messages,
                stream = true,
                temperature = request.preset.temperature,
                topP = request.preset.topP,
                reasoningEffort = request.preset.reasoningEffort,
            )
        )
        val httpRequest = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${request.apiKey}")
            .header("Accept", "text/event-stream")
            .build()

        val call = client.newCall(httpRequest)
        withContext(ioDispatcher) {
            runCatching { call.execute() }
                .onFailure { error ->
                    trySend(StreamEvent.Error(error.message ?: "网络请求失败"))
                    close(error)
                }
                .onSuccess { response ->
                    if (!response.isSuccessful) {
                        trySend(StreamEvent.Error("HTTP ${response.code}: ${response.message}"))
                        response.close()
                        close()
                        return@onSuccess
                    }
                    val source = response.body?.source()
                    if (source == null) {
                        trySend(StreamEvent.Error("响应体为空"))
                        response.close()
                        close()
                        return@onSuccess
                    }
                    parseServerSentEvents(source)
                    response.close()
                    close()
                }
        }
        awaitClose { call.cancel() }
    }

    private fun buildUrl(request: StreamRequest): String {
        val override = request.baseUrlOverride?.takeIf { it.isNotBlank() }
        val base = override ?: request.preset.baseUrl.takeIf { it.isNotBlank() }
        val normalizedBase = base?.trimEnd('/') ?: when (request.preset.provider) {
            ModelProvider.DeepSeek -> "https://api.deepseek.com"
            else -> "https://api.openai.com/v1"
        }
        val path = when (request.preset.provider) {
            ModelProvider.DeepSeek -> "/chat/completions"
            else -> "/chat/completions"
        }
        return "$normalizedBase$path"
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<StreamEvent>.parseServerSentEvents(source: BufferedSource) {
        while (!source.exhausted()) {
            val line = try {
                source.readUtf8LineStrict()
            } catch (ioe: IOException) {
                trySend(StreamEvent.Error(ioe.message ?: "读取响应失败"))
                return
            }
            if (line.isBlank()) continue
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload == "[DONE]") {
                trySend(StreamEvent.Complete)
                return
            }
            runCatching {
                json.decodeFromString(OpenAiStreamChunk.serializer(), payload)
            }.onSuccess { chunk ->
                val delta = chunk.choices.firstOrNull()?.delta
                if (delta != null) {
                    val content = delta.content?.joinToString(separator = "") { it.text }
                    val reasoning = delta.reasoningContent?.joinToString(separator = "") { it.text }
                    trySend(StreamEvent.Delta(content = content, reasoning = reasoning))
                }
            }.onFailure { error ->
                trySend(StreamEvent.Error(error.message ?: "解析数据失败"))
            }
        }
    }
}

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<StreamMessage>,
    val stream: Boolean,
    val temperature: Double,
    @SerialName("top_p") val topP: Double,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
data class OpenAiStreamChunk(
    val choices: List<Choice>,
) {
    @Serializable
    data class Choice(
        val delta: Delta? = null,
    )

    @Serializable
    data class Delta(
        val role: String? = null,
        val content: List<DeltaContent>? = null,
        @SerialName("reasoning_content") val reasoningContent: List<DeltaContent>? = null,
    )

    @Serializable
    data class DeltaContent(
        @SerialName("type") val type: String? = null,
        @SerialName("text") val text: String = "",
    )
}
