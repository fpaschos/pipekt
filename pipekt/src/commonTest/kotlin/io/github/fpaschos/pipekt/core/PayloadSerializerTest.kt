package io.github.fpaschos.pipekt.core

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

/**
 * Tests for [PayloadSerializer] and its default implementation [KotlinxPayloadSerializer].
 *
 * **Contract and behavior coverage (backed by specs/streams-contracts-v1.md - PayloadSerializer):**
 * - Round trip: serialize then deserialize returns an equal value for a @Serializable data class.
 * - Null-field round trip: nullable properties survive serialize/deserialize as null.
 * - ignoreUnknownKeys: JSON with extra fields does not throw on deserialization (schema evolution safety).
 *
 * These tests validate the engine-level serialization boundary only; step authors never call
 * PayloadSerializer directly.
 */
class PayloadSerializerTest :
    FunSpec({

        @Serializable
        data class SamplePayload(
            val id: String,
            val score: Int,
            val label: String? = null,
        )

        val serializer: PayloadSerializer = KotlinxPayloadSerializer

        test("serialize then deserialize returns equal value") {
            val original = SamplePayload(id = "abc", score = 42)
            val json = serializer.serialize(original, typeOf<SamplePayload>())
            val result = serializer.deserialize<SamplePayload>(json, typeOf<SamplePayload>())
            result shouldBe original
        }

        test("null field is preserved through roundtrip") {
            val original = SamplePayload(id = "abc", score = 0, label = null)
            val json = serializer.serialize(original, typeOf<SamplePayload>())
            val result = serializer.deserialize<SamplePayload>(json, typeOf<SamplePayload>())
            result.label shouldBe null
        }

        test("non-null field is preserved through roundtrip") {
            val original = SamplePayload(id = "abc", score = 7, label = "vip")
            val json = serializer.serialize(original, typeOf<SamplePayload>())
            val result = serializer.deserialize<SamplePayload>(json, typeOf<SamplePayload>())
            result.label shouldBe "vip"
        }

        test("deserializing JSON with unknown fields does not throw") {
            val jsonWithExtra = """{"id":"x","score":1,"label":null,"unknownField":"ignored"}"""
            val result = serializer.deserialize<SamplePayload>(jsonWithExtra, typeOf<SamplePayload>())
            result shouldBe SamplePayload(id = "x", score = 1, label = null)
        }
    })
