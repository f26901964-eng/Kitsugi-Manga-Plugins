package eu.kanade.tachiyomi.multisrc.initlisenext

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Initlise-Next platformu (duckscans.com, lunariscans.com, ailurascans.com,
 * oblivionscans.com). WordPress InitManga ailesinin Next.js yeniden yazımı;
 * wp-json uçları YOK, onun yerine:
 *
 *  - GET /api/series?q=<sorgu>&page=N         → arama (runner'da 200 doğrulandı)
 *  - GET /api/series?page=N[&sort=createdAt]  → katalog / yeniler
 *  - GET /seri/<slug>                         → SSR detay; uçuş verisinde
 *    "initialChapters" dizisi TÜM bölümleri sayfa yollarıyla birlikte gömer.
 *    Sitedeki okuyucu kilidi ("Giriş Yapmanız Gerekiyor") yalnız okuyucu
 *    arayüzü içindir; gömülü veri anonim oturumda da gelir (kullanıcının
 *    kendi tarayıcı kayıtları: 79 ve 114 bölümlük serilerde sayfalar tam).
 *
 * Okuyucu, bölüm sayfasını değil SERİ sayfasını çözümleyerek sayfalara ulaşır;
 * böylece reader-guard/coins kapısı hiç devreye girmez.
 */
abstract class InitliseNext : KeiSource() {

    /**
     * Cloudflare clearance çerezi User-Agent'a bağlıdır; tüm istekler (API +
     * görsel) aynı tarayıcı UA'sını taşır, aksi hâlde 403 düşer.
     */
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .apply {
                    val ua = header("User-Agent")
                    if (ua.isNullOrEmpty() || ua.startsWith("okhttp", ignoreCase = true)) {
                        header("User-Agent", USER_AGENT)
                    }
                }
                .build()
            chain.proceed(request)
        }
        return rateLimit(2, 1.seconds)
    }

    /** Görsel CDN'leri site referansı bekler; KeiSource headersBuilder'ı finaldir. */
    override fun imageRequest(page: Page): Request = super.imageRequest(page).newBuilder()
        .header("Referer", "$baseUrl/")
        .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
        .build()

    protected open val seriesPath = "seri"

    protected open val catalogLimit = 24

    /** API sayfalamayı yok sayarsa sonsuz kaydırmayı engelleyen emniyet payı. */
    protected open val maxCatalogPages = 40

    // ============================== Katalog ===============================

    override suspend fun getPopularManga(page: Int): MangasPage = catalogPage(page, sort = null)

    override suspend fun getLatestUpdates(page: Int): MangasPage = catalogPage(page, sort = "createdAt")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return catalogPage(page, sort = null)

        val url = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("q", trimmed)
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", catalogLimit.toString())
            .build()
        return parseCatalog(client.get(url))
    }

    private suspend fun catalogPage(page: Int, sort: String?): MangasPage {
        val builder = "$baseUrl/api/series".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", catalogLimit.toString())
        if (!sort.isNullOrBlank()) builder.addQueryParameter("sort", sort)

        val response = client.get(builder.build())
        if (sort != null && !response.isSuccessful) {
            // Bazı dağıtımlarda bilinmeyen sort parametresi 400 dönebilir;
            // sıralama olmadan devam etmek hiç dönmekten iyidir.
            return catalogPage(page, sort = null)
        }
        return parseCatalog(response)
    }

    private fun parseCatalog(response: Response): MangasPage {
        val body = response.parseAs<CatalogResponseDto>()
        val page = pageOf(response.request.url)
        val mangas = body.data.map { it.toSManga(baseUrl) }
        val hasNext = body.success && body.data.size >= catalogLimit && page < maxCatalogPages
        return MangasPage(mangas, hasNext)
    }

    private fun pageOf(url: HttpUrl): Int = url.queryParameter("page")?.toIntOrNull() ?: 1

    protected fun SeriesDto.toSManga(base: String): SManga = SManga.create().apply {
        title = this@toSManga.title.trim()
        setUrlWithoutDomain("$base/$seriesPath/$slug/")
        thumbnail_url = this@toSManga.image?.let { img ->
            if (img.startsWith("http")) img else base + img
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList()

    // ============================== Detay =================================

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) return null
        val segments = url.pathSegments.filter { it.isNotEmpty() }
        if (segments.size < 2 || segments[0] != seriesPath) return null
        val manga = SManga.create().apply { this.url = "/$seriesPath/${segments[1]}/" }
        return fetchMangaUpdate(manga, emptyList(), true, false).manga.apply {
            this.url = manga.url
            initialized = true
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.get(baseUrl + manga.url)
        if (response.code == 403) {
            throw Exception("Site koruması isteği engelledi (403). Site tarayıcıda açılıyor mu kontrol edin.")
        }
        val html = response.body?.string().orEmpty()
        val flight = flightOf(html)

        val updated = parseDetails(html, flight, manga.url).apply { url = manga.url }
        val updatedChapters = if (fetchChapters) parseChapters(flight, manga.url) else chapters
        return SMangaUpdate(updated, updatedChapters)
    }

    protected open fun parseDetails(html: String, flight: String, mangaUrl: String): SManga = SManga.create().apply {
        val slug = mangaUrl.trim('/').substringAfterLast('/')
        // Seri kaydı "title":"...","slug":"<slug>" çifti halinde gömülür;
        // başlığı bölüm kayıtlarından ayırt etmek için çift aranır.
        title = seriesTitle(flight, slug)
            ?: htmlTitle(html)
            ?: slug

        val document = Jsoup.parse(html, baseUrl)

        description = buildString {
            flightString(flight, "about")?.let { append(it.replace("\\n", "\n").trim()) }
            val alts = flightArray(flight, "alternativeNames")
                ?.parseAs<List<String>>()
                .orEmpty()
                .filter { it.isNotBlank() }
            val original = flightString(flight, "originalName")
            val altList = (alts + listOfNotNull(original?.takeIf { it.isNotBlank() }))
                .distinct()
                .filterNot { it.equals(title, ignoreCase = true) }
            if (altList.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Alternatif Başlıklar: ").append(altList.joinToString(", "))
            }
        }

        if (description.isNullOrBlank()) {
            description = document.selectFirst("meta[property=og:description], meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }

        genre = flightArray(flight, "genre")
            ?.parseAs<List<String>>()
            .orEmpty()
            .filter { it.isNotBlank() }
            .joinToString(", ")

        author = flightString(flight, "author").orEmpty().trim()
        artist = flightString(flight, "artist").orEmpty().trim()

        status = statusOf(flight)

        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
    }

    private fun statusOf(flight: String): Int {
        // "type":"Manhwa","status":"Devam Ediyor","country":"kr" üçlüsü seri kaydına aittir.
        val statusText = Regex("\"type\":\"[^\"]*\",\"status\":\"([^\"]*)\"")
            .find(flight)?.groupValues?.get(1).orEmpty().lowercase()
        return when {
            statusText.contains("devam") || statusText.contains("güncel") || statusText.contains("ongoing") -> SManga.ONGOING
            statusText.contains("tamamland") || statusText.contains("bitti") || statusText.contains("completed") -> SManga.COMPLETED
            statusText.contains("ara ver") || statusText.contains("beklemede") || statusText.contains("sezon") || statusText.contains("hiatus") -> SManga.ON_HIATUS
            statusText.contains("bırakıldı") || statusText.contains("iptal") || statusText.contains("dropped") -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    /**
     * <title> yedeği: "X Türkçe Oku – Tüm Bölümler | Site" biçimini X'e indirger.
     */
    private fun htmlTitle(html: String): String? {
        val raw = Regex("<title>([^<]+)").find(html)?.groupValues?.get(1)?.trim() ?: return null
        return raw.substringBefore(" Türkçe Oku")
            .substringBefore(" |")
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    // ============================== Bölümler ==============================

    protected open fun parseChapters(flight: String, mangaUrl: String): List<SChapter> {
        val array = flightArray(flight, "initialChapters")
            ?: return emptyList()
        val dtos = array.parseAs<List<ChapterDto>>()
        val seriesSlug = mangaUrl.trim('/').substringAfterLast('/')
        return dtos.mapNotNull { dto ->
            val number = dto.chapterNumber ?: return@mapNotNull null
            SChapter.create().apply {
                setUrlWithoutDomain("/$seriesPath/$seriesSlug/bolum/${formatNumber(number)}")
                name = dto.title?.trim()?.takeIf { it.isNotEmpty() } ?: "Bölüm ${formatNumber(number)}"
                chapter_number = number
                date_upload = parseDate(dto.createdAt) ?: parseDate(dto.updatedAt)
            }
        }.sortedByDescending { it.chapter_number }
    }

    protected open fun formatNumber(number: Float): String = if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return runCatching { Instant.parse(raw).toEpochMilli() }.getOrDefault(0L)
    }

    // ============================== Sayfalar ==============================

    /**
     * Sitenin okuyucusuyla birebir aynı akış (reader chunk kanıtı
     * docs/html-dumps/duckscans/chunks/): /api/chapters imzalı sayfa yolları
     * döner (?v=…&t=…&s=…&k=…&sv=5). İmzasız yollar 403 yer; bu yüzden seri
     * sayfası uçuşundaki düz `pages` yolları görüntü için KULLANILMAZ.
     */
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val segments = chapter.url.trim('/').split('/')
        if (segments.size < 2 || segments[0] != seriesPath) {
            throw Exception("Beklenmeyen bölüm adresi: ${chapter.url}")
        }
        val seriesSlug = segments[1]

        val apiUrl = "$baseUrl/api/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("series", seriesSlug)
            .addQueryParameter("chapter", formatNumber(chapter.chapter_number))
            .addQueryParameter("includepages", "true")
            .addQueryParameter("limit", "1")
            .build()

        val response = client.get(apiUrl, apiHeaders(chapter.url), ensureSuccess = false)
        if (response.code == 403) {
            throw Exception("Site koruması isteği engelledi (403). Kaynağa bir kez daha denemek için dokunun.")
        }

        if (response.isSuccessful) {
            val pages = response.parseAs<ChaptersApiDto>()
                .data
                .firstOrNull { it.pages.isNotEmpty() }
                ?.pages
                ?.filter { it.isNotBlank() }
            if (!pages.isNullOrEmpty()) {
                return pages.mapIndexed { index, path ->
                    val imageUrl = if (path.startsWith("http")) path else baseUrl + path
                    Page(index, imageUrl = imageUrl)
                }
            }
        }

        throw Exception(
            "Bu bölümün görselleri sitede korumalı (oturum veya coin gerekiyor). " +
                "Uygulamada kaynağın WebView'ını açıp Discord ile giriş yapın; çerezler " +
                "otomatik kullanılır. Girişten sonra bölümü yeniden açın.",
        )
    }

    /** Okuyucu API'sinin beklediği CORS/origin başlık seti. */
    private fun apiHeaders(refererPath: String): Headers = Headers.Builder()
        .add("Referer", baseUrl + refererPath)
        .add("Origin", baseUrl)
        .add("Accept", "*/*")
        .add("Sec-Fetch-Dest", "empty")
        .add("Sec-Fetch-Mode", "cors")
        .add("Sec-Fetch-Site", "same-origin")
        .build()

    // ======================= Uçuş verisi yardımcıları =====================

    private val flightRegex = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)</script>""", RegexOption.DOT_MATCHES_ALL)

    /** Next.js uçuş parçalarını birleştirip JS dizge kaçışlarını çözer. */
    protected fun flightOf(html: String): String {
        if (!html.contains("self.__next_f")) return ""
        return flightRegex.findAll(html).joinToString("") { match ->
            unescapeJsString(match.groupValues[1])
        }
    }

    private fun unescapeJsString(raw: String): String {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'u' -> {
                        val hex = raw.substringOrNull(i + 2, i + 6)
                        if (hex != null && hex.length == 4) {
                            val decoded = hex.toIntOrNull(16)?.toChar()
                            if (decoded != null) sb.append(decoded)
                            i += 6
                        } else {
                            sb.append(next)
                            i += 2
                        }
                    }

                    'n' -> {
                        sb.append('\n')
                        i += 2
                    }

                    't' -> {
                        sb.append('\t')
                        i += 2
                    }

                    'r' -> {
                        sb.append('\r')
                        i += 2
                    }

                    'b' -> {
                        sb.append('\b')
                        i += 2
                    }

                    'f' -> {
                        sb.append('\u000C')
                        i += 2
                    }

                    else -> {
                        sb.append(next)
                        i += 2
                    }
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Uçuş verisinde "key":"..." biçimli ilk dizgeyi kaçışları çözerek döner. */
    protected fun flightString(flight: String, key: String): String? {
        val keyIndex = flight.indexOf("\"$key\":")
        if (keyIndex < 0) return null
        var i = flight.indexOf('"', keyIndex + key.length + 2)
        if (i < 0) return null
        i++
        val sb = StringBuilder()
        while (i < flight.length) {
            val c = flight[i]
            if (c == '\\') {
                val next = flight.getOrNull(i + 1) ?: return sb.toString()
                when (next) {
                    '"' -> sb.append('"')

                    '\\' -> sb.append('\\')

                    '/' -> sb.append('/')

                    'n' -> sb.append('\n')

                    't' -> sb.append('\t')

                    'u' -> {
                        val hex = flight.substringOrNull(i + 2, i + 6)
                        if (hex != null && hex.length == 4) {
                            sb.append(hex.toIntOrNull(16)?.toChar() ?: "")
                            i += 4
                        }
                    }

                    else -> sb.append(next)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /** Seri başlığını "title":"...","slug":"<slug>" çiftinden çıkarır. */
    private fun seriesTitle(flight: String, slug: String): String? {
        val pattern = Regex("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\",\"slug\":\"${Regex.escape(slug)}\"")
        val match = pattern.find(flight) ?: return null
        return unescapeJsString(match.groupValues[1]).trim().takeIf { it.isNotEmpty() }
    }

    /** "key":[ ... ] dizisini köşeli parantez eşleştirerek ham JSON olarak döner. */
    protected fun flightArray(flight: String, key: String): String? {
        val keyIndex = flight.indexOf("\"$key\":")
        if (keyIndex < 0) return null
        val start = flight.indexOf('[', keyIndex)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until flight.length) {
            val c = flight[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> escaped = true

                c == '"' -> inString = !inString

                !inString && c == '[' -> depth++

                !inString && c == ']' -> {
                    depth--
                    if (depth == 0) return flight.substring(start, i + 1)
                }
            }
        }
        return null
    }

    private fun String.substringOrNull(start: Int, end: Int): String? = if (start in indices && end <= length && start < end) substring(start, end) else null

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}
