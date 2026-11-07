package eu.kanade.tachiyomi.extension.tr.sadscans

import android.util.Log
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" manga platformu.
 *
 * Kanıtlanmış site sözleşmesi (canlı JS parçaları `1vy0fm3wxax8_.js` ve `22yc01h34_ye1.js`):
 *  - Liste/arama: `GET /api/trpc/_s4d7g2k9m0.getSeries?batch=1&input={"0":{"json":{...}}}`
 *    girdi: `{search?, type?, status?, sortBy, turIds?, page, limit:15}`
 *    çıktı: `[{id,href,thumb,name,status,views,ratingAverage,ratingCount,type}]`
 *  - Türler: `tur.getAll`
 *  - Detay + bölümler: `_n3p6q1v8x5.getSeriesData` girdi `{sef,page,limit:10}`
 *    çıktı: `{series,chapters:[{chap_id,name,no,...}],pagination:{totalPages}}`
 *  - Okuyucu: `/reader/{series}/{chapterSlug}/{chapterId}`
 *  - sortBy değerleri: `name`, `-name`, `views`, `-views`, `rating`, `-rating`
 *  - status değerleri: `devam-ediyor`, `tamamlandi`, `birakildi`
 *
 * `/api/trpc` yolu Cloudflare tarafından korunuyor (düz HTTP istemcilerine 403 "Attention
 * Required"). Bu yüzden doğrudan API yalnızca **fırsat** olarak denenir; başarısız olursa ya da
 * boş dönerse sorgu, sitenin kendi tarayıcı oturumunda (WebView) aynı kaynaklı `fetch` ile ve son
 * olarak sitenin gerçek arayüzü sürülerek yürütülür. Hata hiçbir zaman sessizce yutulmaz.
 */
@Source
abstract class Sadscans : KeiSource() {

    /**
     * Bulut sağlayıcı IP'lerinde Cloudflare her isteği engelliyor; ev/ mobil bağlantıda ise
     * bot puanını belirleyen şey başlık setidir. Bu yüzden istekler gerçek bir Android Chrome
     * tarayıcısı gibi görünür (çalışan MangaTR kaynağındaki setin aynısı).
     */
    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        val webViewUa = webViewUserAgent
        if (webViewUa != null) {
            // Cloudflare `cf_clearance` çerezi UA'ya bağlıdır; doğrulamayı geçen WebView'in
            // UA'sı birebir kullanılmalı. Client hint'ler o UA ile uyuşmayacağı için eklenmez.
            set("User-Agent", webViewUa)
        } else {
            set("User-Agent", USER_AGENT)
            set("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"")
            set("Sec-Ch-Ua-Mobile", "?1")
            set("Sec-Ch-Ua-Platform", "\"Android\"")
        }
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(CommonHeadersInterceptor())
    }

    /**
     * CloudflareInterceptor **kaldırılmaz**: `/api/trpc` engellendiğinde WebView tabanlı çözümün
     * ürettiği `cf_clearance` çerezini kullanabilmek için istemcide kalması gerekir.
     *
     * Kitsugi (ve Mihon) her kaynak aramasını **25 saniyede** kesiyor
     * (`MangaSourceRepository` → `withTimeoutOrNull(25_000)`, keşfet ekranında 30 s).
     * Bu yüzden API denemesi kısa tutulur: API engelliyse vakit WebView kademesine kalır.
     */
    private val apiClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    /** SEO/sitemap istekleri: statik rota, JS ve Cloudflare challenge'ı gerektirmez. Kısa bütçe. */
    private val seoClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(3, TimeUnit.SECONDS)
            .build()
    }

    private data class SitemapEntry(val slug: String, val title: String)

    @Volatile
    private var sitemapCache: Pair<Long, List<SitemapEntry>>? = null

    /** Sitemap ulaşılamadıysa 10 dk boyunca yeniden denenmez (her aramada 3 sn kaybetmemek için). */
    @Volatile
    private var sitemapFailedAt: Long = 0L

    private val locRegex = Regex("<loc>\\s*([^<\\s]+)\\s*</loc>", RegexOption.IGNORE_CASE)

    /**
     * Cloudflare `cf_clearance` çerezini üreten WebView'in User-Agent'ı.
     * Çerez bu UA'ya bağlıdır: farklı UA ile API çağrısı 403 döner.
     */
    @Volatile
    private var webViewUserAgent: String? = null

    private val apiHeaders: Headers
        get() = Headers.Builder().apply {
            val ua = webViewUserAgent
            if (ua != null) {
                // WebView UA'sı ile birebir aynı olmalı; client hint'ler farklı olduğundan eklenmez.
                add("User-Agent", ua)
            } else {
                add("User-Agent", USER_AGENT)
                add("Sec-Ch-Ua", "\"Chromium\";v=\"131\", \"Google Chrome\";v=\"131\", \"Not_A Brand\";v=\"24\"")
                add("Sec-Ch-Ua-Mobile", "?1")
                add("Sec-Ch-Ua-Platform", "\"Android\"")
            }
            add("Accept", "application/json, text/plain, */*")
            add("Referer", "$baseUrl/seriler")
        }.build()

    private val trpcJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private data class ListCache(
        val query: String,
        val sort: String,
        val type: String?,
        val status: String?,
        val page: Int,
        val items: List<SManga>,
        val hasMore: Boolean,
        val at: Long,
    ) {
        fun fresh() = System.currentTimeMillis() - at < CACHE_TTL_MS
    }

    private var listCache: ListCache? = null

    private data class ChapterCache(
        val series: JsonObject?,
        val chapters: List<ChapterItemDto>,
        val at: Long,
    ) {
        fun fresh() = System.currentTimeMillis() - at < CHAPTER_CACHE_TTL_MS
    }

    private val chapterCache = mutableMapOf<String, ChapterCache>()

    // ============================== tRPC ==============================

    private suspend fun trpcPayload(procedure: String, input: JsonElement?): JsonElement {
        val envelope = buildJsonObject {
            put("0", buildJsonObject { put("json", input ?: JsonNull) })
        }.toString()
        val url = "$baseUrl/api/trpc/$procedure".toHttpUrl().newBuilder()
            .addQueryParameter("batch", "1")
            .addQueryParameter("input", envelope)
            .build()

        val body = apiClient.get(url, apiHeaders, ensureSuccess = false).use { response ->
            val text = response.body.string()
            when {
                !response.isSuccessful -> throw IOException("SadScans API HTTP ${response.code}")
                text.trimStart().startsWith("<") -> throw IOException("SadScans API koruması (HTML yanıt)")
                text.isBlank() -> throw IOException("SadScans API boş yanıt")
                else -> text
            }
        }

        val batch = trpcJson.decodeFromString<List<TrpcBatchItem>>(body)
        val entry = batch.firstOrNull() ?: throw IOException("SadScans tRPC yanıtı boş")
        entry.error?.let { throw IOException("SadScans tRPC hatası: ${it.message()}") }
        return entry.result?.data?.json ?: throw IOException("SadScans tRPC verisi boş")
    }

    private suspend fun trpcObject(procedure: String, input: JsonElement): JsonObject = trpcPayload(procedure, input) as? JsonObject
        ?: throw IOException("SadScans tRPC nesnesi bekleniyordu: $procedure")

    private fun seriesInput(slug: String, page: Int): JsonObject = buildJsonObject {
        put("sef", JsonPrimitive(slug))
        put("page", JsonPrimitive(page))
        put("limit", JsonPrimitive(CHAPTER_PAGE_LIMIT))
    }


    /** Cloudflare doğrulaması bu oturumda geçilmiş mi? (cf_clearance çerezi paylaşılan kavanozda) */
    private fun hasClearance(): Boolean = runCatching {
        client.cookieJar.loadForRequest("$baseUrl/".toHttpUrl())
            .any { it.name == "cf_clearance" && it.value.isNotBlank() }
    }.getOrDefault(false)

    /** WebView başarılı bir sayfa döndürdüyse UA'sını sakla; sonraki API çağrıları onunla gider. */
    private fun rememberUserAgent(result: JsonObject) {
        result["ua"].stringOrNull()?.takeIf { it.isNotBlank() }?.let { webViewUserAgent = it }
    }

    // ============================== Liste / arama ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = fetchSeries(page, "views", null, null, null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchSeries(page, "name", null, null, null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "name"
        val type = filters.firstInstanceOrNull<TypeFilter>()?.selected?.takeIf { it.isNotBlank() }
        val status = filters.firstInstanceOrNull<StatusFilter>()?.selected?.takeIf { it.isNotBlank() }
        return fetchSeries(page, sort, query.trim().ifBlank { null }, type, status)
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SortFilter(), TypeFilter(), StatusFilter())

    private suspend fun fetchSeries(
        page: Int,
        sort: String,
        query: String?,
        type: String?,
        status: String?,
    ): MangasPage {
        listCache?.let { cached ->
            val same = cached.page == page && cached.query == query.orEmpty() && cached.sort == sort &&
                cached.type == type && cached.status == status
            if (same && cached.fresh()) return MangasPage(cached.items, cached.hasMore)
        }

        // tRPC kademesi yalnızca Cloudflare doğrulaması geçilmişse denenir. Aksi hâlde ilk istek
        // challenge'a takılır: uygulamanın CloudflareInterceptor'ı WebView açar, 25 sn'lik arama
        // bütçesi biter. Çerez yoksa doğrudan WebView kademesine gidilir (çerez oradan kazanılır).
        val direct = if (hasClearance()) {
            attempt {
                val normalized = query?.let { normalizeSearch(it) }
                val first = directSeries(page, sort, normalized, type, status)
                if (first.isNotEmpty()) return@attempt first
                if (!query.isNullOrBlank() && normalized != query) {
                    directSeries(page, sort, query, type, status)
                } else {
                    first
                }
            }
        } else {
            null
        }
        if (!direct.isNullOrEmpty()) {
            val hasMore = direct.size >= PAGE_SIZE
            listCache = ListCache(query.orEmpty(), sort, type, status, page, direct, hasMore, System.currentTimeMillis())
            return MangasPage(direct, hasMore)
        }

        // Kademe 2: sitemap (statik SEO rotası — Cloudflare/JS gerektirmez, ~300 ms).
        // tRPC engelliyse arama yine de sonuç döndürür; detay sayfası WebView ile zenginleşir.
        if (page == 1 && !query.isNullOrBlank()) {
            val fromSitemap = attempt { sitemapSearch(query) }
            if (!fromSitemap.isNullOrEmpty()) {
                listCache = ListCache(query.orEmpty(), sort, type, status, page, fromSitemap, false, System.currentTimeMillis())
                return MangasPage(fromSitemap, false)
            }
        }

        val result = frontendPage(
            "$baseUrl/seriler",
            SadscansFrontend.listScript(query.orEmpty(), sort, type, status, page),
            timeout = 18.seconds,
        )
        rememberUserAgent(result)
        val rows = result["items"] as? JsonArray ?: throw IOException("SadScans arama sonuçları okunamadı.")
        val loaded = parseSeriesItems(rows)
        val fromDom = result["source"].stringOrNull() == "dom"
        val items = if (fromDom) loaded.drop((page - 1) * PAGE_SIZE).take(PAGE_SIZE) else loaded
        val explicitMore = result["hasMore"].stringOrNull()?.toBooleanStrictOrNull()
        val hasMore = explicitMore ?: (items.size >= PAGE_SIZE)
        if (items.isNotEmpty() || !hasMore) {
            listCache = ListCache(query.orEmpty(), sort, type, status, page, items, hasMore, System.currentTimeMillis())
        }
        return MangasPage(items, hasMore)
    }

    /**
     * Arama için JS'siz kademe: /sitemap.xml içindeki /seriler/<slug> adreslerini slug→başlık
     * dönüşümüyle sorguya eşler. Site istemci tarafında render edildiği ve API Cloudflare
     * arkasında olduğu için, engellenen ağlarda bile sonuç üretebilen tek hafif yol budur.
     */
    private suspend fun sitemapSearch(query: String): List<SManga> = withContext(Dispatchers.IO) {
        val key = slugKey(query)
        if (key.length < 3) return@withContext emptyList()
        val entries = sitemapEntries()
        if (entries.isEmpty()) return@withContext emptyList()
        val hits = entries.mapNotNull { entry ->
            val titleKey = slugKey(entry.title)
            val score = when {
                titleKey == key -> 0
                titleKey.startsWith(key) -> 1
                titleKey.contains(key) -> 2
                else -> return@mapNotNull null
            }
            Triple(score, entry, titleKey.length)
        }.sortedWith(compareBy({ it.first }, { it.third }, { it.second.title.length }))

        hits.take(PAGE_SIZE).map { (_, entry, _) ->
            SManga.create().apply {
                url = "/seriler/${entry.slug}"
                title = entry.title
            }
        }
    }

    private suspend fun sitemapEntries(): List<SitemapEntry> {
        val now = System.currentTimeMillis()
        sitemapCache?.let { (at, cached) ->
            if (now - at < SITEMAP_TTL_MS) return cached
        }
        if (now - sitemapFailedAt < SITEMAP_RETRY_MS) return emptyList()
        val root = fetchSitemapText("$baseUrl/sitemap.xml") ?: run {
            sitemapFailedAt = now
            return emptyList()
        }
        val children = locRegex.findAll(root).map { it.groupValues[1] }
            .filter { it.endsWith(".xml", ignoreCase = true) }
            .take(4).toList()
        val documents = if (children.isEmpty()) listOf(root) else children.mapNotNull { fetchSitemapText(it) }
        val found = LinkedHashMap<String, SitemapEntry>()
        for (document in documents) {
            for (match in locRegex.findAll(document)) {
                val url = match.groupValues[1]
                val index = url.indexOf("/seriler/", ignoreCase = true)
                if (index < 0) continue
                val slug = url.substring(index + "/seriler/".length)
                    .substringBefore('?').substringBefore('#').trim('/').substringBefore('/')
                if (slug.isBlank()) continue
                found.putIfAbsent(slug, SitemapEntry(slug, slugToTitle(slug)))
            }
        }
        val list = found.values.toList()
        if (list.isNotEmpty()) sitemapCache = System.currentTimeMillis() to list
        return list
    }

    private suspend fun fetchSitemapText(url: String): String? = try {
        seoClient.get(url.toHttpUrl(), headers, ensureSuccess = false).use { response ->
            if (!response.isSuccessful) {
                null
            } else {
                response.body.string().takeIf { it.isNotBlank() && it.contains("<loc", ignoreCase = true) }
            }
        }
    } catch (_: Throwable) {
        null
    }

    private fun slugToTitle(slug: String): String = slug.split('-', '_')
        .filter { it.isNotBlank() }
        .joinToString(" ") { word ->
            if (word.length <= 1) {
                word.uppercase()
            } else {
                word[0].uppercase() + word.substring(1)
            }
        }

    private fun slugKey(value: String): String {
        val lowered = value.lowercase()
            .replace('ı', 'i').replace('İ', 'i')
            .replace('ş', 's').replace('ğ', 'g').replace('ç', 'c')
            .replace('ö', 'o').replace('ü', 'u')
        return Normalizer.normalize(lowered, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9]+"), "")
    }

    private suspend fun directSeries(
        page: Int,
        sort: String,
        query: String?,
        type: String?,
        status: String?,
    ): List<SManga> {
        val input = buildJsonObject {
            put("page", JsonPrimitive(page))
            put("limit", JsonPrimitive(PAGE_SIZE))
            put("sortBy", JsonPrimitive(sort))
            if (!query.isNullOrBlank()) put("search", JsonPrimitive(query))
            if (type != null) put("type", JsonPrimitive(type))
            if (status != null) put("status", JsonPrimitive(status))
        }
        val array = trpcPayload("$SERIES_ROUTER.getSeries", input) as? JsonArray ?: return emptyList()
        return parseSeriesItems(array)
    }

    private fun parseSeriesItems(items: JsonArray): List<SManga> = items.mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val href = item["href"].stringOrNull()?.takeIf { it.isNotBlank() }
            ?: item["slug"].stringOrNull()?.takeIf { it.isNotBlank() }?.let { "/seriler/$it" }
        val name = item["name"].stringOrNull()?.trim()?.takeIf { it.isNotBlank() }
        if (href == null || name == null) return@mapNotNull null
        SManga.create().apply {
            url = if (href.startsWith("/")) href else "/$href"
            title = name
            thumbnail_url = item["thumb"].stringOrNull()?.takeIf { it.isNotBlank() }?.let { absolute(it) }
        }
    }.distinctBy { it.url }

    // ============================== Detay + bölümler ==============================

    override fun getMangaUrl(manga: SManga): String = baseUrl + manga.url

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        if (url.pathSegments.firstOrNull() != "seriler") return null
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        return SManga.create().apply {
            this.url = "/seriler/$slug"
            title = slug.replace('-', ' ')
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val slug = manga.url.removePrefix("/seriler/").substringBefore('/').substringBefore('?')
        val data = fetchSeriesData(slug)
        val details = if (fetchDetails) buildDetails(manga, data.series) else manga
        val chapterList = if (fetchChapters) {
            data.chapters.mapNotNull { it.toSChapter() }.sortedByDescending { it.chapter_number }
        } else {
            chapters
        }
        return SMangaUpdate(details.apply { url = manga.url }, chapterList)
    }

    private suspend fun fetchSeriesData(slug: String): SeriesDataDto {
        chapterCache[slug]?.let { cached ->
            if (cached.fresh()) {
                return SeriesDataDto(
                    series = cached.series,
                    chapters = cached.chapters,
                    pagination = PaginationDto(totalPages = 1, total = cached.chapters.size),
                )
            }
        }
        val direct = if (hasClearance()) attempt { fetchAllViaApi(slug) } else null
        if (direct != null && direct.chapters.isNotEmpty()) {
            chapterCache[slug] = ChapterCache(direct.series, direct.chapters, System.currentTimeMillis())
            return direct
        }

        val result = frontendPage(
            "$baseUrl/seriler/$slug",
            SadscansFrontend.detailsScript(1),
            timeout = 18.seconds,
        ).also { rememberUserAgent(it) }
        val merged = result["merged"].stringOrNull() == "true"
        val dto = decodeFrontend(result)
        if (merged || dto.chapters.isEmpty()) return dto

        // Arayüz yedeği sayfa sayfa ilerler.
        val total = dto.pagination?.totalPages ?: 1
        val chapters = dto.chapters.toMutableList()
        var page = 2
        while (page <= total && page <= MAX_DOM_CHAPTER_PAGES) {
            val next = attempt {
                decodeFrontend(
                    frontendPage(
                        "$baseUrl/seriler/$slug",
                        SadscansFrontend.detailsScript(page),
                        timeout = 12.seconds,
                    ).also { rememberUserAgent(it) },
                )
            } ?: break
            if (next.chapters.isEmpty()) break
            chapters += next.chapters
            page++
        }
        val unique = chapters.distinctBy { it.idString ?: it.href ?: it.name }
        chapterCache[slug] = ChapterCache(dto.series, unique, System.currentTimeMillis())
        return SeriesDataDto(
            series = dto.series,
            chapters = unique,
            pagination = PaginationDto(totalPages = 1, total = unique.size),
        )
    }

    private suspend fun fetchAllViaApi(slug: String): SeriesDataDto {
        val first = trpcJson.decodeFromJsonElement(
            SeriesDataDto.serializer(),
            trpcObject("$DETAIL_ROUTER.getSeriesData", seriesInput(slug, 1)),
        )
        if (first.chapters.isEmpty()) return first

        val chapters = first.chapters.toMutableList()
        var total = first.pagination?.totalPages ?: 1
        var page = 2
        while (page <= total && page <= MAX_CHAPTER_PAGES) {
            val next = attempt {
                trpcJson.decodeFromJsonElement(
                    SeriesDataDto.serializer(),
                    trpcObject("$DETAIL_ROUTER.getSeriesData", seriesInput(slug, page)),
                )
            } ?: break
            chapters += next.chapters
            total = next.pagination?.totalPages ?: total
            page++
        }
        return SeriesDataDto(
            series = first.series,
            chapters = chapters,
            pagination = PaginationDto(totalPages = 1, total = chapters.size),
        )
    }

    private fun decodeFrontend(json: JsonObject): SeriesDataDto {
        val normalized = buildJsonObject {
            (json["series"] as? JsonObject)?.let { put("series", it) }
            put("chapters", json["chapters"] ?: JsonArray(emptyList()))
            (json["pagination"] as? JsonObject)?.let { put("pagination", it) }
        }
        return trpcJson.decodeFromJsonElement(SeriesDataDto.serializer(), normalized)
    }

    private fun buildDetails(manga: SManga, series: JsonObject?): SManga = SManga.create().apply {
        url = manga.url
        title = series.stringAny("name", "title") ?: manga.title
        thumbnail_url = series.stringAny("cover", "thumb", "image", "featuredImage", "featured_image")
            ?.let { absolute(it) } ?: manga.thumbnail_url
        description = series.stringAny("description", "desc", "content", "summary", "postContent")
            ?: manga.description
        author = listOfNotNull(
            series.stringAny("author"),
            series.stringAny("artist"),
        ).distinct().joinToString().ifBlank { manga.author.orEmpty() }.ifBlank { null }
        genre = parseGenres(series).joinToString().ifBlank { manga.genre }
        status = parseStatus(series.stringAny("status", "seriesStatus", "durum")).takeIf { it != SManga.UNKNOWN }
            ?: manga.status
    }

    private fun JsonObject?.stringAny(vararg keys: String): String? {
        val payload = this ?: return null
        return keys.firstNotNullOfOrNull { key -> payload[key].stringOrNull()?.trim()?.ifBlank { null } }
    }

    private fun parseGenres(series: JsonObject?): List<String> {
        if (series == null) return emptyList()
        val array = series["genres"] ?: series["turs"] ?: series["turler"] ?: series["tags"] ?: return emptyList()
        if (array !is JsonArray) return emptyList()
        return array.mapNotNull { element ->
            when (element) {
                is JsonObject -> element.stringAny("name", "title")
                else -> element.stringOrNull()?.trim()?.ifBlank { null }
            }
        }.distinct()
    }

    private fun parseStatus(status: String?): Int {
        if (status.isNullOrBlank()) return SManga.UNKNOWN
        val value = normalizeSearch(status)
        return when {
            value.contains("devam") -> SManga.ONGOING
            value.contains("tamamland") || value.contains("completed") -> SManga.COMPLETED
            value.contains("birak") || value.contains("dropped") || value.contains("cancel") -> SManga.CANCELLED
            value.contains("ara ver") || value.contains("hiatus") || value.contains("bekle") -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ============================== Okuyucu ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val chapterId = chapter.url.substringBefore('?').trimEnd('/').substringAfterLast('/')
        if (chapterId.isBlank()) throw IOException("SadScans bölüm kimliği okunamadı.")

        val result = frontendPage(
            chapterUrl,
            SadscansFrontend.readerScript(chapterId),
            timeout = 35.seconds,
        ).also { rememberUserAgent(it) }
        val urls = (result["urls"] as? JsonArray)
            ?.mapNotNull { it.stringOrNull()?.trim()?.ifBlank { null } }
            .orEmpty()
            .distinct()
        if (urls.isEmpty()) throw IOException("SadScans okuyucusunda bölüm görselleri bulunamadı.")
        return urls.mapIndexed { index, url -> Page(index, url = chapterUrl, imageUrl = url) }
    }

    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", page.url.takeIf { it.startsWith("$baseUrl/reader/") } ?: "$baseUrl/")
        .build()

    // ============================== WebView köprüsü ==============================

    private suspend fun frontendPage(
        url: String,
        script: String,
        timeout: Duration = 20.seconds,
    ): JsonObject {
        return try {
            runWebView(timeout = timeout) {
                onReceivedError { request, _ ->
                    if (request.isForMainFrame) reject(IOException("SadScans sitesine bağlanılamadı: $url"))
                }
                poll(1.seconds) {
                    evaluateJs(script) { value ->
                        val decoded = try {
                            value.parseAs<String?>()
                        } catch (_: Throwable) {
                            null
                        }
                        if (decoded.isNullOrBlank()) return@evaluateJs
                        val parsed = try {
                            trpcJson.parseToJsonElement(decoded) as? JsonObject
                        } catch (_: Throwable) {
                            null
                        }
                        if (parsed == null) return@evaluateJs
                        val message = parsed["error"].stringOrNull()
                        if (message != null) reject(IOException(message)) else resolve(parsed)
                    }
                }
                loadUrl(url)
            }
        } catch (e: WebViewTimeoutException) {
            throw IOException("SadScans site arayüzü zamanında yanıt vermedi ($url). Siteyi uygulama içi tarayıcıda açıp Cloudflare kontrolünü geçtikten sonra tekrar deneyin.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Throwable) {
            throw IOException("SadScans site arayüzü hatası: ${e.message ?: e.javaClass.simpleName}", e)
        }
    }

    // ============================== Yardımcılar ==============================

    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Doğrudan API denemesi başarısız: ${e.message}")
        null
    }

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

    private fun normalizeSearch(value: String): String = buildString {
        value.forEach { character ->
            append(
                when (character) {
                    'İ', 'I', 'ı' -> 'i'
                    'Ş', 'ş' -> 's'
                    'Ğ', 'ğ' -> 'g'
                    'Ü', 'ü' -> 'u'
                    'Ö', 'ö' -> 'o'
                    'Ç', 'ç' -> 'c'
                    else -> character
                },
            )
        }
    }.lowercase(Locale.ROOT)

    private fun absolute(path: String): String = when {
        path.startsWith("http://") || path.startsWith("https://") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun ChapterItemDto.toSChapter(): SChapter? {
        val id = idString ?: return null
        val number = numberValue ?: parseChapterNumber(name)
        val readerPath = href?.let { raw ->
            runCatching { absolute(raw).toHttpUrl().encodedPath }.getOrNull()
        }?.takeIf { it.contains("/reader/") }
        val label = name?.trim()?.ifBlank { null }
        val displayName = when {
            label == null -> "Bölüm ${number.trimNumber()}"
            label.contains("bölüm", ignoreCase = true) -> label
            else -> "${number.trimNumber()}. Bölüm - $label"
        }
        return SChapter.create().apply {
            url = readerPath ?: "/reader/$id"
            name = displayName
            chapter_number = number
            date_upload = parseDate(createdAt ?: date)
        }
    }

    private fun parseChapterNumber(name: String?): Float {
        if (name == null) return 0f
        return Regex("([0-9]+(?:[.,][0-9]+)?)").find(name)?.groupValues?.get(1)
            ?.replace(',', '.')?.toFloatOrNull() ?: 0f
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilliseconds() }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(raw.substringBefore('T'))?.time }.getOrNull()
            ?: 0L
    }

    private fun Float.trimNumber(): String = if (this == this.toLong().toFloat()) this.toLong().toString() else toString()

    private class CommonHeadersInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder()
                .apply {
                    if (chain.request().header("Referer") == null) header("Referer", "$BASE_URL/")
                    if (chain.request().header("Accept") == null) header("Accept", "application/json, text/html, */*")
                }
                .build()
            return chain.proceed(request)
        }
    }

    companion object {
        private const val LOG_TAG = "SadScans"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
        private const val BASE_URL = "https://sadscans.net"
        private const val SERIES_ROUTER = "_s4d7g2k9m0"
        private const val DETAIL_ROUTER = "_n3p6q1v8x5"
        private const val PAGE_SIZE = 15
        private const val SITEMAP_TTL_MS = 6 * 60 * 60 * 1000L
        private const val SITEMAP_RETRY_MS = 10 * 60 * 1000L
        private const val CHAPTER_PAGE_LIMIT = 10
        private const val MAX_CHAPTER_PAGES = 200
        private const val MAX_DOM_CHAPTER_PAGES = 10
        private const val CACHE_TTL_MS = 3 * 60 * 1000L
        private const val CHAPTER_CACHE_TTL_MS = 10 * 60 * 1000L
    }
}
