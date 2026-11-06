package eu.kanade.tachiyomi.extension.tr.turkmanga

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.Locale

/** Parsing helpers for Turkmanga's Next.js HTML and schema.org JSON-LD pages. */
internal object TurkmangaParser {
    private const val DEFAULT_PAGE_SIZE = 20
    private const val API_BASE_URL = "https://api.turkmanga.org"
    private val json = Json { ignoreUnknownKeys = true }
    private val safeSegment = Regex("""[\p{L}\p{N}._~-]+""")
    private val chapterNumberPattern = Regex("""\d+(?:\.\d+)?""")
    private val pageImageHosts = setOf("cdnapi.turkmanga.org", "api.turkmanga.org")

    internal data class MangaSummary(
        val url: String,
        val title: String,
        val thumbnailUrl: String?,
        val description: String? = null,
        val author: String? = null,
        val genre: String? = null,
        val status: Int = SManga.UNKNOWN,
        val updatedAt: Long = 0L,
    )

    internal data class ChapterSummary(
        val url: String,
        val name: String,
        val chapterNumber: Float,
        val dateUpload: Long,
        val scanlator: String?,
    )

    internal data class MangaList(
        val mangas: List<MangaSummary>,
        val hasNextPage: Boolean,
    )

    internal data class MangaDetails(
        val manga: MangaSummary,
        val chapters: List<ChapterSummary>,
    )

    internal fun listingUrl(baseUrl: String, page: Int, sort: String? = null): HttpUrl =
        "$baseUrl/mangalar".toHttpUrl().newBuilder().apply {
            if (page > 1) addQueryParameter("page", page.toString())
            if (!sort.isNullOrBlank()) addQueryParameter("sort", sort)
        }.build()

