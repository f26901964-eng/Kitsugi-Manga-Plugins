package eu.kanade.tachiyomi.extension.tr.mangatr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
class ChallengeRequestDto(val challenge: String)

@Serializable
class ReaderGateDto(val k: String)

@Serializable
class ReaderGateRequestDto(val n: String, val cv: String)

@Serializable
class ReaderConfigDto(
    val data: ReaderAttributesDto,
    @Serializable(with = ReaderSeedsSerializer::class)
    val seeds: Map<String, Long> = emptyMap(),
    @Serializable(with = ReaderFlexibleIntSerializer::class)
    val grid: Int = 2,
)

@Serializable
class ReaderAttributesDto(
    val parts: String,
    val pageIndex: String,
    val order: String? = null,
)

/** The reader can serialize numeric seed/grid values as JSON strings. */
private fun JsonElement.numericPrimitive(): JsonElement {
    if (this !is JsonPrimitive || !isString) return this
    return content.toLongOrNull()?.let(::JsonPrimitive) ?: this
}

internal object ReaderFlexibleIntSerializer : JsonTransformingSerializer<Int>(Int.serializer()) {
    override fun transformDeserialize(element: JsonElement): JsonElement = element.numericPrimitive()
}

/** cap.js accepts either an array indexed by page or an object keyed by page index. */
internal object ReaderSeedsSerializer : JsonTransformingSerializer<Map<String, Long>>(
    MapSerializer(String.serializer(), Long.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonObject(
            element.mapIndexed { index, value -> index.toString() to value.numericPrimitive() }.toMap(),
        )
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> value.numericPrimitive() })
        else -> element
    }
}
