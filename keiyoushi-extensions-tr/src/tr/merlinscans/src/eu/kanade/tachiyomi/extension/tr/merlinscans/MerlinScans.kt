package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.multisrc.initmanga.InitManga
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class MerlinScans : InitManga() {
    // The archive supports pagination; the ranking page also contains sidebar rankings.
    override val popularUrlSlug = "seri"
    override val latestUrlSlug = "son-guncellenenler"

    override fun popularMangaSelector() =
        "div.manga-card, div.manga-item-grid > div.uk-panel:not(.manga-item-ranking):not(.user-item-info)"

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return super.getSearchMangaList(page, query, filters)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(query.trim())
            .apply { if (page > 1) { addPathSegment("page"); addPathSegment(page.toString()) } }
            .addPathSegment("")
            .build()
        val document = client.get(url).asJsoup()
        return MerlinParser.searchResults(document, baseUrl)
    }

    override suspend fun pageListParse(document: Document, chapterUrl: String): List<Page> {
        // Keep the theme's lock handling and encrypted-reader support. The normal
        // reader exposes ordered image slices in data-original-src.
        if (document.selectFirst("div.lock-card, div.imc-locked") != null) {
            return super.pageListParse(document, chapterUrl)
        }
        val pages = MerlinParser.readerPages(document)
        return if (pages.isNotEmpty()) pages else super.pageListParse(document, chapterUrl)
    }

    override fun parseMangaDetails(document: Document): SManga = super.parseMangaDetails(document).apply {
        // The shared parser only knows older cover markup. The site exposes the full cover here.
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("abs:content")
            ?.takeIf { it.isNotBlank() } ?: thumbnail_url
        genre = document.select("#genre-tags a[href*='/tur/']").joinToString { it.text().trim() }
        // The account labelled Yükleyici is not necessarily the author or translator.
        if (document.selectFirst("div.manga-info-details:contains(Yazar)") == null) author = null
        if (document.selectFirst("div.manga-info-details:contains(Çizer)") == null) artist = null
    }
}
