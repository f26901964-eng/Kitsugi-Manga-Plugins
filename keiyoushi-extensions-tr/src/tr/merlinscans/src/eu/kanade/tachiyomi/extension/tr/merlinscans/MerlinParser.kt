package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.multisrc.initmanga.ReaderImageParser
import eu.kanade.tachiyomi.source.model.Page
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal data class SearchResult(val title: String, val url: String, val thumbnail: String?)
internal data class SearchPage(val mangas: List<SearchResult>, val hasNextPage: Boolean)

internal object MerlinParser {
    fun readerPages(document: Document): List<Page> = ReaderImageParser.imageUrls(
        document,
        "#chapter-content img, #chapter-content canvas[data-enc], .chapter-content img, .chapter-content canvas[data-enc]",
    ).mapIndexed { index, url -> Page(index, imageUrl = url) }

    fun searchResults(document: Document, baseUrl: String): SearchPage {
        val host = baseUrl.toHttpUrlOrNull()?.host
        val mangas = document.select("article:has(h2 a[href])").mapNotNull { article ->
            val link = article.selectFirst("h2 a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val segments = url.pathSegments.filter { it.isNotBlank() }
            // Search includes suggestions and articles as well as actual series.
            if (url.host != host || segments.size != 2 || segments[0] != "seri") return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            val image = article.selectFirst("img")
            val thumbnail = image?.let {
                listOf("data-original-src", "data-src", "data-lazy-src", "src")
                    .map { attr -> it.absUrl(attr) }
                    .firstOrNull { src -> src.startsWith("https://") || src.startsWith("http://") }
            }
            SearchResult(title, url.encodedPath, thumbnail)
        }.distinctBy { it.url }
        val next = document.selectFirst("link[rel=next], a.next, li:not(.uk-disabled) a[aria-label='Sonraki sayfa'], li:not(.uk-disabled) a:has([uk-pagination-next])") != null
        return SearchPage(mangas, next)
    }
}
