package eu.kanade.tachiyomi.extension.tr.mangadenizi

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlin.time.Instant

@Serializable
class MangaApiResponse<T>(
    val data: T,
)

@Serializable
class MangaIndexData(
    val manga: MangaListDataDto,
)

@Serializable
class MangaDetailsData(
    val manga: MangaDto,
)

@Serializable
class MangaListDataDto(
    val data: List<MangaDto>,
    @SerialName("current_page") val currentPage: Int,
    @SerialName("last_page") val lastPage: Int,
)

@Serializable
class MangaDto(
    private val title: String,
    val slug: String,
    @SerialName("cover_url") private val coverUrl: String? = null,
    @SerialName("cover_thumb_url") private val coverThumbUrl: String? = null,
    private val description: String? = null,
    private val status: String? = null,
    private val categories: List<CategoryDto> = emptyList(),
    private val genres: List<CategoryDto> = emptyList(),
    private val authors: List<AuthorDto> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
) {
    fun toSManga() = SManga.create().apply {
        title = this@MangaDto.title
        url = "/manga/$slug"
        thumbnail_url = coverUrl ?: coverThumbUrl
        description = this@MangaDto.description
        status = when (this@MangaDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
        genre = (categories + genres).map { it.name }.distinct().joinToString()
        author = authors.joinToString { it.name }
    }
}

@Serializable
class CategoryDto(
    val name: String,
)

@Serializable
class AuthorDto(
    val name: String,
)

@Serializable
class ChapterDto(
    private val number: JsonElement,
    private val title: String? = null,
    private val slug: String,
    @SerialName("published_at") private val publishedAt: String? = null,
) {
    fun toSChapter(mangaSlug: String) = SChapter.create().apply {
        url = "/read/$mangaSlug/$slug"
        val rawNumber = number.jsonPrimitive.contentOrNull ?: number.toString()
        // Site "762.0" gibi ondalıklı yazabiliyor; tam sayıysa ".0" kısmını at.
        val numberStr = rawNumber.removeSuffix(".0")
        name = "Bölüm $numberStr" + (if (title.isNullOrBlank()) "" else ": $title")
        chapter_number = rawNumber.toFloatOrNull() ?: -1f
        date_upload = Instant.tryParse(publishedAt)
    }
}

@Serializable
class ReaderDto(
    val pages: List<ReaderPageDto> = emptyList(),
)

@Serializable
class ReaderPageDto(
    @SerialName("image_url") private val url: String,
    private val scramble: ScrambleDto? = null,
) {
    fun toPage(index: Int, baseUrl: String): Page {
        // Göreli yolları (ör. /storage/...) mutlak adrese çevir; yoksa toHttpUrl çöker.
        val absolute = if (url.startsWith("/")) "$baseUrl$url" else url
        val pageUrl = if (scramble?.method == "tiled-v1" && scramble.grid != null && scramble.seed != null) {
            absolute.toHttpUrl().newBuilder()
                .addQueryParameter(UnscramblerInterceptor.PARAM_GRID, scramble.grid.toString())
                .addQueryParameter(UnscramblerInterceptor.PARAM_SEED, scramble.seed.toString())
                .build().toString()
        } else {
            absolute
        }
        return Page(index, imageUrl = pageUrl)
    }
}

@Serializable
class ScrambleDto(
    val method: String? = null,
    val grid: Int? = null,
    val seed: Long? = null,
)
