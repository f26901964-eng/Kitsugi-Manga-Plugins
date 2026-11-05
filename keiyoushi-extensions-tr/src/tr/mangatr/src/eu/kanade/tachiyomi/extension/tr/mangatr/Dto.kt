package eu.kanade.tachiyomi.extension.tr.mangatr

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer

@Serializable
class ChallengeRequestDto(val challenge: String)

@Serializable
class ReaderGateDto(val k: String)

@Serializable
class ReaderGateRequestDto(val n: String)

@Serializable
class ReaderConfigDto(
    val data: ReaderAttributesDto,
    @Serializable(with = ReaderSeedsSerializer::class)
    val seeds: Map<String, Long> = emptyMap(),
    val grid: Int = 2,
)

@Serializable
class ReaderAttributesDto(
    val parts: String,
    val pageIndex: String,
    val order: String? = null,
)

/** cap.js accepts either an array indexed by page or an object keyed by page index. */
internal object ReaderSeedsSerializer : JsonTransformingSerializer<Map<String, Long>>(
    MapSerializer(String.serializer(), Long.serializer()),
) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> JsonObject(element.mapIndexed { index, value -> index.toString() to value }.toMap())
        else -> element
    }
}
