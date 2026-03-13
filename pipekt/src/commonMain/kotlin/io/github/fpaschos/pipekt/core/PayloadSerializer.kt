package io.github.fpaschos.pipekt.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlin.reflect.KType

/**
 * Engine-level serialization boundary for payloads stored in the work item store.
 *
 * The engine treats payloads as opaque bytes; this interface is swappable for different
 * KMP targets or formats. Default implementation uses kotlinx.serialization; see [KotlinxPayloadSerializer].
 *
 * Serialized strings are stored in the store (e.g. payload_json); [KType] is used to select
 * the correct serializer at runtime.
 */
interface PayloadSerializer {
    /**
     * Serialize [value] of type [type] to a JSON string.
     * @param type The [KType] used to resolve the serializer (e.g. from reified or KClass).
     * @return JSON string; stored as-is in the store.
     */
    fun <T> serialize(
        value: T,
        type: KType,
    ): String

    /**
     * Deserialize [json] to type [T] using [type] to resolve the serializer.
     * @param type The [KType] for the expected type.
     * @return Deserialized instance.
     */
    fun <T> deserialize(
        json: String,
        type: KType,
    ): T
}

/**
 * Default [PayloadSerializer] using kotlinx.serialization with [Json].
 *
 * Configured with [ignoreUnknownKeys = true] so schema evolution does not break deserialization
 * of older stored payloads.
 */
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
