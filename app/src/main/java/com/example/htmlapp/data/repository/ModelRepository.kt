package com.example.htmlapp.data.repository

import com.example.htmlapp.data.model.ModelPreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ModelRepository {
    private val presets = listOf(
        ModelPreset("gpt-4o", "GPT-4o", "OpenAI GPT-4o 高精度模型"),
        ModelPreset("gpt-4o-mini", "GPT-4o mini", "OpenAI GPT-4o mini 轻量模型"),
        ModelPreset("deepseek", "DeepSeek", "DeepSeek API 兼容模型"),
    )

    private val selectedModel = MutableStateFlow(presets.first().id)

    fun presets(): List<ModelPreset> = presets

    fun selectedModelId(): StateFlow<String> = selectedModel.asStateFlow()

    fun selectModel(modelId: String) {
        if (presets.any { it.id == modelId }) {
            selectedModel.value = modelId
        }
    }
}
