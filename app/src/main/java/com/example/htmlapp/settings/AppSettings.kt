package com.example.htmlapp.settings

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private const val DATA_STORE_NAME = "settings"

data class ProviderSettings(
    val apiKey: String = "",
    val baseUrl: String = "",
)

data class AppSettings(
    val openAi: ProviderSettings = ProviderSettings(baseUrl = "https://api.openai.com/v1"),
    val deepSeek: ProviderSettings = ProviderSettings(baseUrl = "https://api.deepseek.com/v1"),
    val gemini: ProviderSettings = ProviderSettings(baseUrl = "https://generativelanguage.googleapis.com/v1beta"),
    val requestTimeoutSeconds: Int = 60,
    val reasoningEffort: String = "medium",
)

class SettingsRepository(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dataStore = PreferenceDataStoreFactory.create(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + ioDispatcher),
    ) {
        context.applicationContext.preferencesDataStoreFile(DATA_STORE_NAME)
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            openAi = ProviderSettings(
                apiKey = prefs[OPEN_AI_KEY] ?: "",
                baseUrl = prefs[OPEN_AI_BASE_URL] ?: "https://api.openai.com/v1",
            ),
            deepSeek = ProviderSettings(
                apiKey = prefs[DEEPSEEK_KEY] ?: "",
                baseUrl = prefs[DEEPSEEK_BASE_URL] ?: "https://api.deepseek.com/v1",
            ),
            gemini = ProviderSettings(
                apiKey = prefs[GEMINI_KEY] ?: "",
                baseUrl = prefs[GEMINI_BASE_URL] ?: "https://generativelanguage.googleapis.com/v1beta",
            ),
            requestTimeoutSeconds = prefs[REQUEST_TIMEOUT] ?: 60,
            reasoningEffort = prefs[REASONING_EFFORT] ?: "medium",
        )
    }

    suspend fun updateOpenAi(apiKey: String, baseUrl: String) {
        updatePreferences {
            it[OPEN_AI_KEY] = apiKey
            it[OPEN_AI_BASE_URL] = baseUrl
        }
    }

    suspend fun updateDeepSeek(apiKey: String, baseUrl: String) {
        updatePreferences {
            it[DEEPSEEK_KEY] = apiKey
            it[DEEPSEEK_BASE_URL] = baseUrl
        }
    }

    suspend fun updateGemini(apiKey: String, baseUrl: String) {
        updatePreferences {
            it[GEMINI_KEY] = apiKey
            it[GEMINI_BASE_URL] = baseUrl
        }
    }

    suspend fun updateTimeout(seconds: Int) {
        updatePreferences { it[REQUEST_TIMEOUT] = seconds.coerceIn(15, 240) }
    }

    suspend fun updateReasoningEffort(effort: String) {
        updatePreferences { it[REASONING_EFFORT] = effort }
    }

    private suspend fun updatePreferences(block: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        withContext(ioDispatcher) {
            dataStore.edit { prefs ->
                block(prefs)
            }
        }
    }

    companion object {
        private val OPEN_AI_KEY = stringPreferencesKey("openai_api_key")
        private val OPEN_AI_BASE_URL = stringPreferencesKey("openai_base_url")

        private val DEEPSEEK_KEY = stringPreferencesKey("deepseek_api_key")
        private val DEEPSEEK_BASE_URL = stringPreferencesKey("deepseek_base_url")

        private val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        private val GEMINI_BASE_URL = stringPreferencesKey("gemini_base_url")

        private val REQUEST_TIMEOUT = intPreferencesKey("request_timeout_seconds")
        private val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
    }
}

