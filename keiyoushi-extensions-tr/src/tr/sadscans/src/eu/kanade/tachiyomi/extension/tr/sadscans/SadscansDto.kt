package eu.kanade.tachiyomi.extension.tr.sadscans

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** tRPC batch envelope: `[{result:{data:{json:<payload>}}}]`. */
@Serializable
class TrpcBatchItem(
    val result: TrpcResult? = null,
)

@Serializable
class TrpcResult(
    val data: TrpcData? = null,
)

@Serializable
class TrpcData(
    val json: JsonElement? = null,
)

/** Series item returned by `<searchRouter>.getSeries`. */
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

/** `{series:{...}, chapters:[...], pagination:{totalPages}}` from `<detailRouter>.getSeriesData`. */
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
        get() = no?.contentOrNull?.toFloatOrNull()
}

@Serializable
class GenreDto(
    val id: JsonPrimitive? = null,
    val name: String? = null,
    val slug: String? = null,
) {
    val idString: String?
        get() = id?.contentOrNull
}
