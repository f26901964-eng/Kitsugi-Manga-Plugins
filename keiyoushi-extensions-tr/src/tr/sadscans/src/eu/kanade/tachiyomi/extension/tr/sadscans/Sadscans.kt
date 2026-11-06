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
import keiyoushi.utils.WebViewTimeoutException
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.runWebView
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
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" manga platformu.
 *
 * Güncel site yapısı (canlı siteden çıkarılan JS chunk'ları + HTML dökümleri):
 *  - Seri listesi / arama: `GET /api/trpc/_s4d7g2k9m0.getSeries?batch=1&input={"0":{"json":{"search":...,"page":...,"limit":15}}}`
 *    -> `[{result:{data:{json:[{id,href,thumb,name,status,views,ratingAverage,ratingCount,type}]}}}]`
 *  - Türler:               `GET /api/trpc/tur.getAll` -> `[{id,name,slug}]`
 *  - Seri detay + bölümler: `GET /api/trpc/_n3p6q1v8x5.getSeriesData?batch=1&input={"0":{"json":{"sef":<slug>,"page":1,"limit":10}}}`
 *    -> `{series:{...}, chapters:[{chap_id,name,no,...}], pagination:{totalPages,...}}`
 *  - Bölüm okuma: `/reader/{seriesSlug}/{chapterSlug}/{chapterId}` (kısa form `/reader/{chapterId}` de çalışır)
 *  - Görseller `/api/uploads/assets/series/{seriesId}/{chapterId}/images/...` altında; site Cloudflare arkasında.
 *
 * tRPC batch zarfı: `?batch=1&input=<urlencoded {"0":{"json":...}}>`, yanıt `[{result:{data:{json:...}}}]`.
 * Korunan liste çağrıları site arayüzü üzerinden yürütülür. Bölüm görselleri yalnızca
 * çalışan okuyucudaki img elemanlarından alınır; tahmini API adları denenmez.
 */
@Source
abstract class Sadscans : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(CommonHeadersInterceptor())
    }

    // Inspect the original API response without repeated host challenge retries.
    // A protected response uses the site's actual frontend, not a manual fetch.
    private val apiClient: OkHttpClient by lazy {
        client.newBuilder().apply {
            interceptors().removeAll { it.javaClass.simpleName == "CloudflareInterceptor" }
            callTimeout(10, TimeUnit.SECONDS)
        }.build()
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

    private suspend fun trpcCall(procedure: String, input: JsonElement?): JsonElement {
        val inputParam = buildJsonObject {
            put("0", buildJsonObject { put("json", input ?: JsonNull) })
        }.toString()
        val url = "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", inputParam)
            .build()
        val direct = try {
            apiClient.get(url, apiHeaders, ensureSuccess = false).use { response ->
                val body = response.body.string()
                when {
                    response.header("cf-mitigated") == "challenge" -> null

                    response.code in listOf(403, 429, 503) &&
                        (body.contains("cloudflare", ignoreCase = true) || body.contains("cf-chl-")) -> null

                    !response.isSuccessful -> throw IOException("SadScans API HTTP ${response.code}: $procedure")

                    else -> body
                }
            }
        } catch (_: java.net.SocketTimeoutException) {
            null
        }
        val body = direct ?: throw IOException("SadScans: doğrudan API erişimi engellendi.")
        val batch = trpcJson.decodeFromString<List<TrpcBatchItem>>(body)
        val payload = batch.firstOrNull()?.result?.data?.json
            ?: error("SadScans: tRPC yanıtı boş: $procedure")
        return payload
    }

    private suspend fun trpcCallObject(procedure: String, input: JsonElement?): JsonObject = trpcCall(procedure, input) as? JsonObject ?: error("SadScans: tRPC nesne bekleniyordu: $procedure")

    // ============================== Popular / Latest / Search ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(page, "-views", null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(page, "name", null)

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
        val items = try {
            trpcCall("$seriesListRouter.getSeries", input) as? JsonArray
                ?: throw IOException("SadScans: seri listesi dizi değil.")
        } catch (_: IOException) {
            // Let the site's frontend run its own request preparation/Worker path.
            // A manual fetch from WebView is not equivalent to a frontend request.
            val result = frontendPage("$baseUrl/seriler", SadscansFrontend.listScript(query.orEmpty(), sortBy, page))
            val rows = result["items"] as? JsonArray ?: throw IOException("SadScans: tarayıcı seri listesi eksik.")
            return MangasPage(parseSeriesItems(rows), result["hasMore"]?.jsonPrimitive?.content == "true")
        }
        return MangasPage(parseSeriesItems(items), items.size >= PAGE_SIZE)
    }

    private fun parseSeriesItems(items: JsonArray): List<SManga> = items.map { it.jsonObject }.mapNotNull { item ->
        val href = item["href"].stringOrNull() ?: item["slug"].stringOrNull()?.let { "/seriler/$it" }
        val name = item["name"].stringOrNull()
        if (href.isNullOrBlank() || name.isNullOrBlank()) return@mapNotNull null
        SeriesItemDto(href = href, name = name, thumb = item["thumb"].stringOrNull()).toSManga()
    }

    private suspend fun frontendPage(url: String, script: String): JsonObject = try {
        runWebView(timeout = 35.seconds) {
            onReceivedError { request, _ ->
                if (request.isForMainFrame) reject(IOException("SadScans site arayüzüne bağlanılamadı."))
            }
            poll(1.seconds) {
                evaluateJs(script) { value ->
                    val raw = value.parseAs<String?>() ?: return@evaluateJs
                    val result = trpcJson.parseToJsonElement(raw).jsonObject
                    val message = result["error"].stringOrNull()
                    if (message != null) reject(IOException(message)) else resolve(result)
                }
            }
            loadUrl(url)
        }
    } catch (_: WebViewTimeoutException) {
        throw IOException("SadScans site arayüzü zamanında veri üretemedi. WebView'de siteye erişimi kontrol edin.")
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
        val payload = try {
            trpcCallObject("$seriesDetailRouter.getSeriesData", input)
        } catch (_: IOException) {
            frontendPage("$baseUrl/seriler/$slug", SadscansFrontend.detailsScript(page))
        }
        return trpcJson.decodeFromJsonElement(SeriesDataDto.serializer(), payload)
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
        val chapterUrl = getChapterUrl(chapter)
        val chapterId = chapter.url.substringAfterLast('/').substringBefore('?')
        // Never probe undocumented procedure names or interpret JSON-LD thumbnail URLs
        // as a complete chapter. Only read images actually rendered by the reader.
        val result = frontendPage(chapterUrl, SadscansFrontend.readerScript(chapterId))
        val urls = (result["urls"] as? JsonArray)?.mapNotNull { it.stringOrNull() }.orEmpty()
        if (urls.isEmpty()) throw IOException("SadScans okuyucusunda bölüm görselleri bulunamadı.")
        return urls.mapIndexed { index, url -> Page(index, url = chapterUrl, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", page.url.takeIf { it.startsWith("$baseUrl/reader/") } ?: "$baseUrl/")
        .build()

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
        val readerPath = href?.let { runCatching { absolute(it).toHttpUrl().encodedPath }.getOrNull() }
            ?.takeIf { it.startsWith("/reader/") }
        val chSlug = slug?.ifBlank { null } ?: slugifyChapter(num, name)
        return SChapter.create().apply {
            url = readerPath ?: "/reader/$seriesSlug/$chSlug/$id"
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
                1 -> "name"
                2 -> "name"
                3 -> "name"
                else -> "-name"
            }
    }

    private class CommonHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .apply {
                    if (chain.request().header("Referer") == null) header("Referer", "https://sadscans.net/")
                    if (chain.request().header("Accept") == null) header("Accept", "application/json, text/html, */*")
                }
                .build()
            return chain.proceed(request)
        }
    }

    companion object {
        private const val PAGE_SIZE = 15
        private const val CHAPTER_PAGE_LIMIT = 10
    }
}
