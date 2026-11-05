package eu.kanade.tachiyomi.extension.tr.mangadiyari

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
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getStringOrNull
import keiyoushi.utils.parseAs
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient

@Source
abstract class MangaDiyari : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = apply {
        rateLimit(2)
    }

    // The JSON endpoints are the same ones the site's own reader calls, so the client presents itself
    // the way that page does: XHR accept headers and a Turkiye locale.
    override fun Headers.Builder.configureHeaders() = apply {
        set("Accept", "application/json, text/plain, */*")
        set("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        set("X-Requested-With", "XMLHttpRequest")
    }

    override suspend fun getPopularManga(page: Int): MangasPage = seriesPage(page, sort = "popular")

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val response = client.get(latestUpdatesUrl(baseUrl, page)).parseAs<LatestUpdatesResponse>()
        return MangasPage(response.updates.map { it.toSManga(baseUrl) }, response.hasMore)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "latest"
        val type = filters.firstInstanceOrNull<TypeFilter>()?.toUriPart().orEmpty()
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart().orEmpty()
        val genres = filters.firstInstanceOrNull<GenreFilter>()?.toUriPart().orEmpty()
        return seriesPage(page, query.trim(), sort, type, status, genres)
    }

    private suspend fun seriesPage(
        page: Int,
        query: String = "",
        sort: String,
        type: String = "",
        status: String = "",
        genres: String = "",
    ): MangasPage {
        val url = seriesListUrl(baseUrl, page, query, sort, type, status, genres)
        val response = client.get(url).parseAs<SeriesListResponse>()
        return MangasPage(response.series.map { it.toSManga(baseUrl) }, response.hasMore)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (url.host != baseUrl.toHttpUrl().host) {
            return null
        }
        if (url.pathSegments.getOrNull(0)?.lowercase() !in SERIES_PATHS) {
            return null
        }
        val slug = url.pathSegments.getOrNull(1)?.takeIf { it.isNotBlank() } ?: return null
        val manga = SManga.create().apply { this.url = slug }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getMangaUrl(manga: SManga): String = "$baseUrl/series/${manga.url}"

    override fun getChapterUrl(chapter: SChapter): String {
        val slug = chapter.memo.getStringOrNull("slug") ?: return "$baseUrl/seri/${chapter.url}"
        val number = chapter.memo.getStringOrNull("number") ?: chapter.url
        return readerUrl(baseUrl, slug, number)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val details = client.get(seriesDetailsUrl(baseUrl, manga.url)).parseAs<SeriesDetailsResponse>()
        return SMangaUpdate(
            details.series.toSManga(baseUrl),
            details.chapters.toEntries(details.series.slug).map { it.toSChapter() },
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.get(chapterPagesUrl(baseUrl, chapter.url)).parseAs<ChapterPagesResponse>()
        return response.pages.toPageEntries(baseUrl).toPages(getChapterUrl(chapter))
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(),
    )

    companion object {
        private val SERIES_PATHS = setOf("series", "seri")
    }
}
