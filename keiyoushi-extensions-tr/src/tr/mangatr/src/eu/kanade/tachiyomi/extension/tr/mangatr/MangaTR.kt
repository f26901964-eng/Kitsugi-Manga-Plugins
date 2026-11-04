package eu.kanade.tachiyomi.extension.tr.mangatr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Base64
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.JSON_MEDIA_TYPE
import keiyoushi.utils.asJsoup
import keiyoushi.utils.attrOrNull
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

@Source
abstract class MangaTR : KeiSource() {

    override fun Headers.Builder.configureHeaders() = add("Accept-Language", "en-US,en;q=0.5")

    override fun OkHttpClient.Builder.configureClient() = apply {
        addInterceptor(::verifyChallengeInterceptor)
        addInterceptor(::scrambledImageInterceptor)
        addInterceptor(DDoSGuardInterceptor(network.client))
        rateLimit(2)
    }

    private val ajaxHeaders get() = headersBuilder()
        .add("X-Requested-With", "XMLHttpRequest")
        .add("Accept-Encoding", "identity")
        .build()

    private var captchaUrl: String? = null

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage =
        getMangaList(page, "$baseUrl/manga-list-sayfala.html?sort=views&sort_type=DESC&page=$page&listType=pagination")

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        getMangaList(page, "$baseUrl/manga-list-sayfala.html?sort=last_update&sort_type=DESC&page=$page&listType=pagination")

