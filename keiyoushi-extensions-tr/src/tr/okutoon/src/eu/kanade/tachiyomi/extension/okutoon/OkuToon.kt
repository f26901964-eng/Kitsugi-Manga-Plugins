package eu.kanade.tachiyomi.extension.tr.okutoon

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class OkuToon : KeiSource() {

    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("tr"))

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(listingUrl(page, "popular").build())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(listingUrl(page, "updated").build())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val url = listingUrl(page, "updated")

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        val sortFilter = filters.firstInstanceOrNull<SortFilter>()
        if (sortFilter != null) {
            url.setQueryParameter("sira", sortFilter.toUriPart())
        }

        val statusFilter = filters.firstInstanceOrNull<StatusFilter>()
        if (statusFilter != null && statusFilter.toUriPart().isNotEmpty()) {
            url.addQueryParameter("durum", statusFilter.toUriPart())
        }

        val genreFilter = filters.firstInstanceOrNull<GenreFilter>()
        genreFilter?.state?.filter { it.state }?.forEach {
            url.addQueryParameter("k[]", it.id)
        }

        return parseMangaList(url.build())
    }

    // The site splits listings with "/tur?sayfa=N"; there is no path-based paging.
    private fun listingUrl(page: Int, sort: String): HttpUrl.Builder =
        "$baseUrl/tur".toHttpUrl().newBuilder()
            .addQueryParameter("sira", sort)
            .apply { if (page > 1) addQueryParameter("sayfa", page.toString()) }

    private suspend fun parseMangaList(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(".series-grid .series-card").map { element ->
            SManga.create().apply {
                // The card itself is the <a>; selectFirst("a") would look for a nested link.
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".series-card-title")!!.text()
                thumbnail_url = element.selectFirst(".series-card-cover img")?.attr("abs:src")
            }
        }
        // Only an active "Sonraki" link means another page exists; the disabled state is a <span>.
        val hasNextPage = document.selectFirst("nav.pagination a.pagination-btn[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()

        return SMangaUpdate(mangaDetailsParse(document), chapterListParse(document))
    }

    private fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst(".series-detail-title")!!.text()
        author = document.selectFirst(".series-detail-author")?.text()?.takeIf { it != "Bilinmiyor" }
        description = document.selectFirst("[data-series-description-content]")?.text()
        genre = document.select(".series-detail-genres .tag").joinToString { it.text() }
        status = parseStatus(document.selectFirst(".series-detail-kicker .series-status")?.className())
        thumbnail_url = document.selectFirst(".series-detail-cover img")?.attr("abs:src")
    }

    private fun parseStatus(className: String?) = when {
        className?.contains("series-status--ongoing") == true -> SManga.ONGOING
        className?.contains("series-status--completed") == true -> SManga.COMPLETED
        else -> SManga.UNKNOWN
    }

    // The chapter <a> sits inside a wrapper div, so the row itself carries .chapter-item.
    private fun chapterListParse(document: Document): List<SChapter> = document.select(".chapter-item").map { element ->
        SChapter.create().apply {
            setUrlWithoutDomain(element.selectFirst("a.chapter-item-link")!!.attr("abs:href"))
            name = element.selectFirst(".chapter-title")!!.text()
            date_upload = dateFormat.tryParseDate(element.selectFirst(".chapter-date")?.text())
        }
    }

    // The reader is Cloudflare-protected and the pages are lazy-loaded, so the image list has to
    // be read from the lazy attributes too, not only from src.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select("#readerPages img.reader-page")
            .ifEmpty { document.select("img.reader-page") }
        return images.mapIndexedNotNull { index, img ->
            listOf("data-src", "data-lazy-src", "data-original", "src")
                .asSequence()
                .map { img.attr("abs:$it").trim() }
                .firstOrNull { it.startsWith("http") && !it.startsWith("data:") }
                ?.let { Page(index, imageUrl = it) }
        }
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(getGenreList()),
    )
}
