package com.example.htmlapp.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

object ChatStoreSerializer : Serializer<ChatStore> {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    override val defaultValue: ChatStore = ChatStore()

    override suspend fun readFrom(input: InputStream): ChatStore {
        return try {
            json.decodeFromString(ChatStore.serializer(), input.readBytes().decodeToString())
        } catch (error: SerializationException) {
            throw CorruptionException("Unable to parse chat store", error)
        }
    }

    override suspend fun writeTo(t: ChatStore, output: OutputStream) {
        output.write(json.encodeToString(ChatStore.serializer(), t).encodeToByteArray())
    }

    fun encodeToString(store: ChatStore): String =
        json.encodeToString(ChatStore.serializer(), store)

    fun decodeFromString(content: String): ChatStore =
        json.decodeFromString(ChatStore.serializer(), content)
}
