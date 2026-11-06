package eu.kanade.tachiyomi.extension.tr.sadscans

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
import keiyoushi.utils.tryParse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.time.Instant

/**
 * SadScans (https://sadscans.net) — Next.js + tRPC tabanlı "iken" manga platformu.
 *
 * Güncel site yapısı:
 *  - Seri listesi / arama: `GET /api/query?page=&perPage=&searchTerm=&orderBy=&orderDirection=`
 *  - Seri detayları:        `GET /api/post?postSlug={slug}`
 *  - Bölüm listesi:        `GET /api/chapters?postId={id}&skip=0&take=900&order=desc&userid=`
 *  - Sayfa resimleri:      `GET /api/chapter?chapterId={id}` (yedek: okuyucu sayfasındaki
 *    NEXT/RSC gömülü JSON'u veya HTML img etiketleri)
 *  - Web adresleri: `/seriler/{slug}` ve `/reader/{seri}/{bolum}/{id}`
 *  - Görseller `/api/uploads/assets/...` altında; site Cloudflare arkasında.
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

    // ============================== Popular / Latest / Search ==============================

    override suspend fun getPopularManga(page: Int): MangasPage =
        fetchQuery(page, "totalViews", "desc", null)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        fetchQuery(page, "updatedAt", "desc", null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val (orderBy, orderDirection) = filters.firstInstanceOrNull<SortFilter>()?.selected
            ?: ("updatedAt" to "desc")
        return fetchQuery(page, orderBy, orderDirection, query.trim().ifBlank { null })
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(SortFilter())

    private suspend fun fetchQuery(
        page: Int,
        orderBy: String,
        orderDirection: String,
        query: String?,
    ): MangasPage {
        val url = "$baseUrl/api/query".toHttpUrl().newBuilder().apply {
            addQueryParameter("page", page.toString())
            addQueryParameter("perPage", PAGE_SIZE.toString())
            addQueryParameter("searchTerm", query.orEmpty())
            addQueryParameter("orderBy", orderBy)
            addQueryParameter("orderDirection", orderDirection)
        }.build()
        val dto = client.get(url, apiHeaders).parseAs<QueryResponseDto>()
        val mangas = dto.posts
            .filter { !it.isNovelEntry && !it.slug.isNullOrBlank() }
            .map { it.toSManga() }
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
        val post = client.get(
            "$baseUrl/api/post".toHttpUrl().newBuilder()
                .addQueryParameter("postSlug", slug)
                .build(),
            apiHeaders,
        ).parseAs<PostResponseDto>().post ?: error("SadScans: seri bulunamadı: $slug")

        val details = if (fetchDetails) post.toSMangaDetails(slug) else manga
        val chapterList = if (fetchChapters) fetchChapters(slug, post.idString) else chapters
        return SMangaUpdate(details.apply { url = manga.url }, chapterList)
    }

    private suspend fun fetchChapters(seriesSlug: String, postId: String?): List<SChapter> {
        if (postId.isNullOrBlank()) return emptyList()
        val url = "$baseUrl/api/chapters".toHttpUrl().newBuilder().apply {
            addQueryParameter("postId", postId)
            addQueryParameter("skip", "0")
            addQueryParameter("take", "900")
            addQueryParameter("order", "desc")
            addQueryParameter("userid", "")
        }.build()
        val dto = client.get(url, apiHeaders).parseAs<ChaptersResponseDto>()
        val chapters = dto.post?.chapters.orEmpty()
            .filter { !it.locked && !it.slug.isNullOrBlank() }
        return chapters.sortedByDescending { it.number ?: 0f }.map { it.toSChapter(seriesSlug) }
    }

    // ============================== Reader pages ==============================

    override fun getChapterUrl(chapter: SChapter): String = baseUrl + chapter.url

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterId = chapter.url.substringAfterLast('/')

        // 1) JSON API
        val apiImages = runCatching {
            val url = "$baseUrl/api/chapter".toHttpUrl().newBuilder()
                .addQueryParameter("chapterId", chapterId)
                .build()
            client.get(url, apiHeaders).parseAs<ChapterResponseDto>().chapter
        }.getOrNull()
        if (apiImages != null) {
            if (apiImages.isLocked == true || apiImages.isAccessible == false) {
                error("SadScans: bu bölümün kilidi açılmamış")
            }
            val urls = apiImages.images.sortedBy { it.order ?: Int.MAX_VALUE }
                .mapNotNull { it.anyUrl?.let(::absolute) }
            if (urls.isNotEmpty()) return urls.mapIndexed { index, u -> Page(index, imageUrl = u) }
        }

        // 2) Okuyucu sayfası: NEXT/RSC gömülü JSON'u
        val doc = client.get(getChapterUrl(chapter)).asJsoup()
        embeddedImages(doc)?.let { urls ->
            if (urls.isNotEmpty()) return urls.mapIndexed { index, u -> Page(index, imageUrl = u) }
        }

        // 3) HTML içindeki img etiketleri
        val imgUrls = doc.select("img[src*='/api/uploads']")
            .mapNotNull { img -> img.attr("src").ifBlank { img.attr("data-src") }.takeIf { it.isNotBlank() }?.let(::absolute) }
            .distinct()
        if (imgUrls.isNotEmpty()) return imgUrls.mapIndexed { index, u -> Page(index, imageUrl = u) }

        error("SadScans: bölüm görselleri bulunamadı: ${chapter.url}")
    }

    /** Okuyucu sayfasındaki script bloklarında "images" anahtarından sonraki JSON dizisini çıkarır. */
    private fun embeddedImages(doc: org.jsoup.nodes.Document): List<String>? {
        val script = doc.select("script").firstOrNull { it.data().contains("images") } ?: return null
        val data = script.data()
        val keyIndex = data.indexOf("images")
        val start = data.indexOf('[', keyIndex)
        if (start == -1) return null
        var depth = 1
        var i = start + 1
        while (i < data.length && depth > 0) {
            when (data[i]) {
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        val json = data.substring(start, i).replace("\\/", "/").replace("\\\"", "\"")
        val element = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return null
        return when (element) {
            is JsonArray -> element.mapNotNull { item -> item.imageUrl()?.let(::absolute) }
            is JsonObject -> element["images"]?.imageUrlList()?.map(::absolute)
            else -> null
        }
    }

    private fun JsonElement.imageUrl(): String? = when (this) {
        is JsonObject -> (this["url"] ?: this["src"] ?: this["image"])?.jsonPrimitive?.contentOrNull
        is JsonPrimitive -> contentOrNull?.takeIf { it.startsWith("http") || it.startsWith("/") }
        else -> null
    }

    private fun JsonElement.imageUrlList(): List<String>? = when (this) {
        is JsonArray -> mapNotNull { it.imageUrl() }
        else -> null
    }

    // ============================== Helpers ==============================

    private fun absolute(path: String): String = when {
        path.startsWith("http") -> path
        else -> baseUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    private fun PostDto.toSManga(): SManga = SManga.create().apply {
        url = "/seriler/$slug"
        title = postTitle?.trim().orEmpty()
        thumbnail_url = featuredImage?.let(::absolute)
    }

    private fun PostDto.toSMangaDetails(fallbackSlug: String): SManga = SManga.create().apply {
        url = "/seriler/${slug ?: fallbackSlug}"
        title = postTitle?.trim()?.ifBlank { null } ?: fallbackSlug
        thumbnail_url = featuredImage?.let(::absolute)
        description = this@toSMangaDetails.postContent ?: this@toSMangaDetails.description
        author = listOfNotNull(
            this@toSMangaDetails.author?.trim()?.ifBlank { null },
            this@toSMangaDetails.artist?.trim()?.ifBlank { null },
        ).joinToString().ifBlank { null }
        val genreNames = this@toSMangaDetails.genres.orEmpty().mapNotNull { it.name?.trim()?.ifBlank { null } }.distinct()
        if (genreNames.isNotEmpty()) genre = genreNames.joinToString()
        status = when (this@toSMangaDetails.seriesStatus?.uppercase()) {
            "ONGOING" -> SManga.ONGOING
            "COMPLETED" -> SManga.COMPLETED
            "HIATUS" -> SManga.ON_HIATUS
            "DROPPED", "CANCELLED" -> SManga.CANCELLED
            else -> SManga.UNKNOWN
        }
    }

    private fun ChapterDto.toSChapter(seriesSlug: String): SChapter = SChapter.create().apply {
        val chapterSlug = slug.orEmpty()
        url = "/reader/$seriesSlug/$chapterSlug/${idString.orEmpty()}"
        val num = number ?: 0f
        name = title?.trim()?.ifBlank { null } ?: "Bölüm ${num.trimNumber()}"
        chapter_number = num
        date_upload = createdAt?.let { raw ->
            Instant.tryParse(raw)
                ?: SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).parse(raw.substringBefore('T'))?.time
                ?: 0L
        } ?: 0L
    }

    private fun Float.trimNumber(): String =
        if (this == this.toLong().toFloat()) this.toLong().toString() else this.toString()

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
        private const val PAGE_SIZE = 24
    }
}
