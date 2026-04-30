package com.example.htmlapp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ChatMessage(
    val id: String = "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}",
    var role: String,
    var content: String,
    var modelName: String = "",
    var thinking: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("id", id)
            .put("role", role)
            .put("content", content)
            .put("modelName", modelName)
            .put("thinking", thinking)
    }

    companion object {
        fun fromJson(json: JSONObject): ChatMessage {
            return ChatMessage(
                id = json.optString("id", "msg_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"),
                role = json.optString("role", "user"),
                content = json.optString("content", ""),
                modelName = json.optString("modelName", ""),
                thinking = json.optString("thinking", "")
            )
        }
    }
}

data class ChatSession(
    val id: String = "s${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(7)}",
    var name: String,
    var modelName: String,
    var promptPresetName: String = "default",
    val history: MutableList<ChatMessage> = mutableListOf()
) {
    fun toJson(): JSONObject {
        val historyJson = JSONArray()
        history.forEach { historyJson.put(it.toJson()) }
        return JSONObject()
            .put("name", name)
            .put("modelName", modelName)
            .put("promptPresetName", promptPresetName)
            .put("history", historyJson)
    }

    companion object {
        fun fromJson(id: String, json: JSONObject): ChatSession {
            val historyJson = json.optJSONArray("history") ?: JSONArray()
            val messages = mutableListOf<ChatMessage>()
            for (i in 0 until historyJson.length()) {
                historyJson.optJSONObject(i)?.let { messages.add(ChatMessage.fromJson(it)) }
            }
            return ChatSession(
                id = id,
                name = json.optString("name", "对话"),
                modelName = json.optString("modelName", ""),
                promptPresetName = json.optString("promptPresetName", "default"),
                history = messages
            )
        }
    }
}

data class ModelConfig(
    var base: String = "https://api.openai.com/v1",
    var key: String = "",
    var model: String = "gpt-4o-mini",
    var temperature: Double = 1.0,
    var topP: Double = 0.95,
    var topK: Int = 0,
    var maxTokens: Int = 65536,
    var maxOutputTokens: Int = 65536,
    var stream: Boolean = false,
    var useThinking: Boolean = false,
    var thinkingEffort: String = "high",
    var thinkingLevel: String = "high",
    var thinkingBudget: Int = -1,
    var proxyUrl: String = "",
    var proxyPass: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("base", base)
            .put("key", key)
            .put("model", model)
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("top_k", topK)
            .put("max_tokens", maxTokens)
            .put("max_output_tokens", maxOutputTokens)
            .put("stream", stream)
            .put("useThinking", useThinking)
            .put("thinkingEffort", thinkingEffort)
            .put("thinkingLevel", thinkingLevel)
            .put("thinkingBudget", thinkingBudget)
            .put("proxyUrl", proxyUrl)
            .put("proxyPass", proxyPass)
    }

    companion object {
        fun fromJson(json: JSONObject): ModelConfig {
            return ModelConfig(
                base = json.optString("base", "https://api.openai.com/v1"),
                key = json.optString("key", ""),
                model = json.optString("model", "gpt-4o-mini"),
                temperature = json.optDouble("temperature", 1.0),
                topP = json.optDouble("top_p", 0.95),
                topK = json.optInt("top_k", 0),
                maxTokens = json.optInt("max_tokens", 65536),
                maxOutputTokens = json.optInt("max_output_tokens", json.optInt("max_tokens", 65536)),
                stream = json.optBoolean("stream", false),
                useThinking = json.optBoolean("useThinking", false),
                thinkingEffort = json.optString("thinkingEffort", "high"),
                thinkingLevel = json.optString("thinkingLevel", "high"),
                thinkingBudget = json.optInt("thinkingBudget", -1),
                proxyUrl = json.optString("proxyUrl", ""),
                proxyPass = json.optString("proxyPass", "")
            )
        }
    }
}

data class ModelPreset(
    var enabled: Boolean = true,
    var name: String,
    var type: String,
    var config: ModelConfig
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("enabled", enabled)
            .put("name", name)
            .put("type", type)
            .put("config", config.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject): ModelPreset {
            return ModelPreset(
                enabled = json.optBoolean("enabled", true),
                name = json.optString("name", "GPT-4"),
                type = json.optString("type", "openai"),
                config = ModelConfig.fromJson(json.optJSONObject("config") ?: JSONObject())
            )
        }
    }
}

data class PromptPreset(
    var name: String = "默认预设",
    var sysPrompt: String = "",
    var firstUser: String = "",
    var firstAssistant: String = "",
    var messagePrefix: String = "",
    var assistantPrefill: String = "",
    var regexRulesJson: String = "[]",
    var multiStepRunnerJson: String = "{\"enabled\":false,\"steps\":[]}"
) {
    fun toJson(): JSONObject {
        return JSONObject()
            .put("name", name)
            .put("sysPrompt", sysPrompt)
            .put("firstUser", firstUser)
            .put("firstAssistant", firstAssistant)
            .put("messagePrefix", messagePrefix)
            .put("assistantPrefill", assistantPrefill)
            .put("regexRules", safeArray(regexRulesJson))
            .put("multiStepRunner", safeObject(multiStepRunnerJson))
    }

    companion object {
        fun fromJson(json: JSONObject): PromptPreset {
            return PromptPreset(
                name = json.optString("name", "默认预设"),
                sysPrompt = json.optString("sysPrompt", ""),
                firstUser = json.optString("firstUser", ""),
                firstAssistant = json.optString("firstAssistant", ""),
                messagePrefix = json.optString("messagePrefix", ""),
                assistantPrefill = json.optString("assistantPrefill", ""),
                regexRulesJson = (json.optJSONArray("regexRules") ?: JSONArray()).toString(),
                multiStepRunnerJson = (json.optJSONObject("multiStepRunner")
                    ?: JSONObject().put("enabled", false).put("steps", JSONArray())).toString()
            )
        }

        private fun safeArray(text: String): JSONArray {
            return try {
                JSONArray(text)
            } catch (_: Exception) {
                JSONArray()
            }
        }

        private fun safeObject(text: String): JSONObject {
            return try {
                JSONObject(text)
            } catch (_: Exception) {
                JSONObject().put("enabled", false).put("steps", JSONArray())
            }
        }
    }
}

