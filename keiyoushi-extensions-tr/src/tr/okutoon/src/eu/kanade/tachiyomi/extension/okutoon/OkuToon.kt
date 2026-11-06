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
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale

@Source
abstract class OkuToon : KeiSource() {

    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("tr"))

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(
        "$baseUrl/tur?sira=popular".toHttpUrl().withPage(page),
    )

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(
        "$baseUrl/tur?sira=updated".toHttpUrl().withPage(page),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "updated"
        val url = "$baseUrl/tur".toHttpUrl().newBuilder()
            .addQueryParameter("sira", sort)
            .apply {
                if (page > 1) addQueryParameter("sayfa", page.toString())
                if (query.isNotEmpty()) addQueryParameter("q", query)
            }

        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart().orEmpty()
        if (status.isNotEmpty()) {
            url.addQueryParameter("durum", status)
        }

        filters.firstInstanceOrNull<GenreFilter>()?.state?.filter { it.state }?.forEach {
            url.addQueryParameter("k[]", it.id)
        }

        return parseMangaList(url.build())
    }

    // The site pages listings with the "sayfa" query parameter; there is no path based paging.
    private fun HttpUrl.withPage(page: Int): HttpUrl = if (page <= 1) {
        this
    } else {
        newBuilder().addQueryParameter("sayfa", page.toString()).build()
    }

    private suspend fun parseMangaList(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(".series-grid .series-card").map { element ->
            SManga.create().apply {
                // The card itself is the <a>, so a nested "a" lookup would find nothing.
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst(".series-card-title")!!.text()
                thumbnail_url = element.selectFirst(".series-card-cover img")?.attr("abs:src")
            }
        }
        // Only an active "Sonraki" link means another page; its disabled state is a <span>.
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

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select("#readerPages img.reader-page")
            .ifEmpty { document.select("img.reader-page") }
        return images.mapIndexedNotNull { index, image ->
            pageImageUrl(image)?.let { Page(index, imageUrl = it) }
        }
    }

    // The reader loads lazily, so the address can sit in a data attribute instead of src.
    private fun pageImageUrl(image: Element): String? = listOf("data-src", "data-lazy-src", "data-original", "src")
        .map { image.attr("abs:$it").trim() }
        .firstOrNull { it.startsWith("http") && !it.startsWith("data:") }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        StatusFilter(),
        GenreFilter(getGenreList()),
    )
}
