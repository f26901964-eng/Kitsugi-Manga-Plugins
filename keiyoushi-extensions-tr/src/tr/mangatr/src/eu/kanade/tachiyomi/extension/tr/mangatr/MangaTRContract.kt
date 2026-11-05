package eu.kanade.tachiyomi.extension.tr.mangatr

import okhttp3.Headers
import java.security.MessageDigest

internal object MangaTRContract {
    fun seedFromCss(css: String): Int? {
        val white = CssNumberRegex.find(css, "--mdx-white") ?: return null
        val black = CssNumberRegex.find(css, "--mdx-black") ?: return null
        return white + black
    }

    fun signature(mangaSlug: String, seed: Int, timestamp: Long): String {
        val raw = "${mangaSlug}_${seed}_$timestamp"
        val hash = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    private object CssNumberRegex {
        fun find(css: String, variable: String): Int? = Regex("""${Regex.escape(variable)}\s*:\s*(\d+)""")
            .find(css)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }
}

/** Makes navigation headers match a same-origin jQuery XHR without duplicate values. */
internal fun Headers.Builder.mangaTrAjaxHeaders(
    referer: String? = null,
    acceptsHtml: Boolean = false,
): Headers.Builder = apply {
    set("X-Requested-With", "XMLHttpRequest")
    set("Accept", if (acceptsHtml) "text/html, */*; q=0.01" else "*/*")
    set("Accept-Encoding", "identity")
    set("Sec-Fetch-Dest", "empty")
    set("Sec-Fetch-Mode", "cors")
    set("Sec-Fetch-Site", "same-origin")
    removeAll("Sec-Fetch-User")
    removeAll("Upgrade-Insecure-Requests")
    if (referer != null) set("Referer", referer)
}

internal fun Headers.Builder.mangaTrImageHeaders(referer: String): Headers.Builder = apply {
    set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
    set("Referer", referer)
    set("Sec-Fetch-Dest", "image")
    set("Sec-Fetch-Mode", "no-cors")
    set("Sec-Fetch-Site", "cross-site")
    removeAll("Origin")
    removeAll("X-Requested-With")
    removeAll("Sec-Fetch-User")
    removeAll("Upgrade-Insecure-Requests")
}
