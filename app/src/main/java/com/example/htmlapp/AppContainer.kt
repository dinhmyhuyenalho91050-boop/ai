package com.example.htmlapp

import android.content.Context
import com.example.htmlapp.data.AiSettingsRepository
import com.example.htmlapp.data.ChatBackupManager
import com.example.htmlapp.data.ChatLocalDataSource
import com.example.htmlapp.data.ChatRepository
import com.example.htmlapp.data.MessageWindowManager
import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.data.model.ModelProvider
import com.example.htmlapp.network.StreamingClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

class AppContainer(context: Context) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }

    private val okHttpClient = OkHttpClient.Builder().build()

    val modelPresets: List<ModelPreset> = listOf(
        ModelPreset(
            id = "gpt-4o",
            displayName = "GPT-4o",
            provider = ModelProvider.OpenAI,
            model = "gpt-4o",
            baseUrl = "https://api.openai.com/v1",
            supportsThinking = true,
            reasoningEffort = "medium",
        ),
        ModelPreset(
            id = "gpt-4o-mini",
            displayName = "GPT-4o mini",
            provider = ModelProvider.OpenAI,
            model = "gpt-4o-mini",
        ),
        ModelPreset(
            id = "deepseek-chat",
            displayName = "DeepSeek Chat",
            provider = ModelProvider.DeepSeek,
            model = "deepseek-chat",
            baseUrl = "https://api.deepseek.com",
            supportsThinking = true,
            reasoningEffort = "medium",
        ),
        ModelPreset(
            id = "mock-demo",
            displayName = "离线演示",
            provider = ModelProvider.Mock,
            model = "mock",
        ),
    )

    private val chatLocalDataSource = ChatLocalDataSource(context, json)

    val chatRepository = ChatRepository(chatLocalDataSource)
    val messageWindowManager = MessageWindowManager()
    val settingsRepository = AiSettingsRepository(context, defaultModelId = modelPresets.first().id)
    val backupManager = ChatBackupManager(context, json)
    val streamingClient = StreamingClient(okHttpClient, json)
}
