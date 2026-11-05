package eu.kanade.tachiyomi.extension.tr.mangatr

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
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.textOrNull
import keiyoushi.utils.toJsonRequestBody
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

@Source
abstract class MangaTR : KeiSource() {

    override fun Headers.Builder.configureHeaders() = apply {
        set("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
        set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        set("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        set("Accept-Encoding", "gzip, deflate, br")
        set("Sec-Ch-Ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"")
        set("Sec-Ch-Ua-Mobile", "?1")
        set("Sec-Ch-Ua-Platform", "\"Android\"")
        set("Sec-Fetch-Dest", "document")
        set("Sec-Fetch-Mode", "navigate")
        set("Sec-Fetch-Site", "same-origin")
        set("Sec-Fetch-User", "?1")
        set("Upgrade-Insecure-Requests", "1")
    }

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
        addInterceptor(DDoSGuardInterceptor(network.client))
        addInterceptor(::verifyChallengeInterceptor)
        addInterceptor(::scrambledImageInterceptor)
    }

    private val ajaxHeaders get() = headersBuilder()
        .mangaTrAjaxHeaders()
        .build()

    private var captchaUrl: String? = null

    // ============================== Popular ==============================

    override suspend fun getPopularManga(page: Int): MangasPage = getMangaList(page, "$baseUrl/manga-list-sayfala.html?sort=views&sort_type=DESC&page=$page&listType=pagination")

    // ============================== Latest ===============================

    override suspend fun getLatestUpdates(page: Int): MangasPage = getMangaList(page, "$baseUrl/manga-list-sayfala.html?sort=last_update&sort_type=DESC&page=$page&listType=pagination")

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
        // --- New site: index-sayfa-N.html#sayfa (homepage) ---
        val indexPagePattern = Regex("""index-sayfa-(\d+)\.html""")
        val indexPages = select("a[href*='index-sayfa-']").mapNotNull {
            indexPagePattern.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }
        if (indexPages.any { it > currentPage }) return true

        // --- Bento pagination: nav.pagination-wrap a.pagination-link ---
        val paginationLinks = select("nav.pagination-wrap a.pagination-link, .pagination a, ul.pagination a, div.pagination a")
        if (paginationLinks.isNotEmpty()) {
            val active = paginationLinks.firstOrNull { it.hasClass("is-active") || it.hasClass("active") }
            if (active != null) {
                val idx = paginationLinks.indexOf(active)
                if (idx >= 0 && idx < paginationLinks.size - 1) {
                    val nextHref = paginationLinks[idx + 1].attr("href")
                    if (nextHref.isNotBlank()) return true
                    // Even if href blank, active not last => more pages
                    return true
                }
            } else {
                // No active marker: check if any link points to next page number
                if (paginationLinks.any { el ->
                        val t = el.text().trim()
                        t == (currentPage + 1).toString() ||
                            el.attr("href").contains("sayfa-${currentPage + 1}") ||
                            el.attr("href").contains("page=${currentPage + 1}")
                    }
                ) {
                    return true
                }
            }
            val maxNum = paginationLinks.mapNotNull { Regex("""\d+""").find(it.text().trim())?.value?.toIntOrNull() }.maxOrNull()
            if (maxNum != null && maxNum > currentPage) return true
        }

        // --- Legacy: page= query param (manga-list-sayfala.html, arama.html) ---
        val numbered = select("a[href*=page=]").mapNotNull {
            it.absUrl("href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
        }.any { it > currentPage }
        if (numbered) return true

        // Also check manga-list-sayfala pagination with page param in absUrl
        val listSayaPage = select("a[href*='manga-list-sayfala']").mapNotNull {
            it.absUrl("href").toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
        }.any { it > currentPage }
        if (listSayaPage) return true

        // --- Arrows: » or › ---
        val arrowLinks = select("a, button").filter { it.text().trim() in setOf("›", "»", "Sonraki", "Next", "İleri") }
        for (a in arrowLinks) {
            val href = a.absUrl("href")
            if (href.isNotBlank()) {
                val p = href.toHttpUrlOrNull()?.queryParameter("page")?.toIntOrNull()
                if (p != null && p > currentPage) return true
                if (p == null && href.contains("page")) return true
                // index-sayfa pattern in arrow href
                val ip = indexPagePattern.find(href)?.groupValues?.get(1)?.toIntOrNull()
                if (ip != null && ip > currentPage) return true
            } else {
                if (a.text().trim() == "›" || a.text().trim() == "»") {
                    if (!a.hasClass("disabled") && !a.attr("disabled").equals("true", true)) return true
                }
            }
        }

        // --- Fallback: pagination container active check for old FMReader ---
        val bentoPagination = selectFirst("ul.pagination, div.pagination, div.btn-group")
        if (bentoPagination != null) {
            val active = bentoPagination.selectFirst(".active, .btn-info, [aria-current=page]")
            if (active != null) {
                val allPages = bentoPagination.select("a, button").map { it.text().trim() }
                val activeText = active.text().trim()
                val idx = allPages.indexOf(activeText)
                if (idx >= 0 && idx < allPages.size - 1) return true
            }
        }

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
            ?: selectFirst(".bento-hero-cover img")?.absUrl("src")
            ?: selectFirst("img[src*='image.mngtr.site/kapak']")?.absUrl("src")
            ?: selectFirst("img[src*='image.mngtr.site']")?.absUrl("src")
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

    private fun Document.infoRow(label: String): String? = selectFirst(".bento-info-row:has(.bento-info-label:contains($label)) .bento-info-value")?.textOrNull()
        ?: selectFirst("div:has(> .bento-info-label:contains($label)) .bento-info-value")?.textOrNull()

    // ============================= Chapters ==============================

    private suspend fun getChapterList(document: Document, manga: SManga? = null): List<SChapter> {
        // --- Yeni site (2025+): data-manga slug + _sig ile cek/fetch_pages_manga.php ---
        // Kaynak: manga-tr.min.js içinden çözülen __mtrChaptersListInit -> offset 0,20,120...
        // Önce yeni yöntemi dene, başarısız olursa eski fallback'lara düş
        val mangaSlugFromAttr = document.selectFirst("#results")?.attr("data-manga")?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("[data-manga]")?.attr("data-manga")?.trim()?.takeIf { it.isNotEmpty() }
        val mangaSlugFromUrl = manga?.url?.substringAfter("manga-")?.substringBefore(".")
            ?: document.location().substringAfter("manga-").substringBefore(".").substringBefore("?")
                ?.takeIf { it.isNotEmpty() && !it.contains("/") }
        val mangaSlug = mangaSlugFromAttr ?: mangaSlugFromUrl

        if (mangaSlug != null) {
            val tur = document.selectFirst("#results")?.attr("data-tur") ?: ""
            val newChapters = fetchMangaChaptersNew(mangaSlug, tur)
            if (newChapters.isNotEmpty()) return newChapters
        }

        // --- Eski: Bento listKey doğrudan HTML içinde (geriye dönük uyumluluk) ---
        val html = document.html()
        val key = extractChapterKey(html, document)
        if (key != null) {
            val chapters = fetchBentoChapters(key)
            if (chapters.isNotEmpty()) return chapters
        }
        val extKey = extractChapterKeyFromExternalJs(document)
        if (extKey != null) {
            val chapters = fetchBentoChapters(extKey)
            if (chapters.isNotEmpty()) return chapters
        }

        // --- FMReader manga_cek (eski) ---
        val mangaId = mangaSlug
        if (mangaId != null) {
            val fmChapters = fetchFmChapters(mangaId)
            if (fmChapters.isNotEmpty()) return fmChapters
        }

        // Strategy 3: Direct DOM parsing (if chapter list is already in HTML, e.g. no JS)
        val direct = parseDirectChapters(document)
        if (direct.isNotEmpty()) return direct

        throw IOException("Bölüm listesi yüklenemedi. Site yapısı değişmiş olabilir; lütfen uygulamayı güncelleyin.")
    }

    // ============================= Yeni Bölüm API (2025+) =============================
    // Site: cek/fetch_pages_manga.php POST { manga, tur, offset, _t, _sig }
    // _sig = SHA256(mangaSlug_seed_timestamp)  seed = --mdx-white + --mdx-black (CSS)
    // offset: 0 -> 20 -> 120 -> 220 ... (ilk 20, sonra 100'er)
    private suspend fun fetchMangaChaptersNew(mangaSlug: String, tur: String = ""): List<SChapter> {
        val chapters = mutableListOf<SChapter>()
        val seenUrls = mutableSetOf<String>()
        var offset = 0
        var isFirst = true
        var retries = 0
        var requests = 0
        val seed = getMangaSeed()

        while (requests < 40 && chapters.size <= 5000) {
            requests++
            val timestamp = System.currentTimeMillis() / 1000
            val form = FormBody.Builder()
                .add("manga", mangaSlug)
                .add("tur", tur)
                .add("offset", offset.toString())
                .add("_t", timestamp.toString())
                .add("_sig", generateSig(mangaSlug, seed, timestamp))
                .build()
            val headers = headersBuilder()
                .mangaTrAjaxHeaders("$baseUrl/manga-$mangaSlug.html", acceptsHtml = true)
                .set("Origin", baseUrl)
                .build()

            val response = try {
                client.post("$baseUrl/cek/fetch_pages_manga.php", headers, form, ensureSuccess = false)
            } catch (error: Exception) {
                throw IOException("Bölüm listesi isteği gönderilemedi (offset=$offset).", error)
            }

            if (response.code == 403) {
                response.close()
                if (retries < 3) {
                    retries++
                    delay(350)
                    continue
                }
                throw IOException("Bölüm listesi isteği üç yeniden denemeden sonra reddedildi (HTTP 403, offset=$offset).")
            }
            if (!response.isSuccessful) {
                val status = response.code
                response.close()
                throw IOException("Bölüm listesi isteği başarısız oldu (HTTP $status, offset=$offset).")
            }

            val batch = try {
                parseChapterHtmlResponse(response)
            } finally {
                response.close()
            }
            val newChapters = batch.chapters.filter { seenUrls.add(it.url.substringBefore('?')) }

            if (batch.cardCount == 0 || batch.chapters.isEmpty()) {
                if (offset == 0 && chapters.isEmpty()) {
                    throw IOException("Bölüm API'si geçerli bölüm kartı döndürmedi.")
                }
                break
            }
            if (newChapters.isEmpty()) break

            chapters.addAll(newChapters)
            retries = 0

            // The first response contains 20 cards; later responses contain up to
            // 100. An explicit marker, not the number of translation rows, controls pagination.
            if (!batch.hasMore && !(isFirst && batch.cardCount >= 20)) break
            offset = if (isFirst) 20 else offset + 100
            isFirst = false
        }

        return chapters
    }

    private fun parseChapterHtmlResponse(response: Response): MangaTRParser.ChapterBatch {
        val body = response.body.string()
        val html = runCatching {
            if (!body.trim().startsWith("{")) return@runCatching null
            val json = Json.parseToJsonElement(body) as? JsonObject
            json?.get("html")?.jsonPrimitive?.content
                ?: json?.get("data")?.jsonPrimitive?.content
        }.getOrNull() ?: body
        val document = org.jsoup.Jsoup.parse(html, baseUrl)
        return MangaTRParser.parseChapterDocument(document)
    }

    private suspend fun getMangaSeed(): Int {
        // CSS'den --mdx-white ve --mdx-black parse et, yoksa 0
        // mtr.min.css içinde :root { --mdx-white: 255; --mdx-black: 0; } gibi
        return try {
            val cssUrls = listOf(
                "$baseUrl/app/manga/themes/default/assets/css/mtr.min.css",
                "$baseUrl/app/manga/themes/default/assets/css/mtr.min.css?v=0411-6",
            )
            var cssText: String? = null
            for (u in cssUrls) {
                try {
                    cssText = client.get(u).body.string()
                    if (cssText.contains("--mdx-white") || cssText.contains("--mdx-black")) break
                } catch (_: Exception) {}
            }
            if (cssText == null) return 0
            MangaTRContract.seedFromCss(cssText) ?: 0
        } catch (_: Exception) {
            0
        }
    }

    private fun generateSig(mangaSlug: String, seed: Int, timestamp: Long): String = MangaTRContract.signature(mangaSlug, seed, timestamp)

    private fun extractChapterKey(html: String, document: Document): String? {
        val patterns = listOf(
            CHAPTER_KEY_REGEX,
            Regex("""initialChapterListKey\s*=\s*['"`]([^'"`]+)['"`]"""),
            Regex("""window\.mtrChapterKeys\.listKey\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""mtrChapterKeys\s*:\s*\{[^}]*listKey\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""listKey\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""chapter_list_key\s*[:=]\s*['"]([^'"]+)['"]"""),
            Regex("""['"]chapter_list_key['"]\s*:\s*['"]([^'"]+)['"]"""),
            Regex("""data-list-key\s*=\s*['"]([^'"]+)['"]"""),
        )
        for (regex in patterns) {
            val match = regex.find(html)
            if (match != null) {
                val value = match.groupValues.getOrNull(1)?.trim()
                if (!value.isNullOrEmpty() && value.length > 10) return value
            }
        }
        document.selectFirst("#results")?.attr("data-list-key")?.takeIf { it.isNotEmpty() }?.let { return it }
        document.selectFirst("[data-list-key]")?.attr("data-list-key")?.takeIf { it.isNotEmpty() }?.let { return it }
        // meta tag or hidden input fallback
        document.selectFirst("input[name=chapter_list_key]")?.attr("value")?.takeIf { it.length > 10 }?.let { return it }
        return null
    }

    private suspend fun extractChapterKeyFromExternalJs(document: Document): String? {
        // Yeni sitede listKey harici JS içinde: app/manga/themes/default/assets/js/manga-tr.min.js
        val scriptSrc = document.selectFirst("script[src*='manga-tr.min.js']")?.absUrl("src")
            ?: document.selectFirst("script[src*='manga-tr']")?.absUrl("src")
            ?: return null
        return try {
            val jsBody = client.get(scriptSrc, ajaxHeaders).body.string()
            extractChapterKey(jsBody, document)
        } catch (_: Exception) {
            null
        }
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
                                    val batch = innerEls.flatMap {
                                        try {
                                            extractChaptersFromCard(it)
                                        } catch (_: Exception) {
                                            emptyList()
                                        }
                                    }
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
                try {
                    extractChaptersFromCard(el)
                } catch (_: Exception) {
                    emptyList()
                }
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
            // Try GET first (old FMReader), then fallback to POST with offset/page
            val initialResponse = client.get(requestUrl, ajaxHeaders)
            val chapters = chapterListParseFm(initialResponse)
            if (chapters.isNotEmpty()) return chapters

            // Fallback: try POST with offset=0 (new Bento offset mode but using manga_cek slug)
            val formOffset0 = FormBody.Builder().add("manga_cek", mangaId).add("offset", "0").build()
            val postResp = client.post("$baseUrl/cek/fetch_pages_manga.php", ajaxHeaders, formOffset0)
            val postChapters = chapterListParseFm(postResp)
            if (postChapters.isNotEmpty()) return postChapters

            emptyList()
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
        var emptyStreak = 0

        do {
            doc = response.asJsoup().also { it.setBaseUri(baseUrl) }

            // Handle JSON-wrapped HTML (new endpoint returns {"html":"..."} or {"data":"..."})
            var elements = doc.select("div.chapter-item, article.bento-ep-card, div.bento-ep-card, li.chapter-item, div.chapter-box, article.chapter-card")
            if (elements.isEmpty()) {
                val bodyStr = try {
                    response.peekBody(Long.MAX_VALUE).string()
                } catch (_: Exception) {
                    ""
                }
                if (bodyStr.trim().startsWith("{")) {
                    try {
                        val json = Json.parseToJsonElement(bodyStr) as? JsonObject
                        val htmlField = json?.get("html")?.jsonPrimitive?.content
                            ?: json?.get("data")?.jsonPrimitive?.content
                        if (htmlField != null) {
                            val innerDoc = org.jsoup.Jsoup.parse(htmlField, baseUrl)
                            elements = innerDoc.select("div.chapter-item, article.bento-ep-card, div.bento-ep-card")
                        }
                    } catch (_: Exception) {}
                }
            }

            val batch = elements.flatMap { el ->
                try {
                    extractChaptersFromCard(el)
                } catch (_: Exception) {
                    emptyList()
                }
            }.filterNot { seenUrls.contains(it.url) }

            if (batch.isNotEmpty()) {
                batch.forEach { seenUrls.add(it.url) }
                chapters.addAll(batch)
                emptyStreak = 0
            } else {
                emptyStreak++
                if (emptyStreak >= 2) break
            }

            // Pagination: new Bento uses offset, old FMReader uses page
            // Detect which to use based on initial request URL
            val usesMangaCek = response.request.url.toString().contains("manga_cek")
            nextPage++

            // Look for explicit next page / load-more
            val nextPageLink = doc.selectFirst("a[data-page=\"$nextPage\"], button[data-page=\"$nextPage\"], a[href*='page=$nextPage'], button[data-offset], a[data-offset]")
            val btnLoadMore = doc.selectFirst("#btn-load-more, button.load-more, a.load-more")
            val hasMoreByIndicator = nextPageLink != null || btnLoadMore != null
            val hasMoreBySize = batch.size >= 20 || batch.size >= 10 // smaller batch may still have more

            if (!hasMoreByIndicator && !hasMoreBySize) {
                // Try one more offset fetch before giving up (Bento loads 20 then 100)
                if (batch.isEmpty() && chapters.isEmpty()) break
                if (batch.size < 10) break
            }

            // Choose pagination param: if Bento (no manga_cek key but slug), use offset= (prevOffset+increment)
            // For now try both: page and offset
            response = try {
                val reqUrl = requestUrlFromResponse(initialResponse)
                val body = if (reqUrl.contains("manga_cek") && chapters.size >= 20) {
                    // manga_cek mode with offset (new site): offset = 20 then +100
                    val offsetVal = if (chapters.size <= 20) 20 else chapters.size
                    FormBody.Builder().add("offset", offsetVal.toString()).build()
                } else {
                    FormBody.Builder().add("page", nextPage.toString()).build()
                }
                client.post(reqUrl, ajaxHeaders, body)
            } catch (e: Exception) {
                break
            }

            if (chapters.size > 4000) break
        } while (true)

        if (chapters.isEmpty()) {
            // Ultimate fallback: parse initial doc directly (maybe cards already in HTML)
            val fallbackDoc = try {
                initialResponse.asJsoup()
            } catch (_: Exception) {
                return emptyList()
            }
            val els = fallbackDoc.select("div.chapter-item, article.bento-ep-card, article.bento-ep-card, div.chapter-item, a[href*='-read-']")
            return els.flatMap {
                try {
                    extractChaptersFromCard(it)
                } catch (_: Exception) {
                    emptyList()
                }
            }
                .distinctBy { it.url }
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
            "a[href*='/id-']",
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
                            chapters.add(
                                SChapter.create().apply {
                                    setUrlWithoutDomain(href)
                                    name = text
                                    chapter_number = Regex("""(\d+(?:\.\d+)?)""").find(text)?.value?.toFloatOrNull() ?: -1f
                                },
                            )
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
    private fun extractChaptersFromCard(card: Element): List<SChapter> = MangaTRParser.parseChapterCard(card)

    private fun extractChaptersFromCardLegacy(card: Element): List<SChapter> {
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
            return try {
                listOf(chapterFromSingleLink(card, card.selectFirst("a") ?: return emptyList()))
            } catch (_: Exception) {
                emptyList()
            }
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
            return try {
                listOf(chapterFromSingleLink(card, groupLinkCandidates[0], baseName, chapterNumber, commonDate))
            } catch (_: Exception) {
                emptyList()
            }
        }

        // Çoklu çeviri: her aday link için ayrı SChapter
        // Ancak adaylardan bazıları aynı href'e sahipse deduplicate et
        val deduped = groupLinkCandidates.distinctBy {
            it.absUrl("href").ifBlank { it.attr("data-href") }.ifBlank { it.text() }
        }

        // Eğer dedup sonrası hala 1 ise tekil döndür
        if (deduped.size == 1) {
            return try {
                listOf(chapterFromSingleLink(card, deduped[0], baseName, chapterNumber, commonDate))
            } catch (_: Exception) {
                emptyList()
            }
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
            } catch (_: Exception) {
                continue
            }
        }

        // Eğer çoklu üretmede hiçbir şey çıkmadıysa, en azından ana linkten tek chapter dön
        if (result.isEmpty()) {
            return try {
                listOf(chapterFromSingleLink(card, deduped.firstOrNull() ?: card.selectFirst("a") ?: return emptyList(), baseName, chapterNumber, commonDate))
            } catch (_: Exception) {
                emptyList()
            }
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
                ?: card.selectFirst(".chapter-stats")?.text()?.trim(),
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
        var rawHtml = response.peekBody(Long.MAX_VALUE).string()
        var document = response.asJsoup()

        suspend fun parsePages(doc: Document, html: String): List<Page> {
            // Gate values must come from the exact HTTP bytes, not a Jsoup
            // serialization that can escape or normalize script contents.
            val bentoPages = tryParseBentoPages(doc, response.request.url.toString(), html)
            if (!bentoPages.isNullOrEmpty()) return bentoPages

            val directBentoPages = MangaTRParser.parseReaderPages(doc)
            if (directBentoPages.isNotEmpty()) return directBentoPages

            val fmPages = tryParseFmPages(doc)
            if (fmPages.isNotEmpty()) return fmPages

            val directPages = tryParseDirectImages(doc)
            if (directPages.isNotEmpty()) return directPages

            return doc.select("div.reader img, div.chapter-images img, #reader img, .reader-area img")
                .filterNot { img ->
                    img.attr("width") == "0" || img.attr("height") == "0" || img.parents().any { it.tagName() == "noscript" }
                }
                .mapNotNull { img ->
                    val src = img.absUrl("src").ifBlank { img.absUrl("data-src") }.ifBlank { return@mapNotNull null }
                    if (src.contains("logo") || src.contains("avatar") || src.length < 10) return@mapNotNull null
                    src
                }
                .distinct()
                .mapIndexed { index, url -> Page(index, imageUrl = url) }
        }

        fun validateDocument() {
            if (document.selectFirst("div#uyari:contains(üye girişi), div.alert:contains(üye girişi)") != null ||
                document.html().contains("üye girişi yapmanız gerekmektedir", ignoreCase = true)
            ) {
                throw IOException("Bu bölümü okuyabilmek için WebView üzerinden üye girişi yapmanız gerekmektedir.")
            }
            if (document.selectFirst("canvas#sliderCanvas, div.box h2:contains(Güvenlik Doğrulaması), div.cf-turnstile, div#turnstile") != null) {
                captchaUrl = response.request.url.toString()
                throw IOException("Lütfen WebView'da Bot Korumasını geçin.")
            }
        }

        validateDocument()
        var pages = parsePages(document, rawHtml)

        // Only an actual interstitial without pages needs another request. Prefer
        // the group already associated with this reader ID, never an arbitrary group.
        if (pages.isEmpty()) {
            val groupSelection = handleGroupSelectionIfNeeded(document, response)
            if (groupSelection != null) {
                response = groupSelection.first
                document = groupSelection.second
                rawHtml = groupSelection.third
                validateDocument()
                pages = parsePages(document, rawHtml)
            }
        }

        if (pages.isNotEmpty()) {
            val readerUrl = response.request.url.toString()
            return pages.map { page -> Page(page.index, url = readerUrl, imageUrl = page.imageUrl) }
        }
        throw IOException("Sayfa resimleri bulunamadı. Bölüm görüntüleri yüklenemedi.")
    }

    override fun imageRequest(page: Page): Request = MangaTRImagePipeline.imageRequest(page, headers, baseUrl)

    private suspend fun handleGroupSelectionIfNeeded(document: Document, response: Response): Triple<Response, Document, String>? {
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

        val currentReaderId = READER_ID_REGEX.find(response.request.url.toString())?.groupValues?.get(1)
        val preferred = distinctLinks.firstOrNull { el ->
            val txt = el.text().lowercase(Locale.ROOT)
            el.hasClass("is-current") || el.hasClass("active") || el.hasClass("selected") ||
                txt.contains("aktif") || txt.contains("okunan") ||
                el.parent()?.text()?.lowercase(Locale.ROOT)?.contains("aktif") == true
        } ?: distinctLinks.firstOrNull { el ->
            currentReaderId != null && READER_ID_REGEX.find(el.absUrl("href"))?.groupValues?.get(1) == currentReaderId
        } ?: distinctLinks.first()

        val href = preferred.absUrl("href")
        if (href.isBlank() || href.substringBefore('?') == response.request.url.toString().substringBefore('?')) return null

        // Fetch the selected group's page
        return try {
            val newResp = client.get(href)
            val newRawHtml = newResp.peekBody(Long.MAX_VALUE).string()
            val newDoc = newResp.asJsoup()
            Triple(newResp, newDoc, newRawHtml)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun tryParseBentoPages(document: Document, readerUrl: String, rawHtml: String): List<Page>? {
        val configScript = document.selectFirst("script[type=application/json][id^=rdm-]")
            ?: document.selectFirst("script[id^=rdm-]")
            ?: document.selectFirst("[id^=rdm-]")
            ?: return null

        // Extract pageKey (32-char hex) from script attributes
        val pageKey = configScript.attributes().firstNotNullOfOrNull { attr ->
            attr.value.takeIf { HEX_KEY_REGEX.matches(it) }
        } ?: run {
            HEX_KEY_REGEX.find(rawHtml)?.value
        } ?: return null

        // Extract gate path from the unmodified response, including its n/c query.
        val gatePath = MangaTRReaderContract.gatePath(rawHtml)

        if (gatePath != null) {
            val gateUrl = baseUrl.toHttpUrl().resolve(gatePath)
                ?.takeIf { it.scheme == baseUrl.toHttpUrl().scheme && it.host == baseUrl.toHttpUrl().host }
                ?: throw IOException("Manga-TR okuyucu gate adresi geçersiz. KTS-SRC-9101")
            val gateHeaders = headers.newBuilder()
                .mangaTrAjaxHeaders(readerUrl)
                .set("Origin", baseUrl)
                .build()
            val nonce = gateUrl.queryParameter("n").orEmpty()
            val gateKey = try {
                client.newCall(
                    Request.Builder()
                        .url(gateUrl)
                        .headers(gateHeaders)
                        .post(
                            ReaderGateRequestDto(
                                nonce,
                                MangaTRReaderContract.canvasFingerprint(gateUrl.queryParameter("c").orEmpty()),
                            ).toJsonRequestBody(),
                        )
                        .build(),
                ).execute().parseAs<ReaderGateDto>().k
            } catch (_: Exception) {
                try {
                    client.newCall(Request.Builder().url(gateUrl).headers(gateHeaders).get().build())
                        .execute().parseAs<ReaderGateDto>().k
                } catch (_: Exception) {
                    null
                }
            }?.takeIf { it.isNotBlank() }
                ?: throw IOException("Manga-TR okuyucu gate anahtarı alınamadı. KTS-SRC-9102")

            val ease = MangaTRReaderContract.readerEase(rawHtml)
            val flow = MangaTRReaderContract.readerFlow(rawHtml)
            val pointer = MangaTRReaderContract.pointerDelta(nonce, ease, flow)
            val config = MangaTRReaderContract.decryptConfig(
                configScript.data().ifBlank { configScript.html() },
                gateKey,
                pageKey,
                pointer,
                MangaTRReaderContract.pointerDeltaCandidates(nonce, ease, flow),
            ) ?: throw IOException("Manga-TR okuyucu config'i çözülemedi. KTS-SRC-9103")

            return MangaTRReaderContract.parseConfiguredPages(document, config, pageKey)
                ?: throw IOException("Manga-TR config'den tam görsel sayfaları üretilemedi. KTS-SRC-9104")
        }

        // Fallback: direct per-page decrypt without gate/config (pageKey yeterli)
        // Her div'de data-* içinde 80+ karakterli base64-şifreli dizi tutulur → attr|pageKey|reader ile çözünce img_part URL'i çıkar
        return tryParseBentoPagesDirectFallback(document, pageKey)
    }

    private fun tryParseBentoPagesDirectFallback(document: Document, pageKey: String): List<Page>? {
        val candidates = mutableListOf<Triple<Element, String, String?>>() // el, partsAttr, indexAttr
        val allElements = document.select("*")
        for (el in allElements) {
            var partsAttr: String? = null
            var decrypted: String? = null
            // Her attribute'u dene
            for (attr in el.attributes()) {
                val v = attr.value
                if (v.length < 80) continue
                // Base64 benzeri mi?
                if (!v.matches(Regex("[A-Za-z0-9+/=_\\-]{80,}"))) continue
                val t = try {
                    v.decrypt("attr|$pageKey|reader")
                } catch (_: Exception) {
                    continue
                }
                if (t.contains("img_part.php") || (t.contains("http") && t.contains("image.mngtr.site"))) {
                    partsAttr = attr.key
                    decrypted = t
                    break
                }
            }
            if (partsAttr != null) {
                // Sayfa index attribute'unu bul: numeric 0..N
                val idxAttr = el.attributes().firstOrNull { a ->
                    a.key != partsAttr && a.value.matches(Regex("\\d+")) && a.value.toIntOrNull()?.let { it < 1000 } == true
                }?.key
                candidates.add(Triple(el, partsAttr, idxAttr))
            }
        }
        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedBy { (el, _, idxAttr) ->
            idxAttr?.let { el.attr(it).toIntOrNull() } ?: Int.MAX_VALUE
        }

        val pages = sorted.mapIndexed { idx, (el, partsAttr, _) ->
            val enc = el.attr(partsAttr)
            val url = try {
                enc.decrypt("attr|$pageKey|reader").parseAs<List<String>>().firstOrNull()
                    ?: enc.decrypt("attr|$pageKey|reader").let { Json.parseToJsonElement(it).jsonArray.firstOrNull()?.jsonPrimitive?.content }
            } catch (_: Exception) {
                null
            }
            if (url.isNullOrBlank()) return@mapIndexed null
            // Order varsa fragment'e ekle (scrambled)
            // Order attribute'u bulmaya çalış
            var orderFrag: List<Int>? = null
            for (a in el.attributes()) {
                if (a.key == partsAttr) continue
                if (a.value.length < 10) continue
                val ordDec = try {
                    a.value.decrypt("order|$pageKey|reader")
                } catch (_: Exception) {
                    continue
                }
                if (ordDec.trim().startsWith("[") || ordDec.trim().startsWith("{")) {
                    orderFrag = try {
                        Json.parseToJsonElement(ordDec).jsonArray.map { it.jsonPrimitive.content.toInt() }
                    } catch (_: Exception) {
                        null
                    }
                    if (orderFrag != null) break
                }
            }
            val finalUrl = if (orderFrag.isNullOrEmpty()) {
                url
            } else {
                try {
                    url.toHttpUrl().newBuilder().fragment(orderFrag.joinToString(",")).toString()
                } catch (_: Exception) {
                    url
                }
            }
            Page(idx, imageUrl = finalUrl)
        }.filterNotNull()

        return pages.takeIf { it.isNotEmpty() }
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

    private fun tryParseDirectImages(document: Document): List<Page> = document.select("img[src*='img_part.php'], img[data-src*='img_part.php']")
        .filterNot { img ->
            img.attr("width") == "0" || img.attr("height") == "0" ||
                img.attr("style").contains("display:none", ignoreCase = true) ||
                img.parents().any { it.tagName() == "noscript" }
        }
        .map { img -> img.absUrl("src").ifBlank { img.absUrl("data-src") } }
        .filter { it.isNotBlank() }
        .distinct()
        .mapIndexed { index, url -> Page(index, imageUrl = url) }

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

    internal fun scrambledImageInterceptor(chain: Interceptor.Chain): Response = MangaTRImagePipeline.intercept(chain)

    private fun verifyChallengeInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code == 200 && response.header("Content-Type")?.contains("text/html") == true) {
            val bodyString = try {
                response.peekBody(4096).string()
            } catch (_: Exception) {
                ""
            }

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

                    try {
                        network.client.newCall(verifyRequest).execute().close()
                    } catch (_: Exception) {}
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
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        private val NUMBER_REGEX = Regex("""\d+""")
        private val CHALLENGE_REGEX = Regex("""challenge:\s*"([^"]+)"""")
        private val CHAPTER_KEY_REGEX = Regex("""listKey:\s*'([^']+)'""")
        private val HEX_KEY_REGEX = Regex("""[0-9a-f]{32}""")
        private val READER_ID_REGEX = Regex("""(?:^|/)id-(\d+)-read-""")
        private val TRAILING_YEAR_REGEX = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")
    }
}