data class NativeChatState(
    val modelPresets: MutableList<ModelPreset> = mutableListOf(),
    val promptPresets: MutableMap<String, PromptPreset> = linkedMapOf(),
    val sessions: MutableMap<String, ChatSession> = linkedMapOf(),
    var currentId: String? = null,
    var currentModelIndex: Int = 0
) {
    fun enabledPresets(): List<ModelPreset> = modelPresets.filter { it.enabled }

    fun currentPreset(): ModelPreset? {
        val enabled = enabledPresets()
        if (enabled.isEmpty()) return null
        currentModelIndex = currentModelIndex.coerceIn(0, enabled.lastIndex)
        return enabled[currentModelIndex]
    }

    fun activeSession(): ChatSession? = currentId?.let { sessions[it] }

    fun promptFor(session: ChatSession?): PromptPreset {
        val key = session?.promptPresetName ?: "default"
        return promptPresets[key] ?: promptPresets["default"] ?: PromptPreset()
    }

    fun ensureSession(): ChatSession {
        activeSession()?.let { return it }
        val preset = currentPreset()
        val prompt = promptPresets["default"] ?: PromptPreset()
        val session = ChatSession(
            name = "对话 ${sessions.size + 1}",
            modelName = preset?.name ?: "未知",
            promptPresetName = "default"
        )
        if (prompt.firstAssistant.isNotBlank()) {
            session.history.add(
                ChatMessage(
                    role = "assistant",
                    content = prompt.firstAssistant,
                    modelName = preset?.name.orEmpty()
                )
            )
        }
        sessions[session.id] = session
        currentId = session.id
        return session
    }

    fun toBackupJson(includeSessions: Boolean = true, includePresets: Boolean = true): JSONObject {
        val root = JSONObject().put("version", "9.3").put("timestamp", System.currentTimeMillis())
        if (includePresets) {
            val models = JSONArray()
            modelPresets.forEach { models.put(it.toJson()) }
            val prompts = JSONObject()
            promptPresets.forEach { (key, preset) -> prompts.put(key, preset.toJson()) }
            root.put("modelPresets", models)
            root.put("promptPresets", prompts)
        }
        if (includeSessions) {
            val sessionsJson = JSONObject()
            sessions.forEach { (id, session) -> sessionsJson.put(id, session.toJson()) }
            root.put("sessions", sessionsJson)
            root.put("currentId", currentId)
        }
        return root
    }

    companion object {
        fun defaults(): NativeChatState {
            return NativeChatState(
                modelPresets = mutableListOf(
                    ModelPreset(
                        enabled = true,
                        name = "GPT-4",
                        type = "openai",
                        config = ModelConfig(
                            base = "https://api.openai.com/v1",
                            model = "gpt-4o-mini",
                            temperature = 1.0,
                            topP = 0.95,
                            maxTokens = 65536,
                            stream = false
                        )
                    ),
                    ModelPreset(
                        enabled = true,
                        name = "DeepSeek",
                        type = "deepseek",
                        config = ModelConfig(
                            base = "https://api.deepseek.com",
                            model = "deepseek-v4-flash",
                            temperature = 1.0,
                            topP = 0.95,
                            maxTokens = 65536,
                            stream = false,
                            useThinking = true,
                            thinkingEffort = "high"
                        )
                    )
                ),
                promptPresets = linkedMapOf("default" to PromptPreset())
            )
        }
    }
}

class NativeChatRepository(private val context: Context) {
    private val dataDir = File(context.filesDir, "native-chat")
    private val stateFile = File(dataDir, "state.json")
    private val sessionsFile = File(dataDir, "sessions.json")

