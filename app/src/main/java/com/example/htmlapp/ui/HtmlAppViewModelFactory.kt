package com.example.htmlapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.htmlapp.data.AiSettingsRepository
import com.example.htmlapp.data.ChatBackupManager
import com.example.htmlapp.data.ChatRepository
import com.example.htmlapp.data.MessageWindowManager
import com.example.htmlapp.data.model.ModelPreset
import com.example.htmlapp.network.StreamingClient

class HtmlAppViewModelFactory(
    private val chatRepository: ChatRepository,
    private val settingsRepository: AiSettingsRepository,
    private val backupManager: ChatBackupManager,
    private val streamingClient: StreamingClient,
    private val messageWindowManager: MessageWindowManager,
    private val modelPresets: List<ModelPreset>,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HtmlAppViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HtmlAppViewModel(
                chatRepository = chatRepository,
                settingsRepository = settingsRepository,
                backupManager = backupManager,
                streamingClient = streamingClient,
                messageWindowManager = messageWindowManager,
                modelPresets = modelPresets,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}
