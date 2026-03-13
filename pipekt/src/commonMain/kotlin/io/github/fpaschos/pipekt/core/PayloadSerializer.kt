package io.github.fpaschos.pipekt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType

interface PayloadSerializer {
    fun <T> serialize(
        value: T,
        type: KType,
    ): String

    fun <T> deserialize(
        json: String,
        type: KType,
    ): T
}

object KotlinxPayloadSerializer : PayloadSerializer {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("UNCHECKED_CAST")
    override fun <T> serialize(
        value: T,
        type: KType,
    ): String = json.encodeToString(serializer(type), value)

    @Suppress("UNCHECKED_CAST")
    override fun <T> deserialize(
        json: String,
        type: KType,
    ): T = this.json.decodeFromString(serializer(type), json) as T
}
