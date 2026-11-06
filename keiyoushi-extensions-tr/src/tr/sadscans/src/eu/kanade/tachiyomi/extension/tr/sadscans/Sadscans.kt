package eu.kanade.tachiyomi.extension.tr.sadscans

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Instant

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" manga platformu.
 *
 * Güncel site yapısı (canlı siteden çıkarılan JS chunk'ları + HTML dökümleri):
 *  - Seri listesi / arama: `GET /api/trpc/_s4d7g2k9m0.getSeries?batch=1&input={"0":{"json":{"search":...,"page":...,"limit":15}}}`
 *    -> `[{result:{data:{json:[{id,href,thumb,name,status,views,ratingAverage,ratingCount,type}]}}}]`
 *  - Türler:               `GET /api/trpc/tur.getAll` -> `[{id,name,slug}]`
 *  - Seri detay + bölümler: `GET /api/trpc/_n3p6q1v8x5.getSeriesData?batch=1&input={"0":{"json":{"sef":<slug>,"page":1,"limit":50}}}`
 *    -> `{series:{...}, chapters:[{chap_id,name,no,...}], pagination:{totalPages,...}}`
 *  - Bölüm okuma: `/reader/{seriesSlug}/{chapterSlug}/{chapterId}` (kısa form `/reader/{chapterId}` de çalışır)
 *  - Görseller `/api/uploads/assets/series/{seriesId}/{chapterId}/images/...` altında; site Cloudflare arkasında.
 *
 * tRPC batch zarfı: `?batch=1&input=<urlencoded {"0":{"json":...}}>`, yanıt `[{result:{data:{json:...}}}]`.
 * Router adları canlı sitede build-time hash; chapter görselleri için birden fazla aday
 * endpoint sırayla denenir (bkz. [chapterImageProcedures]), yedek olarak okuyucu HTML'i kazınır.
 */
@Source
abstract class Sadscans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(CommonHeadersInterceptor())
    }

    private val apiHeaders: Headers by lazy {
        Headers.Builder()
            .add("Accept", "application/json, text/plain, */*")
            .add("Referer", "$baseUrl/")
            .build()
    }

    // ============================== tRPC plumbing ==============================

    private val trpcJson = Json { ignoreUnknownKeys = true }

    /** `<searchRouter>.getSeries` — canlı sitede build hash'i `_s4d7g2k9m0`. */
    protected open val seriesListRouter: String = "_s4d7g2k9m0"

    /** `<detailRouter>.getSeriesData` — canlı sitede build hash'i `_n3p6q1v8x5`. */
    protected open val seriesDetailRouter: String = "_n3p6q1v8x5"

    protected open val genreRouter: String = "tur"

    protected open val chapterImageProcedures: List<String> = listOf(
        "$seriesDetailRouter.getChapterData",
        "$seriesDetailRouter.getChapter",
        "$seriesDetailRouter.getPages",
        "$seriesListRouter.getChapterData",
        "chapterSingle.getChapterData",
        "chapterSingle.getChapter",
        "chapter.getChapterData",
        "chapter.getById",
        "chapters.getById",
    )

    private suspend fun trpcCall(procedure: String, input: JsonElement?): JsonElement {
        val inputParam = buildJsonObject {
            put("0", buildJsonObject { put("json", input ?: JsonNull) })
        }.toString()
        val url = "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", inputParam)
            .build()
        val batch = client.get(url, apiHeaders).parseAs<List<TrpcBatchItem>>()
        val payload = batch.firstOrNull()?.result?.data?.json
            ?: error("SadScans: tRPC yanıtı boş: $procedure")
        return payload
    }

    private suspend fun trpcCallArray(procedure: String, input: JsonElement?): JsonArray = trpcCall(procedure, input) as? JsonArray ?: error("SadScans: tRPC dizi bekleniyordu: $procedure")

    private suspend fun trpcCallObject(procedure: String, input: JsonElement?): JsonObject = trpcCall(procedure, input) as? JsonObject ?: error("SadScans: tRPC nesne bekleniyordu: $procedure")

    // ============================== Popular / Latest / Search ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(page, "-views", null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(page, "-updatedAt", null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "-views"
        return fetchSeries(page, sort, query.trim().ifBlank { null })
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SortFilter())

    private suspend fun fetchSeries(page: Int, sortBy: String, query: String?): MangasPage {
        val input = buildJsonObject {
            put("page", JsonPrimitive(page))
            put("limit", JsonPrimitive(PAGE_SIZE))
            put("sortBy", JsonPrimitive(sortBy))
            if (query != null) put("search", JsonPrimitive(query))
        }
        val items = trpcCallArray("$seriesListRouter.getSeries", input)
            .map { it.jsonObject }
            .mapNotNull { runCatching { trpcJson.decodeFromJsonElement(SeriesItemDto.serializer(), it) }.getOrNull() }
            .filter { !it.href.isNullOrBlank() && !it.name.isNullOrBlank() }
        val mangas = items.map { it.toSManga() }
        return MangasPage(mangas, mangas.size >= PAGE_SIZE)
    }

    // ============================== Details + Chapters ==============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "seriler") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply { this.url = "/seriler/$slug" }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.removePrefix("/seriler/").substringBefore('/')
        val details = if (fetchDetails) fetchDetails(slug, manga) else manga
        val chapterList = if (fetchChapters) fetchChapters(slug) else chapters
        return SMangaUpdate(details.apply { url = manga.url }, chapterList)
    }

    private suspend fun fetchDetails(slug: String, fallback: SManga): SManga {
        val data = fetchSeriesDataPage(slug, 1)
        val series = data?.series ?: return fallback
        return SManga.create().apply {
            url = "/seriler/${series["slug"]?.stringOrNull()?.ifBlank { null } ?: slug}"
            title = series["name"]?.stringOrNull()?.trim()?.ifBlank { null }
                ?: series["title"]?.stringOrNull()?.trim()?.ifBlank { null }
                ?: fallback.title
            thumbnail_url = listOf("cover", "thumb", "image", "featuredImage")
                .firstNotNullOfOrNull { series[it]?.stringOrNull()?.ifBlank { null } }
                ?.let(::absolute)
                ?: fallback.thumbnail_url
            description = listOf("description", "desc", "content", "summary")
                .firstNotNullOfOrNull { series[it]?.stringOrNull()?.ifBlank { null } }
                ?: fallback.description
            author = listOfNotNull(
                series["author"]?.stringOrNull()?.trim()?.ifBlank { null },
                series["artist"]?.stringOrNull()?.trim()?.ifBlank { null },
            ).joinToString().ifBlank { null }
            genre = parseGenres(series).joinToString().ifBlank { null }
            status = parseStatus(series["status"]?.stringOrNull() ?: series["durum"]?.stringOrNull())
        }
    }

    private fun parseGenres(series: JsonObject): List<String> {
        val arr = series["genres"] ?: series["turs"] ?: series["turler"] ?: series["tags"] ?: return emptyList()
        if (arr !is JsonArray) return emptyList()
        return arr.mapNotNull { item ->
            when (item) {
                is JsonObject -> item["name"]?.stringOrNull()?.trim()?.ifBlank { null }
                else -> item.contentOrNullOrNull()?.ifBlank { null }
            }
        }.distinct()
    }

    private fun parseStatus(status: String?): Int {
        if (status.isNullOrBlank()) return SManga.UNKNOWN
        val n = status.trim().lowercase(Locale("tr"))
        return when {
            n.contains("devam") -> SManga.ONGOING
            n.contains("tamamland") || n == "completed" -> SManga.COMPLETED
            n.contains("birak") || n == "dropped" || n == "cancelled" -> SManga.CANCELLED
            n.contains("ara ver") || n.contains("hiatus") || n.contains("bekle") -> SManga.ON_HIATUS
            n.contains("yakında") || n == "coming_soon" -> SManga.UNKNOWN
            else -> SManga.UNKNOWN
        }
    }

    private suspend fun fetchSeriesDataPage(slug: String, page: Int): SeriesDataDto? {
        val input = buildJsonObject {
            put("sef", JsonPrimitive(slug))
            put("page", JsonPrimitive(page))
            put("limit", JsonPrimitive(CHAPTER_PAGE_LIMIT))
        }
        return runCatching {
            trpcJson.decodeFromJsonElement(SeriesDataDto.serializer(), trpcCallObject("$seriesDetailRouter.getSeriesData", input))
        }.getOrNull()
    }

    private suspend fun fetchChapters(slug: String): List<SChapter> {
        val all = mutableListOf<ChapterItemDto>()
        var page = 1
        while (true) {
            val data = fetchSeriesDataPage(slug, page) ?: break
            all += data.chapters
            val totalPages = data.pagination?.totalPages ?: 1
            if (page >= totalPages || data.chapters.isEmpty()) break
            page++
        }
        return all.mapNotNull { it.toSChapter(slug) }
            .sortedByDescending { it.chapter_number }
    }

    // ============================== Reader pages ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast('/')
        val seriesSlug = chapter.url.removePrefix("/reader/").substringBefore('/')
        val chapterSlug = chapter.url.removePrefix("/reader/$seriesSlug/").substringBefore('/')

        // 1) tRPC chapter endpoint candidates
        for (procedure in chapterImageProcedures) {
            val urls = runCatching {
                fetchChapterImages(procedure, chapterId, chapterSlug)
            }.getOrNull()
            if (!urls.isNullOrEmpty()) return urls.mapIndexed { index, u -> Page(index, imageUrl = u) }
        }

        // 2) Reader page HTML / RSC payload fallback
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        val urls = doc.select("img.reader-img, main img[src*='/api/uploads']")
            .mapNotNull { img -> img.attr("src").ifBlank { img.attr("data-src") }.takeIf { it.isNotBlank() }?.let(::absolute) }
            .ifEmpty {
                doc.select("script").flatMap { s ->
                    Regex("/api/uploads/assets/[^\"\\\\ ]+").findAll(s.data()).map { it.value }.toList()
                }.map(::absolute)
            }
            .distinct()
        if (urls.isNotEmpty()) return urls.mapIndexed { index, u -> Page(index, imageUrl = u) }

        error("SadScans: bölüm görselleri bulunamadı: ${chapter.url}")
    }

    private suspend fun fetchChapterImages(procedure: String, chapterId: String, chapterSlug: String): List<String> {
        val inputVariants = listOf(
            buildJsonObject { put("id", JsonPrimitive(chapterId)) },
            buildJsonObject { put("chap_id", JsonPrimitive(chapterId)) },
            buildJsonObject { put("chapterId", JsonPrimitive(chapterId)) },
            buildJsonObject { put("sef", JsonPrimitive(chapterSlug)) },
        )
        for (input in inputVariants) {
            val payload = runCatching { trpcCall(procedure, input) }.getOrNull() ?: continue
            val urls = extractImageUrls(payload)
            if (urls.isNotEmpty()) return urls
        }
        return emptyList()
    }

    private fun extractImageUrls(payload: JsonElement?): List<String> = when (payload) {
        is JsonArray -> payload.mapNotNull { imageUrlFromJson(it) }

        is JsonObject -> {
            for (key in listOf("images", "pages", "files")) {
                val arr = payload[key] as? JsonArray ?: continue
                val urls = arr.mapNotNull { imageUrlFromJson(it) }
                if (urls.isNotEmpty()) return urls
            }
            payload.values.filter { it is JsonObject || it is JsonArray }
                .flatMap { extractImageUrls(it) }
        }

        else -> emptyList()
    }

    private fun imageUrlFromJson(item: JsonElement): String? = when (item) {
        is JsonObject -> listOf("url", "src", "image", "path", "file")
            .firstNotNullOfOrNull { item[it]?.contentOrNullOrNull()?.takeIf { s -> s.startsWith("http") || s.startsWith("/") } }
            ?.let(::absolute)

        is JsonPrimitive -> item.contentOrNull?.takeIf { it.startsWith("http") || it.startsWith("/") }?.let(::absolute)

        else -> null
    }

    // ============================== Helpers ==============================

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.contentOrNullOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun SeriesItemDto.toSManga(): SManga = SManga.create().apply {
        val slugPath = href?.trimStart('/') ?: "seriler/$slug"
        url = if (slugPath.startsWith("seriler/")) "/$slugPath" else "/seriler/${slug ?: slugPath.substringAfterLast('/')}"
        title = name.orEmpty()
        thumbnail_url = thumb?.let(::absolute)
    }

    private fun ChapterItemDto.toSChapter(seriesSlug: String): SChapter? {
        val id = idString ?: return null
        val num = numberValue ?: 0f
        val chSlug = slug?.ifBlank { null }
            ?: href?.substringAfterLast('/')?.ifBlank { null }
            ?: slugifyChapter(num, name)
        return SChapter.create().apply {
            url = "/reader/$seriesSlug/$chSlug/$id"
            name = name?.trim()?.ifBlank { null } ?: "Bölüm ${num.trimNumber()}"
            chapter_number = num
            date_upload = listOfNotNull(createdAt, date).firstNotNullOfOrNull { raw ->
                runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
                    ?: runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(raw.substringBefore('T'))?.time }.getOrNull()
            } ?: 0L
        }
    }

    private fun slugifyChapter(no: Float, title: String?): String {
        val base = buildString {
            append(no.trimNumber())
            if (!title.isNullOrBlank()) append(" - ").append(title.trim())
        }
        return base.lowercase(Locale("tr"))
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "bolum-${no.trimNumber()}" }
    }

    private fun Float.trimNumber(): String = if (this == this.toLong().toFloat()) this.toLong().toString() else this.toString()

    private class SortFilter(state: Int = 0) :
        Filter.Select<String>(
            "Sırala",
            arrayOf("Popüler", "Güncel", "Yeni", "İsim (A-Z)", "İsim (Z-A)"),
            state,
        ) {
        val selected: String
            get() = when (state) {
                0 -> "-views"
                1 -> "-updatedAt"
                2 -> "-id"
                3 -> "name"
                else -> "-name"
            }
    }

    private class CommonHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .header("Referer", "https://sadscans.net/")
                .header("Accept", "application/json, text/html, */*")
                .build()
            return chain.proceed(request)
        }
    }

    companion object {
        private const val PAGE_SIZE = 15
        private const val CHAPTER_PAGE_LIMIT = 50
    }
}
