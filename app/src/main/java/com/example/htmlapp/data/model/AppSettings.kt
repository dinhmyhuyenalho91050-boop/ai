package com.example.htmlapp.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AppSettings(
    val activeModelId: String,
    val apiKey: String = "",
    val baseUrlOverride: String? = null,
    val enableMockResponses: Boolean = false,
)
