package eu.kanade.tachiyomi.multisrc.initmanga

import org.jsoup.nodes.Document
import java.net.URI
import java.util.Base64

/** Reader markup may contain plain images or canvases whose URL is base64(reverse(url)). */
object ReaderImageParser {
    private val encryptedChapter = Regex("""InitMangaEncryptedChapter\s*=\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)

    fun imageUrls(document: Document, selector: String): List<String> = document.select(selector).mapNotNull { element ->
        if (element.normalName() == "canvas") {
            val encoded = element.attr("data-enc")
            val raw = runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8).reversed() }.getOrNull()
            raw?.let { resolveHttp(document.baseUri(), it) }
        } else {
            listOf("data-original-src", "data-src", "data-lazy-src", "data-cfsrc", "data-original", "src")
                .firstNotNullOfOrNull { attr -> resolveHttp(document.baseUri(), element.attr(attr)) }
        }
    }

    private fun resolveHttp(base: String, candidate: String): String? {
        val value = candidate.trim()
        if (value.isEmpty() || value.startsWith("data:", ignoreCase = true)) return null
        return runCatching {
            val url = URI(base).resolve(value)
            if ((url.scheme == "http" || url.scheme == "https") && !url.host.isNullOrBlank()) url.toASCIIString() else null
        }.getOrNull()
    }

    /** Ignore unrelated inline base64 scripts rather than mistaking them for a chapter payload. */
    fun encryptedPayload(document: Document): String? {
        for (script in document.select("script[src*=base64,]")) {
            val value = script.attr("src").substringAfter("base64,", "")
            val decoded = runCatching { String(Base64.getDecoder().decode(value), Charsets.UTF_8) }.getOrNull() ?: continue
            encryptedChapter.find(decoded)?.let { return it.groupValues[1] }
        }
        return encryptedChapter.find(document.html())?.groupValues?.get(1)
    }
}
