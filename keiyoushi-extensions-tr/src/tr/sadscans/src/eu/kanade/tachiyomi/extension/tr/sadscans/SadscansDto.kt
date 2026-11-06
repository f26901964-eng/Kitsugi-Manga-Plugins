package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** tRPC batch zarfı: `[{result:{data:{json:<payload>}}}]` veya `[{error:{json:{...}}}]`. */
@Serializable
class TrpcBatchItem(
    val result: TrpcResult? = null,
    val error: TrpcError? = null,
)

@Serializable
class TrpcResult(
    val data: TrpcData? = null,
)

@Serializable
class TrpcData(
    val json: JsonElement? = null,
)

@Serializable
class TrpcError(
    val json: JsonObject? = null,
) {
    fun message(): String {
        val payload = json ?: return "bilinmeyen hata"
        return listOf("message", "data", "code", "httpStatus")
            .firstNotNullOfOrNull { key ->
                (payload[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            } ?: "bilinmeyen hata"
    }
}

/** `<searchRouter>.getSeries` öğesi. */
@Serializable
class SeriesItemDto(
    val id: JsonPrimitive? = null,
    val href: String? = null,
    val slug: String? = null,
    val thumb: String? = null,
    val name: String? = null,
    val status: String? = null,
    val views: Long? = null,
    val ratingAverage: Double? = null,
    val ratingCount: Long? = null,
    val type: String? = null,
) {
    val idString: String?
        get() = id?.contentOrNull
}

/** `<detailRouter>.getSeriesData` yanıtı: `{series, chapters, pagination}`. */
@Serializable
class SeriesDataDto(
    val series: JsonObject? = null,
    val chapters: List<ChapterItemDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
class PaginationDto(
    val totalPages: Int? = null,
    val page: Int? = null,
    val total: Int? = null,
)

@Serializable
class ChapterItemDto(
    val chap_id: JsonPrimitive? = null,
    val id: JsonPrimitive? = null,
    val name: String? = null,
    val no: JsonPrimitive? = null,
    val slug: String? = null,
    val href: String? = null,
    val createdAt: String? = null,
    val date: String? = null,
) {
    val idString: String?
        get() = (chap_id ?: id)?.contentOrNull

    val numberValue: Float?
        get() = no?.contentOrNull?.replace(',', '.')?.toFloatOrNull()
}
