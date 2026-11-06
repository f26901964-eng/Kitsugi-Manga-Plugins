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

    // Chapter dates read like "26 Mart 2026".
    private val dateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("tr"))

    override suspend fun getPopularManga(page: Int): MangasPage = parseMangaList(browseUrl(page, "popular").build())

    override suspend fun getLatestUpdates(page: Int): MangasPage = parseMangaList(browseUrl(page, "updated").build())

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart() ?: "updated"
        val url = browseUrl(page, sort)

        if (query.isNotEmpty()) {
            url.addQueryParameter("q", query)
        }

        filters.firstInstanceOrNull<TypeFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
            url.addQueryParameter("tip", it)
        }

        filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()?.takeIf { it.isNotEmpty() }?.let {
            url.addQueryParameter("durum", it)
        }

        filters.firstInstanceOrNull<GenreFilter>()?.state?.filter { it.state }?.forEach {
            url.addQueryParameter("k[]", it.id)
        }

        return parseMangaList(url.build())
    }

    // The catalogue lives under /tur and pages with the "sayfa" query parameter.
    private fun browseUrl(page: Int, sort: String): HttpUrl.Builder = "$baseUrl/tur".toHttpUrl().newBuilder()
        .addQueryParameter("sira", sort)
        .apply { if (page > 1) addQueryParameter("sayfa", page.toString()) }

    private suspend fun parseMangaList(url: HttpUrl): MangasPage {
        val document = client.get(url).asJsoup()
        val mangas = document.select(".series-grid .series-card").mapNotNull(::seriesFromCard)
        // Only an active "Sonraki" link means another page; the disabled state is a <span>.
        val hasNextPage = document.selectFirst("nav.pagination a.pagination-btn[rel=next]") != null
        return MangasPage(mangas, hasNextPage)
    }

    // The card itself is the <a>, so a nested "a" lookup would find nothing.
    private fun seriesFromCard(card: Element): SManga? {
        val href = card.attr("abs:href").trim().takeIf { it.isNotEmpty() } ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(href)
            title = card.selectFirst(".series-card-title")?.text()?.trim()
                ?: card.selectFirst(".series-card-cover img")?.attr("alt")?.trim()
                ?: return null
            thumbnail_url = card.selectFirst(".series-card-cover img")?.attr("abs:src")
            genre = card.selectFirst(".series-card-author")?.text()?.trim()?.takeIf { it != "Bilinmiyor" }
        }
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
        title = document.selectFirst(".series-detail-title")?.text()?.trim().orEmpty()
        author = document.selectFirst(".series-detail-author")?.text()?.trim()?.takeIf { it.isNotEmpty() && it != "Bilinmiyor" }
        description = document.selectFirst("[data-series-description-content]")?.text()
        // Genres are plain .tag-link anchors inside the genres container, not .tag.
        genre = document.select(".series-detail-genres a").joinToString(", ") { it.text() }
        status = parseStatus(document.selectFirst(".series-detail-kicker .series-status")?.className())
        thumbnail_url = document.selectFirst(".series-detail-cover img")?.attr("abs:src")
    }

    private fun parseStatus(className: String?) = when {
        className?.contains("series-status--ongoing") == true -> SManga.ONGOING
        className?.contains("series-status--completed") == true -> SManga.COMPLETED
        className?.contains("series-status--hiatus") == true -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }

    // Each row is div.chapter-item holding a.chapter-item-link, .chapter-title and .chapter-date.
    private fun chapterListParse(document: Document): List<SChapter> = document.select(".chapter-item").mapNotNull { row ->
        val href = row.selectFirst("a.chapter-item-link")?.attr("abs:href")?.trim()
            ?: row.selectFirst("a")?.attr("abs:href")?.trim()
            ?: return@mapNotNull null
        SChapter.create().apply {
            setUrlWithoutDomain(href)
            name = row.selectFirst(".chapter-title")?.text()?.trim()
                ?: row.attr("data-chapter-number-label").takeIf { it.isNotEmpty() }?.let { "Bölüm $it" }
                ?: return@mapNotNull null
            date_upload = dateFormat.tryParseDate(row.selectFirst(".chapter-date")?.text())
        }
    }

    // The reader ships every page in #readerPages as img.reader-page with src set.
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val images = document.select("#readerPages img.reader-page")
            .ifEmpty { document.select("img.reader-page") }
        return images.mapIndexedNotNull { index, image -> pageImageUrl(image)?.let { Page(index, imageUrl = it) } }
    }

    private fun pageImageUrl(image: Element): String? = listOf("data-original-src", "data-src", "data-lazy-src", "src")
        .asSequence()
        .map { image.attr("abs:$it").trim() }
        .firstOrNull { it.startsWith("http") && !it.startsWith("data:") }

    override fun getFilterList(data: JsonElement?) = FilterList(
        SortFilter(),
        TypeFilter(),
        StatusFilter(),
        GenreFilter(getGenreList()),
    )
}