    internal fun searchUrl(baseUrl: String, page: Int, query: String, genre: String = ""): HttpUrl {
        val route = if (query.isBlank()) "$baseUrl/mangalar" else "$baseUrl/search"
        return route.toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("input", query.trim())
            if (page > 1) addQueryParameter("page", page.toString())
            if (genre.isNotBlank()) addQueryParameter("genre", genre)
        }.build()
    }

    internal fun parseMangaList(document: Document, baseUrl: String, page: Int, newestFirst: Boolean = false): MangaList {
        val state = nextData(document)
        val parsed = collectObjects(state)
            .mapNotNull { parseSummary(it, baseUrl) }
            .distinctBy { it.url }
            .ifEmpty { parseSummaryCards(document, baseUrl) }
            .let { summaries ->
                if (newestFirst) summaries.sortedWith(compareByDescending<MangaSummary> { it.updatedAt }) else summaries
            }

        val hasNext = hasNextPage(document, state, page, parsed.size)
        return MangaList(parsed, hasNext)
    }

    internal fun parseMangaDetails(document: Document, baseUrl: String, requestedUrl: String): MangaDetails {
        val mangaPath = mangaPath(requestedUrl, baseUrl)
            ?: mangaPath(document.selectFirst("link[rel=canonical]")?.attr("href").orEmpty(), baseUrl)
            ?: "/manga/${requestedUrl.trim('/').substringAfterLast('/')}"
        val mangaSlug = mangaPath.split('/').filter { it.isNotBlank() }.getOrNull(1).orEmpty()
        val structured = jsonLdObjects(document)
        val series = structured.firstOrNull { it.hasType("ComicSeries") }
            ?: collectObjects(nextData(document)).firstOrNull {
                it.string("slug") == mangaSlug && it.string("name") != null
            }

        val manga = MangaSummary(
            url = mangaPath,
            title = series?.string("name")
                ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.cleanSiteSuffix()
                ?: document.title().cleanSiteSuffix(),
            description = series?.string("description")
                ?: document.selectFirst("meta[name=description]")?.attr("content")?.takeIf { it.isNotBlank() },
            author = series?.let { names(it["author"] ?: it["creator"]).joinToString(", ").takeIf(String::isNotBlank) },
            genre = series?.let { names(it["genre"]).distinct().joinToString(", ").takeIf(String::isNotBlank) },
            status = series?.let { parseStatus(it) } ?: SManga.UNKNOWN,
            thumbnailUrl = series?.let { imageUrl(it["image"], document.location()) }
                ?: resolveImage(document.selectFirst("meta[property=og:image]")?.attr("content"), document.location())
                ?: coverUrl(mangaSlug),
        )

        val seriesTranslator = series?.let { names(it["translator"]).joinToString(", ").takeIf(String::isNotBlank) }
        val structuredChapters = series?.get("hasPart")?.asList().orEmpty()
            .mapNotNull { parseChapter(it, baseUrl, mangaSlug, seriesTranslator) }
            .distinctBy { it.url }
        val chapters = if (structuredChapters.isNotEmpty()) {
            structuredChapters
        } else {
            parseChapterLinks(document, baseUrl, mangaSlug, seriesTranslator)
        }

        return MangaDetails(manga, chapters)
    }

    internal fun parseImageUrls(document: Document, baseUrl: String, chapterUrl: String): List<String> {
        val requestedPath = chapterPath(chapterUrl, baseUrl)
        val issue = jsonLdObjects(document)
            .filter { it.hasType("ComicIssue") }
            .firstOrNull { requestedPath == null || chapterPath(it.string("url") ?: it.string("@id").orEmpty(), baseUrl) == requestedPath }
            ?: jsonLdObjects(document).firstOrNull { it.hasType("ComicIssue") }

        val structuredImages = issue?.let {
            imageUrls(it["image"] ?: it["associatedMedia"] ?: it["hasPart"], document.location())
        }.orEmpty()
        if (structuredImages.isNotEmpty()) return structuredImages.distinct()

        val stateImages = collectObjects(nextData(document))
            .asSequence()
            .flatMap { obj -> obj.values.asSequence() }
            .flatMap { element -> imageUrls(element, document.location()).asSequence() }
            .filter(::isReaderImage)
            .distinct()
            .toList()
        if (stateImages.isNotEmpty()) return stateImages

        return document.select("img").mapNotNull { image ->
            listOf("data-original", "data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attribute ->
                image.attr(attribute).takeIf { it.isNotBlank() }?.let { resolveImage(it, document.location()) }
            }
        }.filter(::isReaderImage).distinct()
    }

    internal fun mangaPath(rawUrl: String, baseUrl: String): String? {
        val url = resolveSiteUrl(rawUrl, baseUrl) ?: return null
        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.size != 2 || segments[0] != "manga" || !safeSegment.matches(segments[1])) return null
        return "/manga/${segments[1]}"
    }

    internal fun chapterPath(rawUrl: String, baseUrl: String): String? {
        val url = resolveSiteUrl(rawUrl, baseUrl) ?: return null
        val segments = url.pathSegments.filter(String::isNotBlank)
        if (segments.size != 3 || segments[0] != "manga" || segments.any { !safeSegment.matches(it) }) return null
        return "/manga/${segments[1]}/${segments[2]}"
    }

    internal fun mangaUrl(rawUrl: String, baseUrl: String): String {
        val path = mangaPath(rawUrl, baseUrl)
            ?: rawUrl.trim().trim('/').substringAfterLast('/').takeIf { safeSegment.matches(it) }
                ?.let { "/manga/$it" }
            ?: return baseUrl.trimEnd('/')
        return baseUrl.trimEnd('/') + path
    }

    internal fun chapterUrl(rawUrl: String, baseUrl: String): String {
        val path = chapterPath(rawUrl, baseUrl) ?: return baseUrl.trimEnd('/')
        return baseUrl.trimEnd('/') + path
    }

    private fun parseSummary(obj: JsonObject, baseUrl: String): MangaSummary? {
        val slug = obj.string("slug")?.trim()?.takeIf(safeSegment::matches) ?: return null
        val title = obj.string("name")?.cleanText()?.takeIf { it.isNotBlank() } ?: return null
        // Episodes also have slug/name (and sometimes _id); require a series-only field.
        if (listOf("type", "finished", "nsfw", "description", "manga_type").none(obj::containsKey)) return null

        val episode = obj["episode"] as? JsonObject
        val updatedAt = parseTimestamp(
            episode?.string("date"),
            obj.string("updatedAt"),
            obj.string("updated_at"),
            obj.string("lastUpdated"),
            obj.string("last_update"),
        )
        return MangaSummary(
            url = "/manga/$slug",
            title = title,
            thumbnailUrl = firstImage(
                obj,
                listOf("cover", "coverUrl", "cover_url", "thumbnail", "thumbnailUrl", "poster", "image"),
                baseUrl,
            ) ?: coverUrl(slug),
            description = obj.string("description")?.takeIf { it.isNotBlank() },
            genre = names(obj["genre"] ?: obj["genres"]).distinct().joinToString(", ").takeIf(String::isNotBlank),
            status = parseStatus(obj),
            updatedAt = updatedAt,
        )
    }

    private fun parseSummaryCards(document: Document, baseUrl: String): List<MangaSummary> =
        document.select("a[href]").mapNotNull { link ->
            val path = mangaPath(link.attr("abs:href").ifBlank { link.attr("href") }, baseUrl) ?: return@mapNotNull null
            val slug = path.substringAfterLast('/')
            val image = link.selectFirst("img")
            val title = link.selectFirst("h1, h2, h3, [class*=title]")?.text()?.trim()
                ?.takeIf(String::isNotBlank)
                ?: image?.attr("alt")?.trim()?.takeIf(String::isNotBlank)
                ?: link.attr("title").trim().takeIf(String::isNotBlank)
                ?: link.text().trim().takeIf(String::isNotBlank)
                ?: return@mapNotNull null

            val thumb = image?.let {
                listOf("data-original", "data-src", "data-lazy-src", "src").firstNotNullOfOrNull { attr ->
                    it.attr(attr).takeIf(String::isNotBlank)?.let { raw -> resolveImage(raw, document.location()) }
                }
            }
            MangaSummary(
                url = path,
                title = title,
                thumbnailUrl = thumb ?: coverUrl(slug),
            )
        }.distinctBy { it.url }

    private fun parseChapter(
        element: JsonElement,
        baseUrl: String,
        mangaSlug: String,
        defaultTranslator: String?,
    ): ChapterSummary? {
        val obj = element as? JsonObject ?: return null
        val rawUrl = obj.string("url") ?: obj.string("@id")
            ?: obj.string("slug")?.let { "$baseUrl/manga/$mangaSlug/$it" }
            ?: return null
        val path = chapterPath(rawUrl, baseUrl) ?: return null
        if (mangaSlug.isNotBlank() && path.split('/').getOrNull(2) != mangaSlug) return null

        val name = obj.string("name")?.cleanText().orEmpty()
        val number = obj["issueNumber"].primitiveText()?.toFloatOrNull()
            ?: obj["chapterNumber"].primitiveText()?.toFloatOrNull()
            ?: chapterNumberPattern.find(name)?.value?.toFloatOrNull()
            ?: -1f
        return ChapterSummary(
            url = path,
            name = name.ifBlank {
                if (number >= 0f) "Bölüm ${number.toDisplayNumber()}" else "Bölüm"
            },
            chapterNumber = number,
            dateUpload = parseTimestamp(
                obj.string("datePublished"),
                obj.string("dateModified"),
                obj.string("uploadDate"),
                obj.string("date"),
            ),
            scanlator = names(obj["translator"]).joinToString(", ").takeIf(String::isNotBlank) ?: defaultTranslator,
        )
    }

    private fun parseChapterLinks(
        document: Document,
        baseUrl: String,
        mangaSlug: String,
        defaultTranslator: String?,
    ): List<ChapterSummary> = document.select("a[href]").mapNotNull { link ->
        val path = chapterPath(link.attr("abs:href").ifBlank { link.attr("href") }, baseUrl) ?: return@mapNotNull null
        if (mangaSlug.isNotBlank() && path.split('/').getOrNull(2) != mangaSlug) return@mapNotNull null
        val title = link.selectFirst("[class*=chapter], [class*=episode]")?.text()?.trim()
            ?.takeIf(String::isNotBlank)
            ?: link.text().trim().takeIf(String::isNotBlank)
            ?: return@mapNotNull null
        val number = chapterNumberPattern.find(title)?.value?.toFloatOrNull() ?: -1f
        ChapterSummary(
            url = path,
            name = title,
            chapterNumber = number,
            dateUpload = parseTimestamp(
                link.attr("data-date"),
                link.parent()?.selectFirst("time")?.attr("datetime"),
                link.parent()?.selectFirst("time")?.text(),
            ),
            scanlator = defaultTranslator,
        )
    }.distinctBy { it.url }

    private fun hasNextPage(document: Document, state: JsonElement?, page: Int, resultCount: Int): Boolean {
        if (resultCount == 0) return false
        if (document.select("a[rel=next], a[aria-label*=Sonraki], a[aria-label*=Next]").isNotEmpty()) return true
        val objects = collectObjects(state)
        for (obj in objects) {
            for (key in listOf("hasNextPage", "has_next_page", "hasMore", "has_more")) {
                obj[key].asBooleanOrNull()?.let { return it }
            }
            val current = listOf("currentPage", "current_page", "page").firstNotNullOfOrNull { obj[it].primitiveText()?.toIntOrNull() }
            val last = listOf("lastPage", "last_page", "totalPages", "total_pages").firstNotNullOfOrNull { obj[it].primitiveText()?.toIntOrNull() }
            if (current != null && last != null) return current < last
            if (last != null) return page < last
        }
        return resultCount >= DEFAULT_PAGE_SIZE
    }

    private fun parseStatus(obj: JsonObject): Int {
        val status = listOf("creativeWorkStatus", "status", "publicationStatus")
            .firstNotNullOfOrNull { obj.string(it) }
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        return when {
            status.contains("complete") || status.contains("tamam") || status.contains("bitti") -> SManga.COMPLETED
            status.contains("ongoing") || status.contains("devam") || status == "current" -> SManga.ONGOING
            status.contains("hiatus") || status.contains("ara ver") || status.contains("bekle") -> SManga.ON_HIATUS
            obj["finished"].asBooleanOrNull() == true -> SManga.COMPLETED
            obj["finished"].asBooleanOrNull() == false -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    private fun jsonLdObjects(document: Document): List<JsonObject> = document
        .select("script[type=application/ld+json]")
        .flatMap { script -> parseJson(script)?.let(::collectObjects).orEmpty() }

    private fun nextData(document: Document): JsonElement? =
        document.selectFirst("script#__NEXT_DATA__")?.let(::parseJson)

    private fun parseJson(script: Element): JsonElement? {
        val data = script.data().ifBlank { script.html() }.trim()
        if (data.isBlank()) return null
        return runCatching { json.parseToJsonElement(data) }.getOrNull()
    }

    private fun collectObjects(root: JsonElement?): List<JsonObject> {
        if (root == null || root is JsonNull) return emptyList()
        val found = mutableListOf<JsonObject>()
        val pending = ArrayDeque<JsonElement>()
        pending.addLast(root)
        while (pending.isNotEmpty()) {
            when (val current = pending.removeFirst()) {
                is JsonObject -> {
                    found += current
                    current.values.forEach(pending::addLast)
                }
                is JsonArray -> current.forEach(pending::addLast)
                else -> Unit
            }
        }
        return found
    }

    private fun JsonObject.hasType(type: String): Boolean {
        val types = this["@type"]
        val values = when (types) {
            is JsonArray -> types.mapNotNull { it.primitiveText() }
            null -> emptyList()
            else -> listOfNotNull(types.primitiveText())
        }
        return values.any { it.substringAfterLast('/').equals(type, ignoreCase = true) }
    }

    private fun JsonObject.string(key: String): String? = this[key].primitiveText()
        ?.cleanText()
        ?.takeIf { it.isNotBlank() }

    private fun JsonElement?.primitiveText(): String? = when (this) {
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        else -> null
    }

    private fun JsonElement?.asBooleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement?.asList(): List<JsonElement> = when (this) {
        is JsonArray -> toList()
        is JsonObject -> listOf(this)
        else -> emptyList()
    }

    private fun names(element: JsonElement?): List<String> = when (element) {
        is JsonPrimitive -> element.contentOrNull?.cleanText()?.takeIf(String::isNotBlank)?.let(::listOf).orEmpty()
        is JsonArray -> element.flatMap(::names)
        is JsonObject -> listOfNotNull(
            element.string("name"),
            element.string("displayName"),
            element.string("display_name"),
        ).distinct()
        else -> emptyList()
    }

    private fun firstImage(obj: JsonObject, keys: List<String>, baseUrl: String): String? =
        keys.firstNotNullOfOrNull { key -> imageUrl(obj[key], baseUrl) }

    private fun imageUrl(element: JsonElement?, baseUrl: String): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let { resolveImage(it, baseUrl) }
        is JsonArray -> element.firstNotNullOfOrNull { imageUrl(it, baseUrl) }
        is JsonObject -> listOf("contentUrl", "url", "src", "image", "thumbnail", "thumbnailUrl")
            .firstNotNullOfOrNull { imageUrl(element[it], baseUrl) }
        else -> null
    }

    private fun imageUrls(element: JsonElement?, baseUrl: String): List<String> = when (element) {
        is JsonPrimitive -> element.contentOrNull?.let { resolveImage(it, baseUrl) }?.let(::listOf).orEmpty()
        is JsonArray -> element.flatMap { imageUrls(it, baseUrl) }
        is JsonObject -> listOf("contentUrl", "url", "src", "image", "thumbnail", "thumbnailUrl")
            .flatMap { imageUrls(element[it], baseUrl) }
        else -> emptyList()
    }

    private fun resolveImage(raw: String?, baseUrl: String): String? {
        val value = raw?.trim()?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() } ?: return null
        if (value.startsWith("data:", ignoreCase = true) || value.startsWith("javascript:", ignoreCase = true)) return null
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true) &&
            !value.startsWith("//") && !value.startsWith('/') && !value.contains('/') && !value.contains('.')
        ) return null
        val base = baseUrl.toHttpUrlOrNull() ?: "https://turkmanga.org/".toHttpUrl()
        val resolved = base.resolve(value) ?: value.toHttpUrlOrNull() ?: return null
        return resolved.takeIf { it.scheme == "https" || it.scheme == "http" }?.toString()
    }

    private fun isReaderImage(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.host in pageImageHosts && url.pathSegments.firstOrNull() == "image"
    }

    private fun resolveSiteUrl(rawUrl: String, baseUrl: String): HttpUrl? {
        val base = baseUrl.toHttpUrlOrNull() ?: return null
        val raw = rawUrl.trim().takeIf(String::isNotBlank) ?: return null
        val url = base.resolve(raw) ?: raw.toHttpUrlOrNull() ?: return null
        val acceptedHost = url.host == base.host || url.host == "www.${base.host}"
        return url.takeIf { acceptedHost && (it.scheme == "http" || it.scheme == "https") }
    }

    private fun coverUrl(slug: String): String =
        "$API_BASE_URL/api/media/manga/$slug?width=600&height=900"

    private fun parseTimestamp(vararg values: String?): Long = values.firstNotNullOfOrNull { value ->
        val candidate = value?.trim()?.takeIf(String::isNotBlank) ?: return@firstNotNullOfOrNull null
        runCatching { Instant.parse(candidate).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(candidate).toInstant().toEpochMilli() }.getOrNull()
            ?: runCatching { LocalDate.parse(candidate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
            ?: candidate.toLongOrNull()?.let { if (it < 10_000_000_000L) it * 1000 else it }
    } ?: 0L

    private fun String.cleanText(): String = trim().trimEnd(',', ';').trim()

    private fun String.cleanSiteSuffix(): String = cleanText()
        .removeSuffix(" | Turkmanga")
        .removeSuffix(" - Turkmanga")
        .removeSuffix(" - Manga")
        .trim()

    private fun Float.toDisplayNumber(): String = toString().removeSuffix(".0")
}
