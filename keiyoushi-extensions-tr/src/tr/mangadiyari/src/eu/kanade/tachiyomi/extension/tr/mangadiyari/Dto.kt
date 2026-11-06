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
    // Both are required by the app, not by the API: a single row without them must not make the
    // whole list unparseable, so the defaults keep the response readable and the row is skipped.
    val slug: String = "",
    private val title: String = "",
    @SerialName("cover_url") private val coverUrl: String? = null,
    private val description: String? = null,
    private val author: String? = null,
    private val artist: String? = null,
    private val status: String? = null,
    private val type: String? = null,
    private val genres: List<String> = emptyList(),
    @SerialName("alt_names") private val altNames: String? = null,
) {
    fun toEntry(baseUrl: String) = SeriesEntry(
        slug = slug,
        title = title,
        thumbnailUrl = coverUrl?.takeIf { it.isNotBlank() }?.let { baseUrl.resolveImage(it) },
        author = author,
        artist = artist,
        genre = buildList {
            type?.takeIf { it.isNotBlank() }?.let { add(it.replaceFirstChar(Char::uppercase)) }
            addAll(genres.filter { it.isNotBlank() })
        }.joinToString().ifBlank { null },
        description = buildString {
            this@SeriesDto.description?.let {
                append(Jsoup.parseBodyFragment(it).wholeText().replace(PARAGRAPH_GAP, "\n\n").trim())
            }
            if (!altNames.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif isimler: ")
                append(altNames)
            }
        }.ifBlank { null },
        status = when (this@SeriesDto.status) {
            "ongoing" -> SManga.ONGOING
            "completed" -> SManga.COMPLETED
            "hiatus" -> SManga.ON_HIATUS
            "cancelled" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        },
    )

    fun toSManga(baseUrl: String): SManga = toEntry(baseUrl).toSManga()

    /** Rows without a slug or a title cannot be opened, so the list drops them instead of failing. */
    fun toEntryOrNull(baseUrl: String): SeriesEntry? = if (slug.isBlank() || title.isBlank()) null else toEntry(baseUrl)

    fun toSMangaOrNull(baseUrl: String): SManga? = toEntryOrNull(baseUrl)?.toSManga()
}

/** The parts of a series the extension sends to the app; kept apart from [SManga] so it can be tested. */
data class SeriesEntry(
    val slug: String,
    val title: String,
    val thumbnailUrl: String?,
    val author: String?,
    val artist: String?,
    val genre: String?,
    val description: String?,
    val status: Int,
) {
    fun toSManga() = SManga.create().apply {
        url = slug
        title = this@SeriesEntry.title
        thumbnail_url = this@SeriesEntry.thumbnailUrl
        author = this@SeriesEntry.author
        artist = this@SeriesEntry.artist
        genre = this@SeriesEntry.genre
        description = this@SeriesEntry.description
        status = this@SeriesEntry.status
    }
}

@Serializable
class ChapterDto(
    // `url` carries this id because `/api/chapters/{id}` is how the page list is read.
    // Defaulted so a single row without an id cannot break the whole chapter list.
    val id: Long = 0,
    // The site stores unnumbered chapters, so this is not guaranteed.
    @SerialName("chapter_number") val chapterNumber: Float? = null,
    private val title: String? = null,
    @SerialName("publish_at") private val publishAt: String? = null,
    @SerialName("created_at") private val createdAt: String? = null,
) {
    fun toEntry(slug: String): ChapterEntry {
        val number = chapterNumber?.toChapterNumber()
        return ChapterEntry(
            slug = slug,
            id = id,
            number = number,
            name = title?.takeIf { it.isNotBlank() } ?: "Bölüm ${number ?: id}",
            chapterNumber = chapterNumber ?: -1f,
            dateUpload = Instant.tryParse(publishAt ?: createdAt),
        )
    }
}

data class ChapterEntry(
    val slug: String,
    val id: Long,
    val number: String?,
    val name: String,
    val chapterNumber: Float,
    val dateUpload: Long,
) {
    fun toSChapter() = SChapter.create().apply {
        url = id.toString()
        memo = buildJsonObject {
            put("slug", slug)
            if (number != null) put("number", number)
        }
        name = this@ChapterEntry.name
        chapter_number = this@ChapterEntry.chapterNumber
        date_upload = this@ChapterEntry.dateUpload
    }

    /** The reader route the site serves: it is keyed by the chapter number, not by the row id. */
    fun readerUrl(baseUrl: String): String = readerUrl(baseUrl, slug, number ?: id.toString())
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

data class PageEntry(val pageNumber: Int, val imageUrl: String, val resolvedUrl: String)

/**
 * The API returns chapters unsorted and with gaps, and a series may repeat a number, so order by
 * number (newest first) and drop repeated ids.
 */
internal fun List<ChapterDto>.toEntries(slug: String) = distinctBy { it.id }
    .sortedWith(
        compareByDescending<ChapterDto> { it.chapterNumber ?: -1f }
            .thenByDescending { it.id },
    )
    .map { it.toEntry(slug) }

/** Builds reader pages in page order, skipping pages without an image and repeated images. */
internal fun List<PageDto>.toPageEntries(baseUrl: String) = mapNotNull { dto ->
    dto.imageUrl?.let { PageEntry(dto.pageNumber, it, baseUrl.resolveImage(it)) }
}
    .sortedBy { it.pageNumber }
    .distinctBy { it.imageUrl }

internal fun List<PageEntry>.toPages(chapterUrl: String) = mapIndexed { index, entry ->
    Page(index, chapterUrl, entry.resolvedUrl)
}

internal fun Float.toChapterNumber(): String = toString().removeSuffix(".0")

private val PARAGRAPH_GAP = Regex("""\s*\n\s*\n\s*""")

fun String.resolveImage(src: String): String = when {
    src.startsWith("http") -> src
    src.startsWith("//") -> "https:$src"
    src.startsWith("/") -> this + src
    else -> "$this/$src"
}
