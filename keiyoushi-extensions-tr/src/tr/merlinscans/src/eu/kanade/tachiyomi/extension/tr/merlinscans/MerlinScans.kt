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
import java.io.IOException

@Source
abstract class MerlinScans : InitManga() {
    // The archive supports pagination; the ranking page also contains sidebar rankings.
    override val popularUrlSlug = "seri"
    override val latestUrlSlug = "son-guncellenenler"

    override fun popularMangaSelector() = "div.manga-card, div.manga-item-grid > div.uk-panel:not(.manga-item-ranking):not(.user-item-info)"

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isBlank()) return super.getSearchMangaList(page, query, filters)
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("search")
            .addPathSegment(query.trim())
            .apply {
                if (page > 1) {
                    addPathSegment("page")
                    addPathSegment(page.toString())
                }
            }
            .addPathSegment("")
            .build()
        val document = client.get(url).asJsoup()
        val result = MerlinParser.searchResults(document, baseUrl)
        return MangasPage(
            result.mangas.map { item ->
                SManga.create().apply {
                    title = item.title
                    this.url = item.url
                    thumbnail_url = item.thumbnail
                }
            },
            result.hasNextPage,
        )
    }

    override suspend fun pageListParse(document: Document, chapterUrl: String): List<Page> {
        // Let the theme decode the complete encrypted payload before falling back
        // to plain HTML; otherwise a single preload image can mask the other pages.
        val pages = super.pageListParse(document, chapterUrl)
        if (pages.isEmpty()) {
            throw IOException("MerlinToon 1.6.12: Bölüm görselleri alınamadı; site koruması veya okuyucu biçimi değişmiş olabilir.")
        }
        return pages
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