    // ============================== Search ===============================

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isNotBlank()) {
            // New site uses view=sayfalama for paginated search; keep legacy page param as fallback
            val url = "$baseUrl/arama.html".toHttpUrl().newBuilder()
                .addQueryParameter("view", "sayfalama")
                .addQueryParameter("icerik", query)
                .addQueryParameter("page", page.toString())
                .build()
            val document = client.get(url).asJsoup()

            // Try Bento arama-card first, then old FMReader div.row a[data-toggle], then generic
            val mangasBento = document.select("div.arama-card")
                .filterNot { it.selectFirst(".arama-badge")?.text()?.lowercase(Locale.ROOT)?.isExcludedType() == true }
                .mapNotNull { el ->
                    val link = el.selectFirst("a.arama-card__title") ?: return@mapNotNull null
                    SManga.create().apply {
                        setUrlWithoutDomain(link.absUrl("href"))
                        title = link.text().trim()
                        thumbnail_url = el.selectFirst("img.arama-card__cover")?.absUrl("src")
                            ?: el.selectFirst("img")?.absUrl("src")
                    }
                }
            if (mangasBento.isNotEmpty()) {
                return MangasPage(mangasBento, document.hasNextPage(page))
            }

            val mangasFm = document.select("div.row a[data-toggle]")
                .filterNot { it.parent()?.selectFirst("a.anime-r, a.novel-r") != null }
                .mapNotNull { el ->
                    // In some snapshots the search card is inside div.row > a > img + title
                    val href = el.absUrl("href").ifBlank { return@mapNotNull null }
                    SManga.create().apply {
                        setUrlWithoutDomain(href)
                        title = el.text().trim().ifEmpty { el.attr("title") }
                        thumbnail_url = el.selectFirst("img")?.absUrl("src")
                    }
                }
            if (mangasFm.isNotEmpty()) {
                // FMReader search has no pagination on arama.html
                return MangasPage(mangasFm, false)
            }

            // Fallback: generic manga links on search page
            val generic = document.select("a[href*='/manga-'][href$='.html']")
                .filterNot { it.text().isNullOrBlank() && it.attr("href").isBlank() }
                .distinctBy { it.absUrl("href") }
                .mapNotNull { el ->
                    val href = el.absUrl("href")
                    if (href.isBlank()) return@mapNotNull null
                    SManga.create().apply {
                        setUrlWithoutDomain(href)
                        title = el.text().trim().ifEmpty { el.attr("title").trim() }.ifEmpty { href.substringAfter("manga-").substringBefore(".").replace("-", " ") }
                        thumbnail_url = el.selectFirst("img")?.absUrl("src") ?: el.parent()?.selectFirst("img")?.absUrl("src")
                    }
                }
            // Filter out duplicates that are clearly not manga (e.g. navigation)
            val filteredGeneric = generic.filter { it.title.length > 1 && !it.url.contains("arama.html") }
            if (filteredGeneric.isNotEmpty()) return MangasPage(filteredGeneric, document.hasNextPage(page))

            return MangasPage(emptyList(), false)
        }

        val url = "$baseUrl/manga-list-sayfala.html".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("listType", "pagination")

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.toUriPart())
                is SortDirectionFilter -> url.addQueryParameter("sort_type", filter.toUriPart())
                is GenreFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("tur", it) }
                is StatusFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("durum", it) }
                is TranslationStatusFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("ceviri", it) }
                is AgeFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("yas", it) }
                is ContentTypeFilter -> filter.toUriPart().takeIf { it.isNotEmpty() }?.let { url.addQueryParameter("icerik", it) }
                else -> {}
            }
        }

        // Special case: GenreFilter uses "tur" param, but some old links use genre[] - we keep tur
        return getMangaList(page, url.build())
    }

    private suspend fun getMangaList(page: Int, url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()

        // Support both Bento .media-card and old FMReader thumbnails
        var mangas = document.select(".media-card")
            .filterNot { it.selectFirst(".media-card__badge")?.text()?.lowercase(Locale.ROOT)?.isExcludedType() == true }
            .mapNotNull {
                val link = it.selectFirst("a.media-card__title")
                    ?: it.selectFirst("a[href*='/manga-']")
                    ?: return@mapNotNull null
                SManga.create().apply {
                    setUrlWithoutDomain(link.absUrl("href"))
                    title = link.text().trim().ifEmpty { it.selectFirst("img")?.attr("alt") ?: "" }
                    thumbnail_url = it.selectFirst("img.media-card__cover")?.absUrl("src")
                        ?: it.selectFirst("img")?.absUrl("src")
                }
            }

        if (mangas.isEmpty()) {
            // Fallback for FMReader layout: div.col-md-12 > span.thumbnail
            mangas = document.select("div.col-md-12 > span.thumbnail, div.thumbnail, article.media-card")
                .mapNotNull { el ->
                    val link = el.selectFirst("a.pull-left") ?: el.selectFirst("a[href*='/manga-']") ?: return@mapNotNull null
                    // Filter out novel/anime badges
                    if (el.selectFirst("a.anime-r, a.novel-r") != null) return@mapNotNull null
                    val title = el.selectFirst("h3.media-heading a")?.text()?.trim()
                        ?: link.attr("title").trim()
                        ?: el.selectFirst("a.media-card__title")?.text()?.trim()
                        ?: return@mapNotNull null
                    SManga.create().apply {
                        setUrlWithoutDomain(link.absUrl("href"))
                        this.title = title
                        thumbnail_url = link.selectFirst("img.media-object")?.absUrl("src")
                            ?: link.selectFirst("img")?.absUrl("src")
                            ?: el.selectFirst("img")?.absUrl("src")
                    }
                }
        }

        if (mangas.isEmpty()) {
            // Ultimate fallback: any manga- link in list page, deduplicated
            val generic = document.select("a[href*='/manga-'][href$='.html']")
                .distinctBy { it.absUrl("href") }
                .mapNotNull { link ->
                    val href = link.absUrl("href")
                    if (href.isBlank() || href.contains("arama") || href.contains("manga-list")) return@mapNotNull null
                    val title = link.text().trim().ifEmpty { link.attr("title").trim() }
                    if (title.length < 2) return@mapNotNull null
                    SManga.create().apply {
                        setUrlWithoutDomain(href)
                        this.title = title
                        thumbnail_url = link.selectFirst("img")?.absUrl("src")
                    }
                }
            // Deduplicate by url and keep first occurrence
            val dedup = generic.distinctBy { it.url }
            if (dedup.isNotEmpty()) mangas = dedup
        }

        // More robust next page detection: arrows, numbered links, or text
        val hasNext = document.hasNextPage(page)
        return MangasPage(mangas, hasNext)
    }

    private suspend fun getMangaList(page: Int, url: String) = getMangaList(page, url.toHttpUrl())

    private fun String.isExcludedType() = contains("novel") || contains("anime")

    private fun Document.hasNextPage(currentPage: Int): Boolean {
        // Strategy 1: Look for explicit next page number links
        val numbered = select("a[href*=page=]").mapNotNull {
            it.absUrl("href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
        }.any { it > currentPage }
        if (numbered) return true

        // Strategy 2: Look for arrows: » or › where href page > current
        val arrowLinks = select("a, button").filter { it.text().trim() in setOf("›", "»", "Sonraki", "Next", "İleri") }
        for (a in arrowLinks) {
            val href = a.absUrl("href")
            if (href.isNotBlank()) {
                val p = href.toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                if (p != null && p > currentPage) return true
                // If href exists but no page param, assume next exists
                if (p == null && href.contains("page")) return true
            } else {
                // Button without href but indicates pagination (FMReader)
                if (a.text().trim() == "›" || a.text().trim() == "»") {
                    // Check if not disabled
                    if (!a.hasClass("disabled") && !a.attr("disabled").equals("true", true)) return true
                }
            }
        }

        // Strategy 3: Check pagination container for current page indicator + next sibling
        val bentoPagination = selectFirst("ul.pagination, div.pagination, div.btn-group")
        if (bentoPagination != null) {
            val active = bentoPagination.selectFirst(".active, .btn-info, [aria-current=page]")
            if (active != null) {
                // If active is not the last pagination element, next exists
                val allPages = bentoPagination.select("a, button").map { it.text().trim() }
                val activeText = active.text().trim()
                val idx = allPages.indexOf(activeText)
                if (idx >= 0 && idx < allPages.size - 1) return true
            }
        }

        // Strategy 4: Count items: if page returned reasonable count, assume more pages may exist
        // We already handled explicit links; otherwise default to false for safety
        return false
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        SortDirectionFilter(),
        GenreFilter(),
        StatusFilter(),
        TranslationStatusFilter(),
        AgeFilter(),
        ContentTypeFilter(),
    )

    // ============================== Details ==============================

    override fun getMangaUrl(manga: SManga): String = captchaUrl?.also { captchaUrl = null } ?: super.getMangaUrl(manga)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host && url.host != "www.${baseUrl.toHttpUrl().host}") return null
        val path = url.pathSegments.singleOrNull() ?: return null
        if (!path.startsWith("manga-") || !path.endsWith(".html")) return null

        val document = client.get("$baseUrl/$path").asJsoup()
        return document.mangaDetails().apply { this.url = "/$path" }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(baseUrl + manga.url).asJsoup()
        val details = document.mangaDetails()
        val chapterList = if (fetchChapters) getChapterList(document, manga) else chapters
        return SMangaUpdate(details, chapterList)
    }

    private fun Document.mangaDetails(): SManga = SManga.create().apply {
        // Try Bento selectors first, then FMReader fallbacks
        val bentoTitle = selectFirst("h1.bento-hero-title")?.text()?.trim()
        val fmTitle = selectFirst("h1")?.text()?.trim()
        val rawTitle = bentoTitle ?: fmTitle ?: ""
        // Strip trailing year like "Chainsaw Man (2018)" -> "Chainsaw Man"
        title = rawTitle.replace(TRAILING_YEAR_REGEX, "").trim()

        thumbnail_url = selectFirst(".poster-card__image")?.absUrl("src")
            ?: selectFirst("img[src*='image.mangatr.site']")?.absUrl("src")
            ?: selectFirst("img[title]")?.absUrl("src")
            ?: selectFirst(".manga-cover img, .poster img")?.absUrl("src")

        val bentoAlt = selectFirst("h1.bento-hero-title + div:not(.bento-hero-meta)")?.textOrNull()
        val fmAlt = selectFirst("div.info-other")?.textOrNull()
        val altNames = bentoAlt ?: fmAlt

        val bentoDesc = selectFirst("#manga-desc-content")?.wholeText()?.trim()
        val fmDesc = selectFirst("div.info-desc, div#tab1 p, div.summary")?.text()?.trim()
        val desc = bentoDesc ?: fmDesc

        description = buildString {
            if (!desc.isNullOrBlank()) append(desc)
            if (!altNames.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif İsimler: ").append(altNames.trim())
            }
        }.ifBlank { null }

        // Author / Artist: Try Bento infoRow, then FMReader meta
        author = infoRow("Yazar")
            ?: selectFirst("div.manga-meta-item:contains(Yazar) .manga-meta-value a[href*='author=']")?.text()?.trim()
            ?: selectFirst("div.manga-meta-item:contains(Yazar) .manga-meta-value")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: selectFirst("a[href*='?author='], a[href*='author=']")?.text()?.trim()

        artist = infoRow("Sanatçı")
            ?: selectFirst("div.manga-meta-item:contains(Sanatçı) .manga-meta-value a[href*='artist=']")?.let { it.text().trim() }
                ?.ifBlank { null }
            ?: selectFirst("div.manga-meta-item:contains(Sanatçı) .manga-meta-value")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: select("a[href*='?artist='], a[href*='artist=']").joinToString { it.text().trim() }.ifBlank { null }

        genre = select(".bento-hero-genres a").joinToString { it.text().trim() }.ifBlank {
            select("div.manga-meta-item:contains(Tür) a[href*='?tur='], div.manga-meta-value a[href*='?tur=']").joinToString { it.text().trim() }
                .ifBlank { select("a[href*='?tur=']").joinToString { it.text().trim() } }
        }

        // Status: Try Bento Durum, then FMReader translate badge, then generic
        val statusBento = infoRow("Durum")?.lowercase(Locale.ROOT)
        val translateLabel = selectFirst("div.manga-meta-item:contains(Çeviri Durumu) .manga-meta-value a[href*='ceviri=']")
            ?.text()?.trim().orEmpty().lowercase(Locale.ROOT)
        val statusText = statusBento ?: translateLabel.ifEmpty { null }

        status = when {
            statusText == null -> SManga.UNKNOWN
            statusText.contains("devam") -> SManga.ONGOING
            statusText.contains("tamamlan") -> SManga.COMPLETED
            statusText.contains("bırak") || statusText.contains("birak") || statusText.contains("iptal") -> SManga.CANCELLED
            statusText.contains("askı") || statusText.contains("askida") -> SManga.ON_HIATUS
            statusText.contains("tamam") -> SManga.COMPLETED
            else -> SManga.UNKNOWN
        }
        // Also check translateLabel separately for more accurate status
        if (status == SManga.UNKNOWN && translateLabel.isNotEmpty()) {
            status = when {
                translateLabel.contains("tamamlan") -> SManga.COMPLETED
                translateLabel.contains("bırak") || translateLabel.contains("birak") -> SManga.CANCELLED
                translateLabel.contains("askı") -> SManga.ON_HIATUS
                translateLabel.contains("devam") -> SManga.ONGOING
                else -> SManga.UNKNOWN
            }
        }
    }

    private fun Document.infoRow(label: String): String? =
        selectFirst(".bento-info-row:has(.bento-info-label:contains($label)) .bento-info-value")?.textOrNull()
            ?: selectFirst("div:has(> .bento-info-label:contains($label)) .bento-info-value")?.textOrNull()

    // ============================= Chapters ==============================

    private suspend fun getChapterList(document: Document, manga: SManga? = null): List<SChapter> {
        // Strategy 1: Bento listKey (multiple possible variable names)
        val html = document.html()
        val key = extractChapterKey(html, document)
        if (key != null) {
            val chapters = fetchBentoChapters(key)
            if (chapters.isNotEmpty()) return chapters
        }

        // Strategy 2: FMReader manga_cek via data-manga attribute or URL id
        val mangaIdFromAttr = document.selectFirst("#results")?.attr("data-manga")?.trim()
            ?: document.selectFirst("[data-manga]")?.attr("data-manga")?.trim()
            ?.takeIf { it.isNotEmpty() }

        val mangaIdFromUrl = manga?.url?.substringAfter("manga-")?.substringBefore(".")
            ?: document.location().substringAfter("manga-").substringBefore(".").substringBefore("?")
            ?.takeIf { it.isNotEmpty() && !it.contains("/") }

        val mangaId = mangaIdFromAttr ?: mangaIdFromUrl

        if (mangaId != null) {
            val fmChapters = fetchFmChapters(mangaId)
            if (fmChapters.isNotEmpty()) return fmChapters
        }

        // Strategy 3: Direct DOM parsing (if chapter list is already in HTML, e.g. no JS)
        val direct = parseDirectChapters(document)
        if (direct.isNotEmpty()) return direct

        // If all fail, throw informative error
        throw IOException("Bölüm listesi yüklenemedi. Site yapısı değişmiş olabilir; lütfen uygulamayı güncelleyin.")
    }

    private fun extractChapterKey(html: String, document: Document): String? {
        // Try multiple regex patterns for listKey
        val patterns = listOf(
            CHAPTER_KEY_REGEX,
            Regex("""initialChapterListKey\s*=\s*['"`]([^'"`]+)['"`]"""),
            Regex("""window\.mtrChapterKeys\.listKey\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""mtrChapterKeys\s*:\s*\{[^}]*listKey\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""listKey\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""chapter_list_key\s*[:=]\s*['"]([^'"]+)['"]""")
        )
        for (regex in patterns) {
            val match = regex.find(html)
            if (match != null) {
                val value = match.groupValues.getOrNull(1)?.trim()
                if (!value.isNullOrEmpty() && value.length > 10) return value
            }
        }
        // Also try data attribute on #results
        document.selectFirst("#results")?.attr("data-list-key")?.takeIf { it.isNotEmpty() }?.let { return it }
        document.selectFirst("[data-list-key]")?.attr("data-list-key")?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private suspend fun fetchBentoChapters(key: String): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var offset: Int? = null
        val seenBatches = mutableSetOf<List<String>>()
        var attempts = 0
        val maxAttempts = 30 // safety: max 30 pages (20 + 29*100 = 2920 chapters)

        while (attempts < maxAttempts) {
            attempts++
            val form = FormBody.Builder()
                .add("chapter_list_key", key)
                .apply { offset?.let { add("offset", it.toString()) } }
                .build()
            val response = try {
                client.post("$baseUrl/cek/fetch_pages_manga.php", ajaxHeaders, form)
            } catch (e: Exception) {
                break
            }
            val doc = response.asJsoup().also { it.setBaseUri("$baseUrl/") }

            // Try multiple selectors for chapter cards
            val elements = doc.select("article.bento-ep-card, div.bento-ep-card, div.chapter-item, article.chapter-item, div.chapter-box")
            if (elements.isEmpty()) {
                // If response contains JSON instead of HTML, try to parse alternative
                val bodyStr = response.peekBody(Long.MAX_VALUE).string()
                // Some endpoints return JSON with html field
                if (bodyStr.trim().startsWith("{")) {
                    try {
                        val json = Json.parseToJsonElement(bodyStr)
                        if (json is JsonObject) {
                            val htmlField = json["html"]?.jsonPrimitive?.content ?: json["data"]?.jsonPrimitive?.content
                            if (htmlField != null) {
                                val innerDoc = org.jsoup.Jsoup.parse(htmlField, baseUrl)
                                val innerEls = innerDoc.select("article.bento-ep-card, div.chapter-item")
                                if (innerEls.isNotEmpty()) {
                                    val batch = innerEls.flatMap { try { extractChaptersFromCard(it) } catch (_: Exception) { emptyList() } }
                                    if (!seenBatches.add(batch.map { it.url })) break
                                    chapters.addAll(batch)
                                    offset = (offset ?: 0) + if (offset == null) 20 else 100
                                    continue
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                break
            }

            val batch = elements.flatMap { el ->
                try { extractChaptersFromCard(el) } catch (_: Exception) { emptyList() }
            }.filterNot { it.url.isBlank() }
            if (batch.isEmpty()) break

            // Eğer kart çoklu çeviri ürettiyse aynı bölüm numarası birden fazla kez olacak; deduplicate sadece aynı url için
            val dedupedBatch = batch.distinctBy { it.url }
            if (dedupedBatch.isEmpty()) break
            if (!seenBatches.add(dedupedBatch.map { it.url })) {
                // Duplicate batch indicates pagination broken; break to avoid loop
                break
            }
            chapters.addAll(dedupedBatch)

            // Detect if more pages: site loads 20 first, then 100 per load
            // Check for "load more" indicator in response
            val hasMore = doc.selectFirst("a[data-offset], button[data-offset], div.load-more, a.load-more") != null ||
                batch.size >= 20 // heuristic: if we got full batch, try next
            if (!hasMore && batch.size < 20) break

            offset = (offset ?: 0) + if (offset == null) 20 else 100
            // If batch size < expected, we likely reached end
            if (batch.size < 20 && offset != null && offset > 20) {
                // Still try one more to ensure, but count attempts
                // If next request returns empty, loop will break
            }
        }

        return chapters
    }

    private suspend fun fetchFmChapters(mangaId: String): List<SChapter> {
        val requestUrl = "$baseUrl/cek/fetch_pages_manga.php?manga_cek=$mangaId"
        return try {
            val initialResponse = client.get(requestUrl, ajaxHeaders)
            chapterListParseFm(initialResponse)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun chapterListParseFm(initialResponse: Response): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        var nextPage = 1
        var doc: Document? = null
        var response = initialResponse
        val seenUrls = mutableSetOf<String>()

        do {
            doc = response.asJsoup().also { it.setBaseUri(baseUrl) }
            // Support multiple selectors - her kart çoklu çeviri içerebilir, flatMap ile hepsini aç
            val elements = doc.select("div.chapter-item, article.bento-ep-card, div.bento-ep-card, li.chapter-item, div.chapter-box")
            val batch = elements.flatMap { el ->
                try { extractChaptersFromCard(el) } catch (_: Exception) { emptyList() }
            }.filterNot { seenUrls.contains(it.url) }

            if (batch.isNotEmpty()) {
                batch.forEach { seenUrls.add(it.url) }
                chapters.addAll(batch)
            }

            // Check for next page indicator: a[data-page=nextPage+1]
            nextPage++
            val nextPageLink = doc.selectFirst("a[data-page=\"$nextPage\"], button[data-page=\"$nextPage\"], a[href*='page=$nextPage']")
            if (nextPageLink == null) {
                // Also check for generic pagination
                val hasMoreBySize = batch.size >= 20
                if (!hasMoreBySize) break
                // Try to fetch next via POST even without explicit link, as site may use offset
                // But to avoid infinite loop, check if we already have many chapters and no link
                // We'll try POST once more to see
            }

            // Fetch next page via POST
            response = try {
                val body = FormBody.Builder().add("page", nextPage.toString()).build()
                client.post(requestUrlFromResponse(initialResponse), ajaxHeaders, body)
            } catch (e: Exception) {
                break
            }
            // Loop continues until no next link and batch empty
            // Prevent infinite: if same URLs again, break
            if (chapters.size > 3000) break
        } while (true)

        // If we only fetched first page due to loop logic, at least include it
        if (chapters.isEmpty()) {
            val fallbackDoc = initialResponse.asJsoup()
            val els = fallbackDoc.select("div.chapter-item, article.bento-ep-card")
            return els.flatMap { try { extractChaptersFromCard(it) } catch (_: Exception) { emptyList() } }
        }
        }

        return chapters
    }

    private fun requestUrlFromResponse(response: Response): String {
        // The initial request URL contains manga_cek; reuse it
        return response.request.url.toString()
    }

    private fun parseDirectChapters(document: Document): List<SChapter> {
        // Try to find chapter links directly in HTML without AJAX
        val selectors = listOf(
            "article.bento-ep-card",
            "div.chapter-item",
            "a.bento-ep-title-link",
            "div.chapter-title a",
            "a[href*='-read-']",
            "a[href*='/id-']"
        )
        for (sel in selectors) {
            val elements = document.select(sel)
            if (elements.isNotEmpty()) {
                // If selector is link itself, map each link
                val chapters = mutableListOf<SChapter>()
                for (el in elements) {
                    val linkEl = if (el.tagName() == "a") el else el.selectFirst("a") ?: continue
                    val href = linkEl.absUrl("href").ifBlank { continue }
                    if (!href.contains("manga-tr.com") && !href.startsWith("/")) continue
                    // Filter out non-chapter links (like manga list)
                    if (href.contains("/manga-") && href.endsWith(".html") && !href.contains("read") && !href.contains("bolum")) {
                        // This is manga detail, skip
                        continue
                    }
                    try {
                        chapters.add(chapterFromElement(el))
                    } catch (_: Exception) {
                        // Fallback: create minimal chapter
                        val text = linkEl.text().trim().ifEmpty { linkEl.attr("title") }
                        if (text.isNotBlank()) {
                            chapters.add(SChapter.create().apply {
                                setUrlWithoutDomain(href)
                                name = text
                                chapter_number = Regex("""(\d+(?:\.\d+)?)""").find(text)?.value?.toFloatOrNull() ?: -1f
                            })
                        }
                    }
                }
                if (chapters.isNotEmpty()) {
                    // Deduplicate by URL
                    return chapters.distinctBy { it.url }
                }
            }
        }
        return emptyList()
    }

    /**
     * Her bir bento-ep-card içinde birden fazla fansub çevirisi olabilir (ör: 1. Bölüm → 4 çeviri).
     * Senin son attığın screenshot'taki gibi “Detay ▼” içinde her satır ayrı bir gruptur.
     * Bu yüzden tek bir karta karşılık 1 değil, N tane SChapter üretiyoruz.
     */
    private fun extractChaptersFromCard(card: Element): List<SChapter> {
        // Önce kart içindeki tüm olası grup linklerini topla
        val groupLinkCandidates = mutableListOf<Element>()

        // 1) Haruneko'nun kullandığı ana başlık linki
        card.select("a.bento-ep-title-link").forEach { groupLinkCandidates.add(it) }
        // 2) Fansub özel seçiciler
        card.select("a.fansub-group-item, a.bento-ep-group-link, a.group-link, div.bento-ep-groups a, div.chapter-groups a").forEach {
            if (!groupLinkCandidates.contains(it)) groupLinkCandidates.add(it)
        }
        // 3) Genel fallback: kart içindeki tüm read/id linkleri (en az 2 ise çoklu çeviri demektir)
        val allReadLinks = card.select("a[href*='/id-'], a[href*='/read-'], a[href*='-read-']")
        // Eğer kart içinde birden fazla read linki varsa, bunları grup olarak kabul et
        if (allReadLinks.size > 1) {
            allReadLinks.forEach { if (!groupLinkCandidates.contains(it)) groupLinkCandidates.add(it) }
        }
        // Ayrıca Detay dropdown içindeki linkler bazen button[data-href] olabilir
        card.select("[data-href*='/id-'], [data-href*='/read-']").forEach { el ->
            val href = el.attr("data-href")
            if (href.isNotBlank()) groupLinkCandidates.add(el)
        }

        // Eğer hiç aday yoksa tekil fallback
        if (groupLinkCandidates.isEmpty()) {
            return try { listOf(chapterFromSingleLink(card, card.selectFirst("a") ?: return emptyList())) } catch (_: Exception) { emptyList() }
        }

        // Kartın ortak bilgilerini bir kere çıkar (numara, alt başlık, tarih)
        val commonNumber = card.selectFirst(".bento-ep-chapter-num")?.text()?.removeSuffix(".")?.trim()
            ?: card.selectFirst("div.chapter-title a span:last-child")?.text()?.trim()?.takeIf { it.matches(Regex(""".*\d+.*""")) }
            ?: card.selectFirst("a.bento-ep-title-link")?.text()?.let { extractNumber(it) }
        val commonSubtitle = card.selectFirst(".bento-ep-subtitle, .chapter-sub, div.chapter-sub")?.textOrNull()?.trim()
        val commonDateText = card.selectFirst(".bento-ep-meta-time")?.text()?.trim()
            ?: card.selectFirst(".stats")?.ownText()?.trim()
            ?: card.selectFirst(".bento-ep-meta")?.text()?.trim()
            ?: card.selectFirst(".chapter-stats, .chapter-meta")?.text()?.trim()
        val commonDate = parseRelativeDate(commonDateText)

        val baseName = when {
            !commonNumber.isNullOrBlank() && !commonSubtitle.isNullOrBlank() && !commonSubtitle.contains("Bölüm", true) ->
                "Bölüm $commonNumber - $commonSubtitle"
            !commonNumber.isNullOrBlank() && !commonSubtitle.isNullOrBlank() ->
                commonSubtitle.takeIf { it.contains("Bölüm", true) } ?: "Bölüm $commonNumber - $commonSubtitle"
            !commonNumber.isNullOrBlank() -> "Bölüm $commonNumber" + (commonSubtitle?.let { " - $it" } ?: "")
            else -> card.selectFirst("a.bento-ep-title-link")?.text()?.trim() ?: "Bölüm"
        }.trim()

        val chapterNumber = commonNumber?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.value?.toFloatOrNull() }
            ?: Regex("""(\d+(?:\.\d+)?)""").find(baseName)?.value?.toFloatOrNull() ?: -1f

        // Tek linkli kartta tek chapter, çok linkli kartta her link için ayrı chapter
        // Eğer kartta tek bir başlık linki var ama yanında fansub listesi ayrı elementler olarak duruyorsa
        // ana linki tek chapter olarak döndürüp scanlator'u birleştirilmiş olarak göster
        // yerine her fansub için ayrı chapter üretmek daha doğru (Mihon scanlator rozeti için)
        if (groupLinkCandidates.size == 1) {
            // Tek çeviri → scanlator tekil
            return try { listOf(chapterFromSingleLink(card, groupLinkCandidates[0], baseName, chapterNumber, commonDate)) } catch (_: Exception) { emptyList() }
        }

        // Çoklu çeviri: her aday link için ayrı SChapter
        // Ancak adaylardan bazıları aynı href'e sahipse deduplicate et
        val deduped = groupLinkCandidates.distinctBy {
            it.absUrl("href").ifBlank { it.attr("data-href") }.ifBlank { it.text() }
        }

        // Eğer dedup sonrası hala 1 ise tekil döndür
        if (deduped.size == 1) {
            return try { listOf(chapterFromSingleLink(card, deduped[0], baseName, chapterNumber, commonDate)) } catch (_: Exception) { emptyList() }
        }

        val result = mutableListOf<SChapter>()
        for (linkEl in deduped) {
            try {
                val href = linkEl.absUrl("href").ifBlank { linkEl.attr("data-href") }.ifBlank { continue }
                // Manga detay linklerini ele (manga-*.html ama read içermeyenler)
                if (href.contains("/manga-") && href.endsWith(".html") && !href.contains("read") && !href.contains("id-") && !href.contains("bolum")) continue

                // Bu satıra özel scanlator'u bul: link'in parent'ındaki fansub ismi veya link'in kendi text'i
                val row = linkEl.parent() ?: linkEl
                var scanlator = linkEl.text()?.trim()?.takeIf { it.contains("Fansub", true) || it.contains("FANSUB") }
                    ?: row.selectFirst(".fansub-name, .bento-ep-scanlator, .scanlator, .group-name")?.text()?.trim()
                    ?: linkEl.attr("title")?.takeIf { it.contains("Fansub", true) }
                    ?: linkEl.selectFirst("span")?.text()?.takeIf { it.contains("Fansub", true) }
                    ?: extractScanlatorFromText(linkEl.text() + " " + row.text())

                // Fallback: kartın ilgili satırındaki Detay dropdown'ının yanındaki isim
                if (scanlator.isNullOrBlank()) {
                    val nearestFansub = row.text().let { extractScanlatorFromText(it) }
                    scanlator = nearestFansub
                }

                val ch = SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = baseName
                    this.scanlator = scanlator?.takeIf { it.isNotBlank() && it.length < 60 && !it.contains("Bölüm", true) }
                    chapter_number = chapterNumber
                    date_upload = commonDate
                    // date_upload eğer satır bazlı farklıysa o satırın tarihini al
                    val rowDate = row.selectFirst(".bento-ep-meta-time, .stats, .chapter-stats")?.text()?.trim()?.let { parseRelativeDate(it) }
                    if (rowDate != null && rowDate != 0L) date_upload = rowDate
                }
                result.add(ch)
            } catch (_: Exception) { continue }
        }

        // Eğer çoklu üretmede hiçbir şey çıkmadıysa, en azından ana linkten tek chapter dön
        if (result.isEmpty()) {
            return try { listOf(chapterFromSingleLink(card, deduped.firstOrNull() ?: card.selectFirst("a") ?: return emptyList(), baseName, chapterNumber, commonDate)) } catch (_: Exception) { emptyList() }
        }

        return result
    }

    private fun chapterFromSingleLink(
        card: Element,
        link: Element,
        baseName: String? = null,
        chapterNumber: Float? = null,
        commonDate: Long? = null,
    ): SChapter = SChapter.create().apply {
        val href = link.absUrl("href").ifBlank { link.attr("data-href") }
        if (href.isBlank()) throw IllegalArgumentException("chapter link href blank")
        setUrlWithoutDomain(href)

        val scanlator = card.selectFirst(".bento-ep-scanlator")?.text()?.trim()
            ?: link.parent()?.selectFirst(".scanlator, .bento-ep-scanlator")?.text()?.trim()
            ?: link.selectFirst(".scanlator")?.text()?.trim()
            ?: card.selectFirst("a.fansub-group-item")?.text()?.trim()
            ?: card.selectFirst(".bento-ep-source, .chapter-source, .scanlator-badge")?.text()?.trim()
            ?: card.selectFirst(".bento-ep-meta span:last-child")?.text()?.trim()?.takeIf { it.contains("Fansub") || it.contains("Çeviri") }
            ?: link.text()?.takeIf { it.contains("Fansub", true) }
            ?: extractScanlatorFromText(card.text())

        if (!scanlator.isNullOrBlank() && scanlator.length < 60 && !scanlator.contains("Bölüm", true)) {
            this.scanlator = scanlator
        }

        name = baseName ?: run {
            val numberBento = link.selectFirst(".bento-ep-chapter-num")?.text()?.removeSuffix(".")?.trim()
            val titleText = link.textOrNull()?.trim()
            val subtitle = card.selectFirst(".bento-ep-subtitle, .chapter-sub")?.textOrNull()?.trim()
            val number = numberBento ?: extractNumber(titleText)
            when {
                !number.isNullOrBlank() && !subtitle.isNullOrBlank() && !subtitle.contains("Bölüm", true) -> "Bölüm $number - $subtitle"
                !number.isNullOrBlank() && !subtitle.isNullOrBlank() -> subtitle.takeIf { it.contains("Bölüm", true) } ?: "Bölüm $number - $subtitle"
                !number.isNullOrBlank() -> "Bölüm $number" + (subtitle?.let { " - $it" } ?: "")
                !titleText.isNullOrBlank() -> titleText
                else -> "Bölüm"
            }.trim().ifBlank { "Bölüm" }
        }

        chapter_number = chapterNumber ?: link.text()?.let { Regex("""(\d+(?:\.\d+)?)""").find(it)?.value?.toFloatOrNull() }
            ?: Regex("""(\d+(?:\.\d+)?)""").find(name)?.value?.toFloatOrNull() ?: -1f

        date_upload = commonDate ?: parseRelativeDate(
            card.selectFirst(".bento-ep-meta-time")?.text()?.trim()
                ?: card.selectFirst(".stats")?.ownText()?.trim()
                ?: card.selectFirst(".bento-ep-meta")?.text()?.trim()
                ?: card.selectFirst(".chapter-stats")?.text()?.trim()
        )
    }

    private fun chapterFromElement(element: Element): SChapter {
        // Eski tekil API'yi koru: eğer kart çoklu çeviri içeriyorsa ilkini döndür
        // Asıl çoklu mantık extractChaptersFromCard'ta
        val list = extractChaptersFromCard(element)
        if (list.isNotEmpty()) return list.first()
        // Fallback: tekil link
        return chapterFromSingleLink(element, element.selectFirst("a") ?: throw IllegalArgumentException("chapter link not found"))
    }

    private fun extractScanlatorFromText(text: String): String? {
        // Look for known fansub suffixes
        val fansubRegex = Regex("""([A-Za-z0-9\u00C0-\u024F\s]+Fansub(?:\s*x\s*[A-Za-z0-9\s]+)?)""", RegexOption.IGNORE_CASE)
        return fansubRegex.find(text)?.value?.trim()
    }

    private fun extractNumber(text: String?): String? {
        if (text == null) return null
        return Regex("""(?:Bölüm\s*)?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
    }

    // =============================== Pages ===============================

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        var response = client.get(baseUrl + chapter.url)
        var document = response.asJsoup()

        // Handle translation group selection modal / interstitial
        // If page shows "Çeviri Grubu Seçimi" with multiple group links, auto-select one
        val groupSelection = handleGroupSelectionIfNeeded(document, response)
        if (groupSelection != null) {
            response = groupSelection.first
            document = groupSelection.second
        }

        if (document.selectFirst("div#uyari:contains(üye girişi), div.alert:contains(üye girişi)") != null ||
            document.html().contains("üye girişi yapmanız gerekmektedir", ignoreCase = true)) {
            throw IOException("Bu bölümü okuyabilmek için WebView üzerinden üye girişi yapmanız gerekmektedir.")
        }

        if (document.selectFirst("canvas#sliderCanvas, div.box h2:contains(Güvenlik Doğrulaması), div.cf-turnstile, div#turnstile") != null) {
            captchaUrl = response.request.url.toString()
            throw IOException("Lütfen WebView'da Bot Korumasını geçin.")
        }

        // Try Bento scrambled reader (rdm- + _fpx)
        val bentoPages = tryParseBentoPages(document)
        if (bentoPages != null && bentoPages.isNotEmpty()) return bentoPages

        // Try FMReader chapter-page with data-parts / data-order
        val fmPages = tryParseFmPages(document)
        if (fmPages.isNotEmpty()) return fmPages

        // Fallback: direct img_part.php images
        val directPages = tryParseDirectImages(document)
        if (directPages.isNotEmpty()) return directPages

        // Last resort: generic img tags in reader area
        val genericImgs = document.select("div.reader img, div.chapter-images img, #reader img, .reader-area img")
            .mapNotNull { img ->
                val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }.ifBlank { return@mapNotNull null }
                if (src.contains("logo") || src.contains("avatar") || src.length < 10) return@mapNotNull null
                src
            }.distinct()

        if (genericImgs.isNotEmpty()) {
            return genericImgs.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
        }

        throw IOException("Sayfa resimleri bulunamadı. Bölüm görüntüleri yüklenemedi.")
    }

    private fun handleGroupSelectionIfNeeded(document: Document, response: Response): Pair<Response, Document>? {
        // Detect modal title
        val hasModal = document.selectFirst("*:contains(Çeviri Grubu Seçimi)") != null ||
            document.text().contains("farklı çeviri grubu tarafından", ignoreCase = true) ||
            document.selectFirst("div.modal:contains(Çeviri Grubu)") != null

        if (!hasModal) return null

        // Find group links: each group is a link to different reader ID
        // They have href like /id-XXXXX-read-... or /manga-.../bolum-...
        val groupLinks = mutableListOf<Element>()

        // Strategy 1: Look for container with group items
        val containers = document.select("div:has(> *:contains(Çeviri Grubu Seçimi))")
        for (container in containers) {
            val links = container.select("a[href*='-read-'], a[href*='/id-'], a.fansub-group-item")
            groupLinks.addAll(links)
        }
        if (groupLinks.isEmpty()) {
            // Strategy 2: Look for all links with read pattern near the modal text
            groupLinks.addAll(document.select("a[href*='read-chapter'], a[href*='/id-'][href*='.html']"))
        }
        if (groupLinks.isEmpty()) return null

        // Filter to keep only chapter read links (distinct)
        val distinctLinks = groupLinks.distinctBy { it.absUrl("href") }
            .filter { it.absUrl("href").contains("manga-tr.com") || it.attr("href").startsWith("/") || it.attr("href").startsWith("http") }

        if (distinctLinks.isEmpty()) return null

        // Prefer the one marked active / okunan / bagimsiz
        val preferred = distinctLinks.firstOrNull { el ->
            val txt = el.text().lowercase(Locale.ROOT)
            txt.contains("aktif") || txt.contains("okunan") || el.hasClass("active") || el.hasClass("selected") ||
                el.parent()?.text()?.lowercase(Locale.ROOT)?.contains("aktif") == true
        } ?: distinctLinks.firstOrNull { it.text().contains("Bağımsız", true) }
            ?: distinctLinks.first()

        val href = preferred.absUrl("href")
        if (href.isBlank() || href == response.request.url.toString()) return null

        // Fetch the selected group's page
        return try {
            val newResp = client.get(href)
            val newDoc = newResp.asJsoup()
            Pair(newResp, newDoc)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryParseBentoPages(document: Document): List<Page>? {
        val configScript = document.selectFirst("script[type=application/json][id^=rdm-]")
            ?: return null

        // Extract pageKey (32-char hex) from script attributes
        val pageKey = configScript.attributes().firstNotNullOfOrNull { attr ->
            attr.value.takeIf { HEX_KEY_REGEX.matches(it) }
        } ?: run {
            // Try alternative: find hex in html near rdm-
            HEX_KEY_REGEX.find(document.html())?.value
        } ?: return null

        // Extract gate path: try multiple variable names
        val gatePath = GATE_PATH_REGEX.find(document.html())?.groupValues?.get(1)
            ?: Regex("""(?:_fpx|gatePath|mtrGatePath|readerGate)\s*=\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1)
            ?: return null

        val gateUrl = baseUrl.toHttpUrl().resolve(gatePath)
            ?.takeIf { it.scheme == baseUrl.toHttpUrl().scheme && it.host == baseUrl.toHttpUrl().host }
            ?: return null

        // Gate may require specific headers: try with JSON body
        val gateKey = try {
            client.post(gateUrl.toString(), "{}".toRequestBody(JSON_MEDIA_TYPE))
                .parseAs<ReaderGateDto>().k
        } catch (e: Exception) {
            // Try alternative: GET gate
            try {
                client.get(gateUrl.toString()).parseAs<ReaderGateDto>().k
            } catch (_: Exception) {
                return null
            }
        }

        val config = try {
            configScript.data()
                .decrypt("gate|$gateKey|reader")
                .decrypt("boot|$pageKey|reader")
                .parseAs<ReaderConfigDto>().data
        } catch (e: Exception) {
            return null
        }

        // Select page elements and build Page list
        val elements = try {
            document.select("[${config.parts}]")
                .sortedBy { it.attr(config.pageIndex).toIntOrNull() ?: Int.MAX_VALUE }
        } catch (e: Exception) {
            return null
        }

        if (elements.isEmpty()) return null

        return elements.mapIndexed { index, element ->
            val encryptedParts = element.attr(config.parts)
            if (encryptedParts.isBlank()) return@mapIndexed null
            val imageUrl = try {
                encryptedParts.decrypt("attr|$pageKey|reader").parseAs<List<String>>().first()
            } catch (e: Exception) {
                return@mapIndexed null
            }
            val order = try {
                element.attrOrNull(config.order)?.takeIf { it.isNotBlank() }
                    ?.decrypt("order|$pageKey|reader")?.parseAs<List<Int>>()
            } catch (_: Exception) { null }

            val url = if (order.isNullOrEmpty()) {
                imageUrl
            } else {
                // Store order in fragment for interceptor
                try {
                    imageUrl.toHttpUrl().newBuilder().fragment(order.joinToString(",")).toString()
                } catch (_: Exception) { imageUrl }
            }
            Page(index, imageUrl = url)
        }.filterNotNull()
    }

    private fun tryParseFmPages(document: Document): List<Page> {
        val chapterPages = document.select("div.chapter-page")
        if (chapterPages.isEmpty()) return emptyList()

        val pages = mutableListOf<Page>()
        val sortedChapterPages = chapterPages
            .filter { it.hasAttr("data-parts") }
            .sortedBy { it.attr("data-page-index").toIntOrNull() ?: it.attr("data-index").toIntOrNull() ?: Int.MAX_VALUE }

        if (sortedChapterPages.isEmpty()) return emptyList()

        for (page in sortedChapterPages) {
            val partsJson = page.attr("data-parts").ifBlank { continue }
            val orderAttr = page.attr("data-order") // may be blank

            val urls: List<String> = try {
                // partsJson may be already plain JSON or base64? Try direct parse
                Json.parseToJsonElement(partsJson).jsonArray.map { it.jsonPrimitive.content }
            } catch (e: Exception) {
                // Try decrypt? In some older versions parts are encrypted? But FMReader now plain
                continue
            }

            if (urls.isEmpty()) continue

            if (orderAttr.isNullOrBlank()) {
                // No ordering, just add urls as separate pages
                for (url in urls) {
                    pages.add(Page(pages.size, imageUrl = url))
                }
                continue
            }

            val mapping = decodePartOrderMapping(orderAttr)
            if (mapping == null || mapping.isEmpty()) {
                // Fallback: add first URL only or all
                for (url in urls) pages.add(Page(pages.size, imageUrl = url))
                continue
            }

            val sortedUrls = mapping.sortedBy { it.second }
                .mapNotNull { (partIdx, _) -> urls.getOrNull(partIdx) }

            if (sortedUrls.isEmpty()) {
                pages.add(Page(pages.size, imageUrl = urls.first()))
                continue
            }

            for (url in sortedUrls) {
                pages.add(Page(pages.size, imageUrl = url))
            }
        }

        // If we got pages but still zero due to filtering, try alternative: each chapter-page is single image with data-src
        if (pages.isEmpty()) {
            for (el in chapterPages) {
                val src = el.attr("data-src").ifBlank { el.attr("src") }.ifBlank { el.selectFirst("img")?.absUrl("src") ?: "" }
                if (src.isNotBlank()) pages.add(Page(pages.size, imageUrl = src))
            }
        }

        return pages
    }

    private fun tryParseDirectImages(document: Document): List<Page> {
        val directImages = document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")
        if (directImages.isNotEmpty()) {
            return directImages.mapIndexed { index, img ->
                val src = img.attr("src").ifEmpty { img.attr("data-src") }
                Page(index, imageUrl = src)
            }
        }

        val html = document.html()
        val imgUrlRegex = Regex("""https?://[^"'\\s]*img_part\.php[^"'\\s]*""")
        val seenKeys = mutableSetOf<String>()
        val imgUrls = imgUrlRegex.findAll(html)
            .map { it.value.replace("&amp;", "&") }
            .filterNot { it.contains("logo", ignoreCase = true) }
            .filter { url ->
                val keyMatch = Regex("""key=([^&]+)""").find(url)
                val key = keyMatch?.groupValues?.get(1) ?: return@filter false
                if (seenKeys.contains(key)) {
                    false
                } else {
                    seenKeys.add(key)
                    true
                }
            }
            .toList()

        if (imgUrls.isNotEmpty()) {
            return imgUrls.mapIndexed { idx, url -> Page(idx, imageUrl = url) }
        }

        return emptyList()
    }

    private fun String.decrypt(key: String): String {
        // Handle url-safe base64
        val normalized = replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(pad % 4)
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        for (i in bytes.indices) {
            bytes[i] = (bytes[i].toInt() xor key[i % key.length].code).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    /** Decodes `data-order`: Base64, XOR 0x5A, then JSON mapping part index to vertical order. */
    private fun decodePartOrderMapping(encoded: String): List<Pair<Int, Int>>? {
        return try {
            if (encoded.isBlank()) return null
            val raw = Base64.decode(encoded, Base64.DEFAULT)
            val decoded = raw.map { (it.toInt() and 0xFF) xor 0x5A }
            val jsonStr = String(decoded.map { it.toByte() }.toByteArray(), StandardCharsets.UTF_8)
            val element = Json.parseToJsonElement(jsonStr)
            when (element) {
                is JsonArray -> {
                    element.mapIndexedNotNull { idx, el ->
                        val pos = el.jsonPrimitive.content.toIntOrNull() ?: return@mapIndexedNotNull null
                        idx to pos
                    }
                }
                is JsonObject -> {
                    element.mapNotNull { (k, v) ->
                        val partIdx = k.toIntOrNull() ?: return@mapNotNull null
                        val pos = v.jsonPrimitive.content.toIntOrNull() ?: return@mapNotNull null
                        partIdx to pos
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ============================= Utilities =============================

    private fun scrambledImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        if (!response.isSuccessful) return response
        val order = request.url.fragment
            ?.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == ',' } }
            ?.split(',')
            ?.mapNotNull { it.toIntOrNull() }
            ?: return response

        // Validate response is image
        val contentType = response.header("Content-Type") ?: ""
        if (!contentType.contains("image") && !request.url.toString().contains("img_part") && !request.url.toString().contains("mangatr")) {
            // Still process if fragment present, as site uses fragment for order even without content-type
        }

        val source = try {
            response.use { BitmapFactory.decodeStream(it.body.byteStream()) }
        } catch (e: Exception) { null }
            ?: return response
        // Workaround: if source is null, return original
        if (source.width <= 0 || source.height <= 0) {
            source.recycle()
            return response
        }

        if (order.size > source.height || order.any { it / 100 !in 0..3 || it % 100 !in order.indices } ||
            order.map { it % 100 }.distinct().size != order.size
        ) {
            source.recycle()
            // Invalid order, return original image instead of throwing, to avoid breaking reading
            return response.newBuilder()
                .body(source.let {
                    // Re-encode original? But we consumed stream; need to re-fetch?
                    // Since we already consumed, we need to return error image fallback
                    // We'll just return original response body re-created from bitmap
                    val buf = Buffer().apply { it.compress(Bitmap.CompressFormat.JPEG, 95, outputStream()) }
                    it.recycle()
                    buf.asResponseBody("image/jpeg".toMediaType())
                })
                .build()
        }
        val stripHeight = source.height / order.size
        val output = Bitmap.createBitmap(source.width, stripHeight * order.size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        order.forEachIndexed { sourceIndex, code ->
            val flip = code / 100
            val destination = code % 100
            val src = Rect(0, sourceIndex * stripHeight, source.width, (sourceIndex + 1) * stripHeight)
            val top = destination * stripHeight
            val dst = Rect(0, top, source.width, top + stripHeight)

            canvas.save()
            canvas.scale(
                if (flip and 1 != 0) -1f else 1f,
                if (flip and 2 != 0) -1f else 1f,
                source.width / 2f,
                top + stripHeight / 2f,
            )
            canvas.drawBitmap(source, src, dst, null)
            canvas.restore()
        }
        source.recycle()

        val buffer = Buffer().apply {
            output.compress(Bitmap.CompressFormat.JPEG, 95, outputStream())
        }
        output.recycle()

        return response.newBuilder()
            .body(buffer.asResponseBody("image/jpeg".toMediaType()))
            .build()
    }

    private fun verifyChallengeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 200 && response.header("Content-Type")?.contains("text/html") == true) {
            val bodyString = try { response.peekBody(4096).string() } catch (_: Exception) { "" }

            if (bodyString.contains("/cek/verify.php")) {
                val challenge = CHALLENGE_REGEX.find(bodyString)?.groupValues?.get(1)
                if (challenge != null) {
                    val verifyUrl = request.url.newBuilder().encodedPath("/cek/verify.php").build()
                    val verifyRequest = request.newBuilder()
                        .url(verifyUrl)
                        .post(ChallengeRequestDto(challenge).toJsonRequestBody())
                        .header("Accept", "application/json")
                        .header("Referer", request.url.toString())
                        .header("X-Requested-With", "XMLHttpRequest")
                        .build()

                    try { network.client.newCall(verifyRequest).execute().close() } catch (_: Exception) {}
                    response.close()
                    return chain.proceed(request)
                }
            }
        }
        return response
    }

    private fun parseRelativeDate(dateString: String?): Long {
        if (dateString.isNullOrBlank()) return 0L
        val text = dateString.lowercase(Locale.ROOT)
        val number = NUMBER_REGEX.find(text)?.value?.toLongOrNull() ?: return 0L
        val unit = when {
            text.contains("saniye") -> ChronoUnit.SECONDS
            text.contains("dakika") || text.contains("dk") -> ChronoUnit.MINUTES
            text.contains("saat") -> ChronoUnit.HOURS
            text.contains("gün") || text.contains("gun") -> ChronoUnit.DAYS
            text.contains("hafta") -> ChronoUnit.WEEKS
            text.contains("ay") -> ChronoUnit.MONTHS
            text.contains("yıl") || text.contains("yil") -> ChronoUnit.YEARS
            else -> return 0L
        }
        return try {
            ZonedDateTime.now().minus(number, unit).toInstant().toEpochMilli()
        } catch (e: Exception) { 0L }
    }

    companion object {
        private val NUMBER_REGEX = Regex("""\d+""")
        private val CHALLENGE_REGEX = Regex("""challenge:\s*"([^"]+)"""")
        private val CHAPTER_KEY_REGEX = Regex("""listKey:\s*'([^']+)'""")
        private val GATE_PATH_REGEX = Regex("""_fpx\s*=\s*"([^"]+)"""")
        private val HEX_KEY_REGEX = Regex("""[0-9a-f]{32}""")
        private val TRAILING_YEAR_REGEX = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")
    }
}
