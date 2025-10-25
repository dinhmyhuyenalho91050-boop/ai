package com.example.htmlapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.htmlapp.data.model.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "htmlapp_settings")

class AiSettingsRepository(
    context: Context,
    private val defaultModelId: String,
) {
    private val dataStore = context.applicationContext.settingsDataStore

    private val keyActiveModel = stringPreferencesKey("active_model")
    private val keyApiKey = stringPreferencesKey("api_key")
    private val keyBaseUrl = stringPreferencesKey("base_url")
    private val keyMock = booleanPreferencesKey("mock_responses")

    val settingsFlow: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            activeModelId = prefs[keyActiveModel] ?: defaultModelId,
            apiKey = prefs[keyApiKey] ?: "",
            baseUrlOverride = prefs[keyBaseUrl],
            enableMockResponses = prefs[keyMock] ?: false,
        )
    }

    suspend fun setActiveModel(modelId: String) {
        dataStore.edit { prefs -> prefs[keyActiveModel] = modelId }
    }

    suspend fun updateApiKey(apiKey: String) {
        dataStore.edit { prefs ->
            if (apiKey.isBlank()) {
                prefs.remove(keyApiKey)
            } else {
                prefs[keyApiKey] = apiKey
            }
        }
    }

    suspend fun updateBaseUrl(baseUrl: String?) {
        dataStore.edit { prefs ->
            if (baseUrl.isNullOrBlank()) {
                prefs.remove(keyBaseUrl)
            } else {
                prefs[keyBaseUrl] = baseUrl
            }
        }
    }

    suspend fun setMockResponses(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[keyMock] = enabled }
    }
}
