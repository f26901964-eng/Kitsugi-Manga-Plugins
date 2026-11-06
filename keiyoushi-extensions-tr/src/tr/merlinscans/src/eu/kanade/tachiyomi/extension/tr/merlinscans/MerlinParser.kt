package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document

internal object MerlinParser {
    fun readerPages(document: Document): List<Page> = document.select("#chapter-content img, .chapter-content img")
        .mapNotNull { image ->
            listOf("data-original-src", "data-src", "data-lazy-src", "src")
                .map { image.absUrl(it) }
                .firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
        }.mapIndexed { index, url -> Page(index, imageUrl = url) }

    fun searchResults(document: Document, baseUrl: String): MangasPage {
        val host = baseUrl.toHttpUrlOrNull()?.host
        val mangas = document.select("article:has(h2 a[href])").mapNotNull { article ->
            val link = article.selectFirst("h2 a[href]") ?: return@mapNotNull null
            val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
            val segments = url.pathSegments.filter { it.isNotBlank() }
            // Search includes suggestions and articles as well as actual series.
            if (url.host != host || segments.size != 2 || segments[0] != "seri") return@mapNotNull null
            val title = link.text().trim()
            if (title.isEmpty()) return@mapNotNull null
            SManga.create().apply {
                this.title = title
                this.url = url.encodedPath
                val image = article.selectFirst("img")
                thumbnail_url = image?.let {
                    listOf("data-original-src", "data-src", "data-lazy-src", "src")
                        .map { attr -> it.absUrl(attr) }
                        .firstOrNull { src -> src.startsWith("https://") || src.startsWith("http://") }
                }
            }
        }.distinctBy { it.url }
        val next = document.selectFirst("link[rel=next], a.next, li:not(.uk-disabled) a[aria-label='Sonraki sayfa'], li:not(.uk-disabled) a:has([uk-pagination-next])") != null
        return MangasPage(mangas, next)
    }
}
