package eu.kanade.tachiyomi.extension.tr.mangadiyari

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.utils.tryParse
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import kotlin.time.Instant

@Serializable
class SeriesListResponse(
    val series: List<SeriesDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
class LatestUpdatesResponse(
    val updates: List<SeriesDto> = emptyList(),
    val hasMore: Boolean = false,
)

@Serializable
class SeriesDetailsResponse(
    val series: SeriesDto,
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class SeriesDto(
    val slug: String,
    private val title: String,
    @SerialName("cover_url") private val coverUrl: String? = null,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    private val type: String? = null,
    private val genres: List<String> = emptyList(),
    @SerialName("alt_names") private val altNames: String? = null,
) {
    fun toSManga(baseUrl: String) = SManga.create().apply {
        url = slug
        title = this@SeriesDto.title
        thumbnail_url = coverUrl?.takeIf { it.isNotBlank() }?.let { baseUrl.resolveImage(it) }
        author = this@SeriesDto.author
        artist = this@SeriesDto.artist
        genre = buildList {
            type?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar(Char::uppercase)) }
            addAll(genres.filter { it.isNotBlank() })
        }.joinToString().ifBlank { null }
        description = buildString {
            this@SeriesDto.description?.let {
                append(Jsoup.parseBodyFragment(it).wholeText().replace(PARAGRAPH_GAP, "\n\n").trim())
            }
            if (!altNames.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif isimler: ")
                append(altNames)
            }
        }.ifBlank { null }
        status = when (this@SeriesDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }
}

@Serializable
class ChapterDto(
    // `url` carries this id because `/api/chapters/{id}` is how the page list is read.
    private val id: Long,
    // The site stores unnumbered chapters, so this is not guaranteed.
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    private val title: String? = null,
    @SerialName("publish_at") private val publishAt: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toSChapter(slug: String) = SChapter.create().apply {
        val number = chapterNumber?.toChapterNumber()

        url = id.toString()
        memo = buildJsonObject {
            put("slug", slug)
            if (number != null) put("number", number)
        }
        name = title?.takeIf { it.isNotBlank() } ?: "Bölüm ${number ?: id}"
        chapter_number = chapterNumber ?: -1f
        date_upload = Instant.tryParse(publishAt ?: createdAt)
    }
}

@Serializable
class ChapterPagesResponse(
    val pages: List<PageDto> = emptyList(),
)

@Serializable
class PageDto(
    @SerialName("page_number") val pageNumber: Int = 0,
    // Either field may be missing; the site has served pages with only one of them filled.
    @SerialName("display_image") private val displayImage: String? = null,
    @SerialName("image_path") private val imagePath: String? = null,
) {
    val imageUrl: String?
        get() = displayImage?.takeIf { it.isNotBlank() } ?: imagePath?.takeIf { it.isNotBlank() }
}

/**
 * The API returns chapters unsorted and with gaps, and a series may repeat a number, so order by
 * number (newest first) and drop repeated ids.
 */
internal fun List<ChapterDto>.toSChapters(slug: String) = distinctBy { it.id }
    .sortedWith(
        compareByDescending<ChapterDto> { it.chapterNumber ?: -1f }
            .thenByDescending { it.id },
    )
    .map { it.toSChapter(slug) }

/** Builds reader pages in page order, skipping pages without an image and repeated images. */
internal fun List<PageDto>.toPages(baseUrl: String, chapterUrl: String) = mapNotNull { dto ->
    dto.imageUrl?.let { dto.pageNumber to it }
}
    .sortedBy { it.first }
    .distinctBy { it.second }
    .mapIndexed { index, (_, image) -> Page(index, chapterUrl, baseUrl.resolveImage(image)) }

internal fun Float.toChapterNumber(): String = toString().removeSuffix(".0")

private val PARAGRAPH_GAP = Regex("""\s*\n\s*\n\s*""")

fun String.resolveImage(src: String): String = when {
    src.startsWith("http") -> src
    src.startsWith("//") -> "https:$src"
    src.startsWith("/") -> this + src
    else -> "$this/$src"
}