    fun load(): NativeChatState {
        if (!dataDir.exists()) dataDir.mkdirs()
        val state = NativeChatState.defaults()
        runCatching {
            if (stateFile.exists()) {
                val json = JSONObject(stateFile.readText(Charsets.UTF_8))
                state.modelPresets.clear()
                json.optJSONArray("modelPresets")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { state.modelPresets.add(ModelPreset.fromJson(it)) }
                    }
                }
                if (state.modelPresets.isEmpty()) {
                    state.modelPresets.addAll(NativeChatState.defaults().modelPresets)
                }
                state.promptPresets.clear()
                json.optJSONObject("promptPresets")?.let { prompts ->
                    prompts.keys().forEach { key ->
                        prompts.optJSONObject(key)?.let { state.promptPresets[key] = PromptPreset.fromJson(it) }
                    }
                }
                if (state.promptPresets.isEmpty()) {
                    state.promptPresets["default"] = PromptPreset()
                }
                state.currentId = json.optString("currentId").ifBlank { null }
                state.currentModelIndex = json.optInt("currentModelIndex", 0)
            }
            if (sessionsFile.exists()) {
                val sessions = JSONObject(sessionsFile.readText(Charsets.UTF_8))
                state.sessions.clear()
                sessions.keys().forEach { id ->
                    sessions.optJSONObject(id)?.let { state.sessions[id] = ChatSession.fromJson(id, it) }
                }
            }
        }
        state.ensureSession()
        save(state)
        return state
    }

    @Synchronized
    fun save(state: NativeChatState) {
        if (!dataDir.exists()) dataDir.mkdirs()
        val stateJson = JSONObject()
        val models = JSONArray()
        state.modelPresets.forEach { models.put(it.toJson()) }
        val prompts = JSONObject()
        state.promptPresets.forEach { (key, preset) -> prompts.put(key, preset.toJson()) }
        stateJson
            .put("version", "9.3")
            .put("modelPresets", models)
            .put("promptPresets", prompts)
            .put("currentId", state.currentId)
            .put("currentModelIndex", state.currentModelIndex)

        val sessionsJson = JSONObject()
        state.sessions.forEach { (id, session) -> sessionsJson.put(id, session.toJson()) }
        writeAtomic(stateFile, stateJson.toString(2))
        writeAtomic(sessionsFile, sessionsJson.toString(2))
    }

    fun importBackup(state: NativeChatState, data: JSONObject, replace: Boolean) {
        if (replace) {
            data.optJSONArray("modelPresets")?.let { arr ->
                state.modelPresets.clear()
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { state.modelPresets.add(ModelPreset.fromJson(it)) }
            }
            data.optJSONObject("promptPresets")?.let { prompts ->
                state.promptPresets.clear()
                prompts.keys().forEach { key ->
                    prompts.optJSONObject(key)?.let { state.promptPresets[key] = PromptPreset.fromJson(it) }
                }
            }
            data.optJSONObject("sessions")?.let { sessions ->
                state.sessions.clear()
                sessions.keys().forEach { id ->
                    sessions.optJSONObject(id)?.let { state.sessions[id] = ChatSession.fromJson(id, it) }
                }
            }
            state.currentId = data.optString("currentId").ifBlank { null }
        } else {
            data.optJSONArray("modelPresets")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val preset = arr.optJSONObject(i)?.let { ModelPreset.fromJson(it) } ?: continue
                    if (state.modelPresets.none { it.name == preset.name }) state.modelPresets.add(preset)
                }
            }
            data.optJSONObject("promptPresets")?.let { prompts ->
                prompts.keys().forEach { key ->
                    val preset = prompts.optJSONObject(key)?.let { PromptPreset.fromJson(it) } ?: return@forEach
                    val targetKey = if (!state.promptPresets.containsKey(key)) key else "${key}_imported_${System.currentTimeMillis()}"
                    state.promptPresets[targetKey] = preset
                }
            }
            data.optJSONObject("sessions")?.let { sessions ->
                sessions.keys().forEach { id ->
                    val session = sessions.optJSONObject(id)?.let { ChatSession.fromJson(id, it) } ?: return@forEach
                    val targetId = if (!state.sessions.containsKey(id)) id else "s${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(7)}"
                    state.sessions[targetId] = session.copy(id = targetId)
                }
            }
        }
        if (state.modelPresets.isEmpty()) state.modelPresets.addAll(NativeChatState.defaults().modelPresets)
        if (state.promptPresets.isEmpty()) state.promptPresets["default"] = PromptPreset()
        state.ensureSession()
        save(state)
    }

    fun exportBackup(filename: String, jsonText: String): String {
        val bytes = jsonText.toByteArray(Charsets.UTF_8)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(filename, bytes)
        } else {
            saveToAppDownloads(filename, bytes)
        }
    }

    private fun saveToMediaStore(filename: String, bytes: ByteArray): String {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, filename)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AIChat")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("无法创建下载文件")
        try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法写入下载文件")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "内部存储/Download/AIChat/$filename"
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveToAppDownloads(filename: String, bytes: ByteArray): String {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: throw IOException("外部下载目录不可用")
        val dir = File(base, "AIChat")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, filename)
        FileOutputStream(file).use { it.write(bytes) }
        return file.absolutePath
    }

    private fun writeAtomic(file: File, text: String) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (file.exists()) file.delete()
        if (!tmp.renameTo(file)) {
            file.writeText(text, Charsets.UTF_8)
            tmp.delete()
        }
    }
}

data class NativeAiResult(val content: String, val thinking: String = "")

class NativeAiClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(0, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var activeCall: Call? = null

    fun abort() {
        activeCall?.cancel()
        activeCall = null
    }

    fun send(
        state: NativeChatState,
        session: ChatSession,
        continuationPrefill: String? = null,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        val preset = state.currentPreset() ?: throw IllegalStateException("无可用模型预设")
        val prompt = state.promptFor(session)
        val runner = runCatching { JSONObject(prompt.multiStepRunnerJson) }.getOrNull()
        if (continuationPrefill == null &&
            runner?.optBoolean("enabled") == true &&
            (runner.optJSONArray("steps")?.length() ?: 0) > 0
        ) {
            return runMultiStep(state, session, prompt, preset, runner, onDelta)
        }
        return sendWithMessages(buildMessages(session, prompt, preset, continuationPrefill), preset, onDelta)
    }

    private fun sendWithMessages(
        messages: List<JSONObject>,
        preset: ModelPreset,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        return when (preset.type.lowercase(Locale.ROOT)) {
            "anthropic" -> sendAnthropic(messages, preset, onDelta)
            "gemini", "gemini-proxy" -> sendGemini(messages, preset, onDelta)
            else -> sendOpenAICompatible(messages, preset, onDelta)
        }
    }

    private fun buildMessages(
        session: ChatSession,
        prompt: PromptPreset,
        preset: ModelPreset,
        continuationPrefill: String? = null
    ): List<JSONObject> {
        val messages = mutableListOf<JSONObject>()
        if (prompt.sysPrompt.isNotBlank()) {
            messages.add(JSONObject().put("role", "system").put("content", prompt.sysPrompt))
        }
        if (prompt.firstUser.isNotBlank()) {
            messages.add(JSONObject().put("role", "user").put("content", prompt.firstUser))
        }
        session.history.forEachIndexed { index, message ->
            if (message.role == "user" || message.role == "assistant") {
                if (message.role == "assistant" && message.content.isBlank() && index == session.history.lastIndex) {
                    return@forEachIndexed
                }
                if (continuationPrefill != null && message.role == "assistant" && index == session.history.lastIndex) {
                    return@forEachIndexed
                }
                val lastUser = message.role == "user" && index == session.history.lastIndex
                val content = if (lastUser && prompt.messagePrefix.isNotBlank()) {
                    prompt.messagePrefix + message.content
                } else {
                    message.content
                }
                messages.add(JSONObject().put("role", message.role).put("content", content))
            }
        }
        val prefillContent = continuationPrefill ?: prompt.assistantPrefill
        if (prefillContent.isNotBlank()) {
            val prefill = JSONObject().put("role", "assistant").put("content", prefillContent)
            val type = preset.type.lowercase(Locale.ROOT)
            if (type == "deepseek") prefill.put("prefix", true)
            if (type == "kimi") prefill.put("partial", true)
            messages.add(prefill)
        }
        return messages
    }

    private fun runMultiStep(
        state: NativeChatState,
        session: ChatSession,
        prompt: PromptPreset,
        defaultPreset: ModelPreset,
        runner: JSONObject,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        val steps = runner.optJSONArray("steps") ?: throw IllegalStateException("多步编排器未配置 steps")
        val finalIndex = resolveFinalStepIndex(steps)
        val baseTurns = buildRunnerTurns(session, prompt)
        val baseUser = latestUserContent(session)
        if (baseUser.isBlank()) throw IllegalStateException("多步编排器需要最新 user 消息")
        val replies = mutableListOf<String>()
        var lastResult = NativeAiResult("")

        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: JSONObject()
            val isFinal = i == finalIndex
            val stepPreset = resolveStepPreset(state, step, defaultPreset)
            var system = prompt.sysPrompt
            if (step.has("system")) system = applyContentEdit(system, step.opt("system"), replies)
            val selectedTurns = selectRunnerTurns(baseTurns, step.optJSONObject("history"))

            step.optJSONObject("patches")?.let { patches ->
                applyTurnEdits(selectedTurns, patches.optJSONArray("turnUsers"), replies, targetAssistant = false)
                applyTurnEdits(selectedTurns, patches.optJSONArray("turnAssistants"), replies, targetAssistant = true)
                if (patches.has("system")) system = applyContentEdit(system, patches.opt("system"), replies)
            }

            var userContent = baseUser
            if (step.has("user")) userContent = applyContentEdit(userContent, step.opt("user"), replies)
            step.optJSONObject("patches")?.let { patches ->
                if (patches.has("latestUser")) userContent = applyContentEdit(userContent, patches.opt("latestUser"), replies)
            }

            val messages = mutableListOf<JSONObject>()
            if (system.isNotBlank()) messages.add(JSONObject().put("role", "system").put("content", system))
            selectedTurns.forEach { turn ->
                messages.add(JSONObject().put("role", "user").put("content", turn.user))
                messages.add(JSONObject().put("role", "assistant").put("content", turn.assistant))
            }
            messages.add(JSONObject().put("role", "user").put("content", userContent))

            val result = sendWithMessages(messages, stepPreset, if (isFinal) onDelta else { _, _ -> })
            val processed = applyRegexRules(result.content, step.optJSONArray("replyRegexRules"))
            replies.add(processed)
            if (isFinal) lastResult = result.copy(content = processed)
        }
        return lastResult
    }

    private data class RunnerTurn(val index: Int, var user: String, var assistant: String)

    private fun resolveFinalStepIndex(steps: JSONArray): Int {
        var finalIndex = -1
        for (i in 0 until steps.length()) {
            if (steps.optJSONObject(i)?.optBoolean("isFinal") == true) {
                if (finalIndex >= 0) throw IllegalStateException("多步编排只能有一个最终步骤")
                finalIndex = i
            }
        }
        if (finalIndex >= 0 && finalIndex != steps.length() - 1) {
            throw IllegalStateException("最终步骤必须是最后一步")
        }
        return if (finalIndex >= 0) finalIndex else steps.length() - 1
    }

    private fun buildRunnerTurns(session: ChatSession, prompt: PromptPreset): List<RunnerTurn> {
        val turns = mutableListOf<RunnerTurn>()
        var startIndex = 0
        if (prompt.firstUser.isNotBlank() && session.history.firstOrNull()?.role == "assistant") {
            turns.add(RunnerTurn(1, prompt.firstUser, session.history.first().content))
            startIndex = 1
        }
        var pendingUser: ChatMessage? = null
        for (i in startIndex until session.history.size) {
            val message = session.history[i]
            if (message.role == "user") {
                pendingUser = message
            } else if (message.role == "assistant" && pendingUser != null) {
                turns.add(RunnerTurn(turns.size + 1, pendingUser?.content.orEmpty(), message.content))
                pendingUser = null
            }
        }
        return turns
    }

    private fun latestUserContent(session: ChatSession): String {
        for (i in session.history.size - 1 downTo 0) {
            val message = session.history[i]
            if (message.role == "user") return message.content
        }
        return ""
    }

    private fun selectRunnerTurns(turns: List<RunnerTurn>, history: JSONObject?): MutableList<RunnerTurn> {
        if (turns.isEmpty()) return mutableListOf()
        val rawMode = history?.optString("mode", "all").orEmpty().lowercase(Locale.ROOT)
        val mode = when {
            rawMode.contains("none") || rawMode.contains("empty") -> "none"
            rawMode.contains("range") -> "range"
            rawMode.contains("last") || rawMode.contains("latest") || rawMode.contains("recent") -> "latest"
            else -> "all"
        }
        if (mode == "none") return mutableListOf()
        var start = 1
        var end = turns.size
        if (mode == "range") {
            start = history?.optInt("start", 1) ?: 1
            end = history?.optInt("end", turns.size) ?: turns.size
        } else if (mode == "latest") {
            val latest = history?.optInt("latest", 1) ?: 1
            val count = maxOf(1, history?.optInt("count", latest) ?: latest)
            start = maxOf(1, turns.size - count + 1)
            end = turns.size
        }
        if (start < 1 || end < start || end > turns.size) {
            throw IllegalStateException("历史回合范围无效: $start..$end (总回合 ${turns.size})")
        }
        return turns.subList(start - 1, end).map { it.copy() }.toMutableList()
    }

    private fun applyTurnEdits(
        turns: MutableList<RunnerTurn>,
        edits: JSONArray?,
        replies: List<String>,
        targetAssistant: Boolean
    ) {
        if (edits == null) return
        for (i in 0 until edits.length()) {
            val edit = edits.optJSONObject(i) ?: continue
            val turnIndex = edit.optInt("turn", Int.MIN_VALUE)
            val turn = turns.firstOrNull { it.index == turnIndex }
                ?: throw IllegalStateException("回合改写目标不存在: turn $turnIndex")
            if (targetAssistant) {
                turn.assistant = applyContentEdit(turn.assistant, edit, replies)
            } else {
                turn.user = applyContentEdit(turn.user, edit, replies)
            }
        }
    }

    private fun applyContentEdit(base: String, editValue: Any?, replies: List<String>): String {
        if (editValue == null || editValue == JSONObject.NULL) return base
        if (editValue is String) return interpolateReplies(editValue, replies)
        val edit = editValue as? JSONObject ?: return base
        val mode = edit.optString("mode", "replace").lowercase(Locale.ROOT)
        val rawContent = if (edit.has("content")) edit.optString("content") else edit.optString("text")
        val content = interpolateReplies(rawContent, replies)
        return when (mode) {
            "keep", "none", "unchanged" -> base
            "append" -> base + content
            "prepend" -> content + base
            else -> content
        }
    }

    private fun interpolateReplies(text: String, replies: List<String>): String {
        return Regex("\\{\\{\\s*reply(\\d+)\\s*\\}\\}").replace(text) { match ->
            val index = match.groupValues[1].toIntOrNull()?.minus(1)
            if (index == null || index !in replies.indices) {
                throw IllegalStateException("引用了不存在的回复变量 ${match.value}")
            }
            replies[index]
        }
    }

    private fun resolveStepPreset(state: NativeChatState, step: JSONObject, defaultPreset: ModelPreset): ModelPreset {
        val presetName = when {
            step.has("preset") -> step.optString("preset")
            step.has("presetName") -> step.optString("presetName")
            else -> ""
        }.trim()
        if (presetName.isNotBlank()) {
            return state.modelPresets.firstOrNull { it.name.trim() == presetName }
                ?: throw IllegalStateException("Step 模型预设不存在: $presetName")
        }
        if (step.has("presetIndex")) {
            val rawIndex = step.optInt("presetIndex")
            val index = if (rawIndex >= 0) rawIndex else state.modelPresets.size + rawIndex
            return state.modelPresets.getOrNull(index)
                ?: throw IllegalStateException("Step 模型预设索引无效: $rawIndex")
        }
        return defaultPreset
    }

    private fun applyRegexRules(content: String, rules: JSONArray?): String {
        if (content.isBlank() || rules == null || rules.length() == 0) return content
        var result = content
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val pattern = rule.optString("pattern")
            if (pattern.isBlank()) continue
            val replacement = rule.optString("replacement")
            val compiled = compileRegexRule(pattern, rule.optString("flags"))
            result = compiled?.let {
                runCatching {
                    if (it.second) it.first.replace(result, replacement) else it.first.replaceFirst(result, replacement)
                }.getOrDefault(result)
            } ?: result
        }
        return result
    }

    private fun compileRegexRule(rawPattern: String, rawFlags: String): Pair<Regex, Boolean>? {
        var pattern = rawPattern
        var flags = rawFlags
        val literal = Regex("^/([\\s\\S]*)/([a-zA-Z]*)$").matchEntire(pattern)
        if (literal != null) {
            pattern = literal.groupValues[1]
            flags = literal.groupValues[2]
        }
        val options = mutableSetOf<RegexOption>()
        if ('i' in flags) options.add(RegexOption.IGNORE_CASE)
        if ('m' in flags) options.add(RegexOption.MULTILINE)
        if ('s' in flags) options.add(RegexOption.DOT_MATCHES_ALL)
        return runCatching { Regex(pattern, options) to ('g' in flags) }.getOrNull()
    }

    private fun sendOpenAICompatible(
        messages: List<JSONObject>,
        preset: ModelPreset,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        val cfg = preset.config
        val isDeepSeek = preset.type == "deepseek" || cfg.base.contains("api.deepseek.com")
        val isKimi = preset.type == "kimi" || Regex("api\\.moonshot\\.(?:cn|ai)").containsMatchIn(cfg.base)
        val baseDefault = when {
            isDeepSeek -> "https://api.deepseek.com"
            isKimi -> "https://api.moonshot.cn/v1"
            else -> "https://api.openai.com/v1"
        }
        val modelDefault = when {
            isDeepSeek -> "deepseek-v4-flash"
            isKimi -> "kimi-k2.5"
            else -> "gpt-4o-mini"
        }
        val model = cfg.model.ifBlank { modelDefault }
        if (isXaiCompatible(cfg.base.ifBlank { baseDefault }, model) && isXaiMultiAgentModel(model)) {
            throw IllegalArgumentException("Grok multi-agent requires xAI Responses API; Chat Completions is not supported.")
        }

        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray(messages))
            .put("stream", cfg.stream)

        val deepSeekThinkingActive = isDeepSeek && (cfg.useThinking || !model.equals("deepseek-chat", ignoreCase = true))
        val kimiFixedSampling = isKimi && Regex("^kimi-k2(?:[.\\-]|$)", RegexOption.IGNORE_CASE).containsMatchIn(model)
        if (cfg.temperature != 0.0 && !deepSeekThinkingActive && !kimiFixedSampling) body.put("temperature", cfg.temperature)
        if (cfg.topP != 0.0 && !deepSeekThinkingActive && !kimiFixedSampling) body.put("top_p", cfg.topP)
        if (cfg.maxTokens != 0) body.put("max_tokens", cfg.maxTokens)

        if (isDeepSeek) {
            body.put("thinking", JSONObject().put("type", if (cfg.useThinking) "enabled" else "disabled"))
            if (deepSeekThinkingActive && !isOmitEffort(cfg.thinkingEffort)) {
                body.put("reasoning_effort", normalizeDeepSeekEffort(cfg.thinkingEffort))
            }
        } else if (isKimi) {
            if (cfg.useThinking) body.put("thinking", JSONObject().put("type", "enabled"))
            if (!cfg.useThinking && !Regex("thinking", RegexOption.IGNORE_CASE).containsMatchIn(model)) {
                body.put("thinking", JSONObject().put("type", "disabled"))
            }
        } else if (cfg.useThinking && !isXaiCompatible(cfg.base.ifBlank { baseDefault }, model)) {
            val effort = normalizeOpenAIEffort(cfg.thinkingEffort)
            if (effort.isNotBlank()) body.put("reasoning_effort", effort)
        }

        val request = Request.Builder()
            .url(cfg.base.ifBlank { baseDefault }.trimEnd('/') + "/chat/completions")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer ${cfg.key}")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJsonOrSse(request, cfg.stream, onDelta, ::parseOpenAINonStream, ::parseOpenAIStreamLine)
    }

    private fun sendAnthropic(
        messages: List<JSONObject>,
        preset: ModelPreset,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        val cfg = preset.config
        val extracted = extractSystem(messages)
        val model = cfg.model.ifBlank { "claude-3-5-sonnet-20240620" }
        val converted = JSONArray()
        extracted.second.forEach { message ->
            converted.put(
                JSONObject()
                    .put("role", message.optString("role"))
                    .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message.optString("content"))))
            )
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", converted)
            .put("stream", cfg.stream)
            .put("max_tokens", if (cfg.maxTokens > 0) cfg.maxTokens else 4096)
        if (extracted.first.isNotBlank()) body.put("system", extracted.first)
        val thinkingEffort = normalizeAnthropicEffort(cfg.thinkingEffort)
        if (cfg.useThinking) {
            if (useAnthropicAdaptiveThinking(model)) {
                body.put("thinking", JSONObject().put("type", "adaptive").put("display", "summarized"))
                if (thinkingEffort.isNotBlank()) body.put("output_config", JSONObject().put("effort", thinkingEffort))
            } else {
                val budget = anthropicThinkingBudget(cfg, thinkingEffort, body.optInt("max_tokens", 4096))
                body.put(
                    "thinking",
                    JSONObject()
                        .put("type", "enabled")
                        .put("budget_tokens", budget)
                        .put("display", "summarized")
                )
                if (supportsAnthropicOutputEffort(model) && thinkingEffort.isNotBlank()) {
                    body.put("output_config", JSONObject().put("effort", thinkingEffort))
                }
            }
        } else if (cfg.temperature != 0.0) {
            body.put("temperature", cfg.temperature)
        }
        if (cfg.topP != 0.0 && (!cfg.useThinking || cfg.topP >= 0.95)) body.put("top_p", cfg.topP)

        val request = Request.Builder()
            .url(cfg.base.ifBlank { "https://api.anthropic.com/v1" }.trimEnd('/') + "/messages")
            .header("Content-Type", "application/json")
            .header("x-api-key", cfg.key)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(jsonMediaType))
            .build()
        return executeJsonOrSse(request, cfg.stream, onDelta, ::parseAnthropicNonStream, ::parseAnthropicStreamLine)
    }

    private fun sendGemini(
        messages: List<JSONObject>,
        preset: ModelPreset,
        onDelta: (content: String, thinking: String) -> Unit
    ): NativeAiResult {
        val cfg = preset.config
        val isProxy = preset.type == "gemini-proxy"
        val extracted = extractSystem(messages)
        val model = cfg.model.ifBlank { "gemini-2.5-pro" }
        val action = if (cfg.stream) "streamGenerateContent" else "generateContent"
        val base = if (isProxy) cfg.proxyUrl.ifBlank { "http://127.0.0.1:8889" } else cfg.base.ifBlank { "https://generativelanguage.googleapis.com" }
        val query = mutableListOf<String>()
        if (cfg.stream) query.add("alt=sse")
        if (!isProxy && cfg.key.isNotBlank()) query.add("key=${java.net.URLEncoder.encode(cfg.key, "UTF-8")}")
        val url = base.trimEnd('/') + "/v1beta/models/" + java.net.URLEncoder.encode(model, "UTF-8") + ":$action" +
            if (query.isEmpty()) "" else query.joinToString(prefix = "?", separator = "&")

        val contents = JSONArray()
        extracted.second.forEach { message ->
            contents.put(
                JSONObject()
                    .put("role", if (message.optString("role") == "assistant") "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", message.optString("content"))))
            )
        }
        val generationConfig = JSONObject()
        if (cfg.temperature != 0.0) generationConfig.put("temperature", cfg.temperature)
        if (cfg.topP != 0.0) generationConfig.put("topP", cfg.topP)
        if (cfg.topK != 0) generationConfig.put("topK", cfg.topK)
        if (cfg.maxOutputTokens != 0) generationConfig.put("maxOutputTokens", cfg.maxOutputTokens)
        if (cfg.useThinking) {
            val thinkingConfig = JSONObject().put("includeThoughts", true)
            if (isGemini3Model(model)) {
                normalizeGeminiThinkingLevel(cfg.thinkingLevel).takeIf { it.isNotBlank() }?.let {
                    thinkingConfig.put("thinkingLevel", it)
                }
            } else {
                thinkingConfig.put("thinkingBudget", cfg.thinkingBudget)
            }
            generationConfig.put("thinkingConfig", thinkingConfig)
        } else {
            if (isGemini3Model(model)) {
                if (!Regex("pro", RegexOption.IGNORE_CASE).containsMatchIn(model)) {
                    generationConfig.put(
                        "thinkingConfig",
                        JSONObject().put("thinkingLevel", "minimal").put("includeThoughts", false)
                    )
                }
            } else if (!isGemini25ProModel(model)) {
                generationConfig.put(
                    "thinkingConfig",
                    JSONObject().put("thinkingBudget", 0).put("includeThoughts", false)
                )
            }
        }
        val body = JSONObject().put("contents", contents)
        if (extracted.first.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", extracted.first))))
        }
        body.put("generationConfig", generationConfig)
        body.put(
            "safetySettings",
            JSONArray(listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT").map {
                JSONObject().put("category", it).put("threshold", "OFF")
            })
        )

        val builder = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(jsonMediaType))
        if (!isProxy && cfg.key.isNotBlank()) builder.header("X-Goog-Api-Key", cfg.key)
        if (isProxy && cfg.proxyPass.isNotBlank()) builder.header("Authorization", "Bearer ${cfg.proxyPass}")
        return executeJsonOrSse(builder.build(), cfg.stream, onDelta, ::parseGeminiNonStream, ::parseGeminiStreamLine)
    }

    private fun executeJsonOrSse(
        request: Request,
        stream: Boolean,
        onDelta: (content: String, thinking: String) -> Unit,
        parseNonStream: (JSONObject) -> NativeAiResult,
        parseStreamLine: (JSONObject) -> NativeAiResult
    ): NativeAiResult {
        val call = client.newCall(request)
        activeCall = call
        var streamFull: StringBuilder? = null
        var streamThinking: StringBuilder? = null
        var streamEmitter: StreamDeltaEmitter? = null
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty().take(600)
                    throw IOException("HTTP ${response.code}: $errorBody")
                }
                val body = response.body ?: throw IOException("空响应")
                if (!stream) {
                    return parseNonStream(JSONObject(body.string()))
                }

                val full = StringBuilder()
                val thinking = StringBuilder()
                val emitter = StreamDeltaEmitter(onDelta)
                streamFull = full
                streamThinking = thinking
                streamEmitter = emitter
                var pendingEvent = StringBuilder()
                body.charStream().buffered().useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trimEnd()
                        if (line.isBlank()) {
                            if (pendingEvent.isNotEmpty()) {
                                consumeSseJson(pendingEvent.toString(), parseStreamLine, full, thinking, emitter)
                                pendingEvent = StringBuilder()
                            }
                            return@forEach
                        }
                        if (line.startsWith("data:")) {
                            val data = line.removePrefix("data:").trim()
                            if (data != "[DONE]") pendingEvent.append(data)
                        }
                    }
                }
                if (pendingEvent.isNotEmpty()) {
                    consumeSseJson(pendingEvent.toString(), parseStreamLine, full, thinking, emitter)
                }
                emitter.emit(full, thinking)
                return NativeAiResult(full.toString().ifBlank { "(空响应)" }, thinking.toString())
            }
        } finally {
            val full = streamFull
            val thinking = streamThinking
            val emitter = streamEmitter
            if (full != null && thinking != null && emitter != null) {
                emitter.emit(full, thinking)
            }
            activeCall = null
        }
    }

    private fun consumeSseJson(
        data: String,
        parser: (JSONObject) -> NativeAiResult,
        full: StringBuilder,
        thinking: StringBuilder,
        emitter: StreamDeltaEmitter
    ) {
        runCatching {
            val delta = parser(JSONObject(data))
            val contentPart = cleanStreamPart(delta.content)
            val thinkingPart = cleanStreamPart(delta.thinking)
            if (contentPart.isNotBlank() && contentPart != "(空响应)") full.append(contentPart)
            if (thinkingPart.isNotBlank()) thinking.append(thinkingPart)
            if (contentPart.isNotBlank() || thinkingPart.isNotBlank()) {
                emitter.maybeEmit(full, thinking)
            }
        }
    }

    private class StreamDeltaEmitter(
        private val onDelta: (content: String, thinking: String) -> Unit
    ) {
        private var lastContentLength = 0
        private var lastThinkingLength = 0
        private var lastEmitAtNs = 0L
        private val minEmitIntervalNs = TimeUnit.MILLISECONDS.toNanos(24)
        private val maxBufferedChars = 160

        fun maybeEmit(
            content: StringBuilder,
            thinking: StringBuilder
        ) {
            val contentDelta = content.length - lastContentLength
            val thinkingDelta = thinking.length - lastThinkingLength
            if (contentDelta <= 0 && thinkingDelta <= 0) return
            val now = System.nanoTime()
            val enoughTime = lastEmitAtNs == 0L || now - lastEmitAtNs >= minEmitIntervalNs
            val enoughText = contentDelta + thinkingDelta >= maxBufferedChars
            val boundary = hasReadableBoundary(content, lastContentLength) ||
                hasReadableBoundary(thinking, lastThinkingLength)
            if (!enoughTime && !enoughText && !boundary) return
            emit(content, thinking)
        }

        fun emit(
            content: StringBuilder,
            thinking: StringBuilder
        ) {
            if (content.length == lastContentLength && thinking.length == lastThinkingLength) return
            lastContentLength = content.length
            lastThinkingLength = thinking.length
            lastEmitAtNs = System.nanoTime()
            onDelta(content.toString(), thinking.toString())
        }

        private fun hasReadableBoundary(builder: StringBuilder, start: Int): Boolean {
            val from = start.coerceIn(0, builder.length)
            for (index in from until builder.length) {
                val ch = builder[index]
                if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '.' || ch == '!' || ch == '?') {
                    return true
                }
            }
            return false
        }
    }

    private fun cleanStreamPart(value: String): String {
        return if (value.trim().equals("null", ignoreCase = true)) "" else value
    }

    private fun parseOpenAINonStream(json: JSONObject): NativeAiResult {
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: JSONObject()
        val thinking = message.optString("reasoning_content", "")
        val contentValue = message.opt("content")
        return NativeAiResult(extractTextParts(contentValue).ifBlank { "(空响应)" }, thinking)
    }

    private fun parseOpenAIStreamLine(json: JSONObject): NativeAiResult {
        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: JSONObject()
        val thinking = delta.optString("reasoning_content", "")
        return NativeAiResult(extractTextParts(delta.opt("content")), thinking)
    }

    private fun parseAnthropicNonStream(json: JSONObject): NativeAiResult {
        val parts = json.optJSONArray("content") ?: JSONArray()
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            when (part.optString("type")) {
                "text" -> text.append(part.optString("text"))
                "thinking" -> thinking.append(part.optString("thinking", part.optString("text")))
            }
        }
        return NativeAiResult(text.toString().ifBlank { "(空响应)" }, thinking.toString())
    }

    private fun parseAnthropicStreamLine(json: JSONObject): NativeAiResult {
        val block = json.optJSONObject("content_block")
        val delta = json.optJSONObject("delta")
        val text = StringBuilder()
        val thinking = StringBuilder()
        if (block != null) {
            if (block.optString("type") == "text") text.append(block.optString("text"))
            if (block.optString("type") == "thinking") thinking.append(block.optString("thinking", block.optString("text")))
        }
        if (delta != null) {
            if (delta.optString("type") == "text_delta") text.append(delta.optString("text"))
            if (delta.optString("type") == "thinking_delta") thinking.append(delta.optString("thinking"))
        }
        return NativeAiResult(text.toString(), thinking.toString())
    }

    private fun parseGeminiNonStream(json: JSONObject): NativeAiResult {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()
        return parseGeminiParts(parts, fallback = true)
    }

    private fun parseGeminiStreamLine(json: JSONObject): NativeAiResult {
        val parts = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts") ?: JSONArray()
        return parseGeminiParts(parts, fallback = false)
    }

    private fun parseGeminiParts(parts: JSONArray, fallback: Boolean): NativeAiResult {
        val text = StringBuilder()
        val thinking = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.optBoolean("thought")) thinking.append(part.optString("text"))
            else text.append(part.optString("text"))
        }
        return NativeAiResult(if (fallback) text.toString().ifBlank { "(空响应)" } else text.toString(), thinking.toString())
    }

    private fun extractSystem(messages: List<JSONObject>): Pair<String, List<JSONObject>> {
        if (messages.firstOrNull()?.optString("role") == "system") {
            return messages.first().optString("content") to messages.drop(1)
        }
        return "" to messages
    }

    private fun extractTextParts(value: Any?): String {
        return when (value) {
            is String -> value
            is JSONArray -> {
                val text = StringBuilder()
                for (i in 0 until value.length()) {
                    val part = value.optJSONObject(i) ?: continue
                    val type = part.optString("type")
                    if (type == "text" || type == "output_text" || type.isBlank()) {
                        text.append(part.optString("text"))
                    }
                }
                text.toString()
            }
            else -> ""
        }
    }

    private fun normalizeOpenAIEffort(value: String): String {
        val raw = value.trim().lowercase(Locale.ROOT)
        if (isOmitEffort(raw)) return ""
        return when (raw) {
            "none", "minimal", "low", "medium", "high", "xhigh" -> raw
            "max" -> "xhigh"
            else -> "medium"
        }
    }

    private fun normalizeDeepSeekEffort(value: String): String {
        val raw = value.trim().lowercase(Locale.ROOT)
        if (isOmitEffort(raw)) return ""
        return if (raw == "max" || raw == "xhigh") "max" else "high"
    }

    private fun normalizeAnthropicEffort(value: String): String {
        val raw = value.trim().lowercase(Locale.ROOT)
        if (isOmitEffort(raw)) return ""
        return when (raw) {
            "max", "xhigh" -> raw
            "low", "medium", "high" -> raw
            "minimal" -> "low"
            else -> "high"
        }
    }

    private fun anthropicThinkingBudget(cfg: ModelConfig, effort: String, maxTokens: Int): Int {
        if (cfg.thinkingBudget >= 1024) return cfg.thinkingBudget
        val budget = when (effort) {
            "low" -> 1024
            "medium" -> 4096
            "max" -> 16000
            else -> 10000
        }
        val cap = maxTokens - 1
        return if (cap >= 1024) minOf(budget, cap) else budget
    }

    private fun useAnthropicAdaptiveThinking(model: String): Boolean {
        val name = model.lowercase(Locale.ROOT)
        return Regex("claude-mythos").containsMatchIn(name) ||
            Regex("claude-(?:opus|sonnet)-4-6").containsMatchIn(name) ||
            Regex("claude-opus-4-(?:[7-9]|\\d{2,})").containsMatchIn(name)
    }

    private fun supportsAnthropicOutputEffort(model: String): Boolean {
        val name = model.lowercase(Locale.ROOT)
        return Regex("claude-mythos").containsMatchIn(name) ||
            Regex("claude-(?:opus|sonnet)-4-6").containsMatchIn(name) ||
            Regex("claude-opus-4-(?:[5-9]|\\d{2,})").containsMatchIn(name)
    }

    private fun isGemini3Model(model: String): Boolean {
        return Regex("^gemini-3", RegexOption.IGNORE_CASE).containsMatchIn(model)
    }

    private fun isGemini25ProModel(model: String): Boolean {
        val name = model.lowercase(Locale.ROOT)
        return Regex("gemini-2\\.5").containsMatchIn(name) && Regex("pro").containsMatchIn(name)
    }

    private fun normalizeGeminiThinkingLevel(value: String): String {
        val raw = value.trim().lowercase(Locale.ROOT)
        if (isOmitEffort(raw)) return ""
        return when (raw) {
            "minimal", "low", "medium", "high" -> raw
            "max", "xhigh" -> "high"
            else -> "high"
        }
    }

    private fun isOmitEffort(value: String): Boolean {
        return Regex("^(?:__omit__|omit|no_send|nosend|off)$", RegexOption.IGNORE_CASE).matches(value.trim())
    }

    private fun isXaiCompatible(base: String, model: String): Boolean {
        return Regex("(?:^|//)(?:api|mtls)\\.x\\.ai(?:/|$)", RegexOption.IGNORE_CASE).containsMatchIn(base) ||
            Regex("\\bgrok-", RegexOption.IGNORE_CASE).containsMatchIn(model)
    }

    private fun isXaiMultiAgentModel(model: String): Boolean {
        return Regex("grok-4\\.20.*multi-agent", RegexOption.IGNORE_CASE).containsMatchIn(model)
    }
}

fun backupFilename(kind: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
    return "chat-backup-$kind-$stamp.json"
}
