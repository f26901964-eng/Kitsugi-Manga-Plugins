package eu.kanade.tachiyomi.extension.tr.mangitto

import eu.kanade.tachiyomi.extension.tr.mangitto.MangittoHtml.text
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getString
import keiyoushi.utils.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class Mangitto : KeiSource() {

    private val apiUrl get() = "$baseUrl/api/manga"

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val mangas = homeManga().map { it.toHomeManga() }
        val start = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        return MangasPage(mangas.drop(start).take(PAGE_SIZE), start + PAGE_SIZE < mangas.size)
    }

    private suspend fun homeManga() = MangittoHtml.mangaObjects(client.get(baseUrl).asJsoup())

    private fun kotlinx.serialization.json.JsonObject.toHomeManga() = SManga.create().apply {
        url = text("slug")!!
        title = text("title")!!
        thumbnail_url = text("coverImage")?.let { baseUrl.toHttpUrl().resolve(it)?.toString() }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val skip = (page - 1) * PAGE_SIZE
        val data = client.get("$apiUrl/latest?skip=$skip&take=$PAGE_SIZE").parseAs<MangttoLatestData>()

        // The API returns a list of chapters, so we distinct by slug to avoid duplicates.
        val mangas = data.chapters.map { it.manga }.distinctBy { it.slug }.map { it.toSManga() }

        return MangasPage(mangas, skip + data.chapters.size < data.total)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$apiUrl/search".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("q", query)

        filters.firstInstanceOrNull<GenreFilter>()?.getQuery()
            ?.takeIf { it.isNotEmpty() }
            ?.let { url.addQueryParameter("genres", it) }

        if (filters.firstInstanceOrNull<AdultFilter>()?.state == true) {
            url.addQueryParameter("isAdult", "true")
        }

        if (filters.firstInstanceOrNull<CompletedFilter>()?.state == true) {
            url.addQueryParameter("isFinished", "true")
        }

        filters.firstInstanceOrNull<ScoreFilter>()?.state
            ?.takeIf { it.isNotBlank() }
            ?.let { url.addQueryParameter("meanScore", it.trim()) }

        filters.firstInstanceOrNull<DateFilter>()?.state
            ?.takeIf { it.isNotBlank() }
            ?.let { url.addQueryParameter("releaseDate", it.trim()) }

        val data = client.get(url.build()).parseAs<MangttoSearchData>()

        // No search HTML page exists. Keep the site's JSON search for the whole
        // archive, and include homepage aliases (English/native/romaji) on page one.
        // Do not silently turn a failed API request into an incomplete local search.
        val aliases = if (page == 1 && query.isNotBlank() && filters.isEmpty()) {
            homeManga().filter { MangittoHtml.matches(it, query) }.map { it.toHomeManga() }
        } else {
            emptyList()
        }
        return MangasPage(
            (data.hits.map { it.document.toSManga() } + aliases).distinctBy { it.url },
            page * SEARCH_PAGE_SIZE < data.estimatedTotalHits,
        )
    }

    override val supportsFilterFetching = true

    override suspend fun fetchFilterData(): JsonElement = client.get("$baseUrl/api/genres").parseAs()

    override fun getFilterList(data: JsonElement?): FilterList {
        val genres = data?.parseAs<MangttoGenres>()?.genres.orEmpty()
        return FilterList(
            buildList {
                if (genres.isNotEmpty()) {
                    add(GenreFilter(genres))
                }
                add(AdultFilter())
                add(CompletedFilter())
                add(ScoreFilter())
                add(DateFilter())
            },
        )
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host || url.pathSegments.firstOrNull() != "manga") {
            return null
        }
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null

        return client.get("$apiUrl/$slug").parseAs<MangttoDetailData>().toSManga().apply { initialized = true }
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/manga/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl/manga/${chapter.memo.getString("mangaSlug")}/${chapter.url}"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = coroutineScope {
        val details = async {
            if (fetchDetails) client.get("$apiUrl/${manga.url}").parseAs<MangttoDetailData>().toSManga() else manga
        }
        val chapterList = async {
            if (fetchChapters) fetchAllChapters(manga.url) else chapters
        }

        SMangaUpdate(details.await(), chapterList.await())
    }

    private suspend fun fetchAllChapters(slug: String): List<SChapter> {
        val allChapters = linkedMapOf<String, SChapter>()
        var skip = 0

        while (true) {
            val data = client.get("$apiUrl/$slug/chapters?skip=$skip&take=$PAGE_SIZE").parseAs<MangttoChapterPageData>()

            val batch = data.chapters.map { it.toSChapter(slug) }
            check(batch.isEmpty() || batch.any { it.url !in allChapters }) {
                "Mangitto: bölüm sayfalaması ilerlemiyor"
            }
            batch.forEach { allChapters[it.url] = it }
            skip += data.chapters.size

            if (data.chapters.isEmpty() || skip >= data.total) break
        }

        // The API returns chapters in ascending order.
        return allChapters.values.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val slug = chapter.memo.getString("mangaSlug")
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = MangittoHtml.readerImages(document, slug, chapter.url)
        if (images.isNotEmpty()) return images.mapIndexed { index, url -> Page(index, imageUrl = url) }

        // Client-rendered readers can omit images; retain the existing upload API.
        val data = client.get("$apiUrl/$slug/${chapter.url}").parseAs<MangttoPageData>()
        val upload = data.uploads.firstOrNull { it.fileLength > 0 }
            ?: error("Mangitto: bu bölüm için okunabilir görsel bulunamadı")

        return (1..upload.fileLength).map { pageNum ->
            Page(pageNum - 1, imageUrl = "${data.cdn.trimEnd('/')}/manga/$slug/${chapter.url}/$pageNum-${upload.fansubId}.webp")
        }
    }

    companion object {
        private const val PAGE_SIZE = 50
        private const val SEARCH_PAGE_SIZE = 42
    }
}
