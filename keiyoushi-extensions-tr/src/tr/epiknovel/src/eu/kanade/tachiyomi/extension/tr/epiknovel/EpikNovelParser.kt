package eu.kanade.tachiyomi.extension.tr.epiknovel

import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

/**
 * Epik Novel (www.epiknovel.com) HTML sözleşmesi — saf jsoup ayrıştırma.
 *
 * Seçiciler ham dump'lardan çıkarıldı (docs/html-dumps/epiknovel/):
 * - Liste/arama kartları yalnız `#pop-href` içindeki `div.media` öğeleridir
 *   (yan panel hariç); kapaklar `data-src` üzerindedir, `src` her zaman
 *   `ajax-loader.gif` yer tutucusudur — `data-src` tercih edilir.
 * - Seri: `h1#tables` (18+ rozeti `img` ile kaldırılır), `img.manga-cover`,
 *   `h4` alanları (`Çevirmen:`, `Durum:` + `durum=` bağlantısı), "Seri Özeti"
 *   başlığından sonra gelen paragraflar, `table.table` içinde `/seri/<slug>/bolum/<n>`
 *   satırları (sitede en eski → en yeni sıralı).
 * - Bölüm: `#icerik` içinde `center>h4>b` başlık + `<p>` metinleri; `script`,
 *   `ins`, `iframe` atlanır; boş `<p><br></p>` ayraçları düşer.
 */
internal object EpikNovelParser {

    private const val SERIES_PREFIX = "/seri/"
    private const val PAGE_SIZE = 50

    private val SAYFA = Regex("Sayfa=(\\d+)")
    private val CHAPTER_NUMBER = Regex("Bölüm\\s*(\\d+)", RegexOption.IGNORE_CASE)
    private val RELATIVE_DATE = Regex(
        "(\\d+)\\s*(dakika|saat|gün|hafta|ay|yıl)\\s*önce",
        RegexOption.IGNORE_CASE,
    )

    private val SKIP_TAGS = setOf(
        "script", "style", "ins", "iframe", "noscript", "svg", "button",
        "form", "select", "textarea", "video", "audio", "object", "embed",
        "canvas", "nav", "header", "footer", "aside",
    )
    private val INLINE_TAGS = setOf(
        "a", "b", "i", "strong", "em", "u", "font", "span", "small", "sup",
        "sub", "code", "mark", "del", "s", "cite", "q", "abbr", "time",
        "label", "big", "tt", "var", "kbd", "samp",
    )

    // ---------------------------------------------------------------- list

    /** Liste/arama sayfasındaki kartlardan seri listesi. */
    fun mangasFromListPage(document: Document): List<SManga> =
        document.select("#pop-href div.media").mapNotNull(::mangaFromCard)

    private fun mangaFromCard(card: Element): SManga? {
        val heading = card.selectFirst("h3.media-heading") ?: return null
        val title = (heading.selectFirst("a") ?: heading).text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val href = heading.selectFirst("a")?.attr("abs:href")?.trim()?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst("a.pull-left")?.attr("abs:href")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        val path = seriesPath(href) ?: return null
        return SManga.create().apply {
            // Yol zaten alansız ("/seri/…"); HttpSource.setUrlWithoutDomain
            // yalnız nesne içinde çağrılabilir olduğu için doğrudan atanır.
            url = path
            this.title = title
            thumbnail_url = coverUrl(card.selectFirst("img"))
        }
    }

    /**
     * Bir sonraki sayfa var mı: sayfalama bağlantılarındaki en büyük `Sayfa`
     * değeri mevcut sayfadan büyükse evet. Sayfalama yoksa sayfa doluysa evet
     * (tek sayfalık sonuçlarda 50'den az kart olur).
     */
    fun hasNextPage(document: Document, page: Int): Boolean {
        val linked = document.select("ul.pagination a[href*=Sayfa]")
            .mapNotNull { SAYFA.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
        if (linked.isNotEmpty()) return linked.any { it > page }
        return document.select("#pop-href div.media").size >= PAGE_SIZE
    }

    // ------------------------------------------------------------- details

    fun mangaFromDetails(document: Document): SManga = SManga.create().apply {
        val heading = document.selectFirst("h1#tables") ?: document.selectFirst("h1")
        title = heading?.let(::stripBadge)?.trim().orEmpty()
        thumbnail_url = document.selectFirst("img.manga-cover")?.let(::coverUrl)

        val translator = h4Value(document, "Çevirmen")
        val writer = h4Value(document, "Yazar")
        author = writer?.takeIf { it.isNotBlank() } ?: translator

        val summary = seriOzeti(document)
        description = buildString {
            if (!translator.isNullOrBlank()) {
                append("Çevirmen: ").append(translator).append("\n\n")
            }
            append(summary)
        }.trim().takeIf { it.isNotEmpty() }

        status = parseStatus(document)
        // Sitede güvenilir tür alanı yok (tür bağlantıları yalnız HTML yorumunda) → genre null.
    }

    /** 18+ rozeti gibi `<img>` öğelerini metinden temizler. */
    private fun stripBadge(element: Element): String {
        val copy = element.clone()
        copy.select("img").remove()
        return copy.text().trim()
    }

    /** `h4` içindeki "Etiket: Değer" metnini değer olarak döndürür; boşsa null. */
    private fun h4Value(document: Document, label: String): String? {
        val h4 = document.select("h4").firstOrNull { it.text().contains(label) } ?: return null
        return h4.text()
            .substringAfter(label)
            .trim()
            .trim(':')
            .trim()
            .takeIf { it.isNotEmpty() }
    }

    private fun parseStatus(document: Document): Int {
        val h4 = document.select("h4").firstOrNull { it.text().contains("Durum") }
        val text = h4?.text().orEmpty()
        val href = h4?.selectFirst("a")?.attr("href").orEmpty()
        return when {
            "Tamamlandı" in text || "durum=1" in href -> SManga.COMPLETED
            "Devam" in text || "durum=0" in href -> SManga.ONGOING
            else -> SManga.UNKNOWN
        }
    }

    /** "Seri Özeti" başlığından sonraki, bir sonraki başlığa kadar olan paragraflar. */
    private fun seriOzeti(document: Document): String {
        val heading = document.select("h3").firstOrNull { it.text().contains("Seri Özeti") }
            ?: return ""
        val parts = mutableListOf<String>()
        var sibling = heading.nextElementSibling()
        while (sibling != null && sibling.tagName() !in setOf("h3", "h4", "h5")) {
            collectParagraphs(sibling, parts)
            sibling = sibling.nextElementSibling()
        }
        return parts.joinToString("\n\n")
    }

    private fun collectParagraphs(element: Element, into: MutableList<String>) {
        when {
            element.tagName() == "p" -> {
                val nested = element.select("p")
                if (nested.isNotEmpty()) {
                    nested.forEach { p -> p.text().clean()?.let(into::add) }
                } else {
                    element.text().clean()?.let(into::add)
                }
            }
            element.tagName() in setOf("ul", "ol") -> {
                element.select("li").forEach { li -> li.text().clean()?.let(into::add) }
            }
            else -> {
                val paragraphs = element.select("p")
                if (paragraphs.isNotEmpty()) {
                    paragraphs.forEach { p -> p.text().clean()?.let(into::add) }
                } else {
                    element.text().clean()?.let(into::add)
                }
            }
        }
    }

    // ----------------------------------------------------------- chapters

    /** Bölüm tablosu (en eski → en yeni, sitedeki sıra korunur). */
    fun chaptersFromDetails(document: Document): List<SChapter> {
        val table = document.select("table.table").firstOrNull { candidate ->
            candidate.selectFirst("a[href*=bolum]") != null
        } ?: return emptyList()

        return table.select("tbody tr").mapNotNull { row ->
            val link = row.selectFirst("td a[href*=bolum]") ?: return@mapNotNull null
            val path = chapterPath(link.attr("abs:href").trim()) ?: return@mapNotNull null
            val name = link.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val dateText = row.select("td").getOrNull(2)?.text().orEmpty()
            SChapter.create().apply {
                url = path
                this.name = name
                chapter_number = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                date_upload = relativeDateMillis(dateText)
            }
        }
    }

    private fun relativeDateMillis(text: String): Long {
        val match = RELATIVE_DATE.find(text) ?: return 0L
        val value = match.groupValues[1].toLongOrNull() ?: return 0L
        val unit = match.groupValues[2].lowercase(Locale.ROOT)
        val millis = when {
            "dakika" in unit -> 60_000L
            "saat" in unit -> 3_600_000L
            "gün" in unit -> 86_400_000L
            "hafta" in unit -> 7L * 86_400_000L
            "ay" in unit -> 30L * 86_400_000L
            "yıl" in unit -> 365L * 86_400_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - value * millis
    }

    // ------------------------------------------------------------ content

    /** Bölüm metnini başlık + paragraf/resim bloklarına çevirir. */
    fun chapterContent(document: Document): ChapterContent {
        val root = document.selectFirst("#icerik") ?: return ChapterContent(emptyList())
        val walker = Walker()
        root.childNodes().forEach(walker::walk)
        walker.flush()
        return ChapterContent(walker.blocks)
    }

    private class Walker {
        val blocks = mutableListOf<ContentBlock>()
        private var titleTaken = false
        private val pending = StringBuilder()

        fun walk(node: Node) {
            when (node) {
                is TextNode -> pending.append(node.text().replace(' ', ' '))
                is Element -> walkElement(node)
                else -> Unit // yorumlar vb.
            }
        }

        private fun walkElement(element: Element) {
            val tag = element.tagName().lowercase(Locale.ROOT)
            when {
                tag in SKIP_TAGS -> Unit
                tag == "br" -> flush()
                tag == "img" -> picture(element)
                tag in INLINE_TAGS -> element.childNodes().forEach(::walk)
                tag == "p" || tag == "li" -> {
                    flush()
                    element.text().clean()?.let { blocks += ContentBlock.Paragraph(it) }
                    element.select("img").forEach(::picture)
                }
                tag.length == 2 && tag[0] == 'h' && tag[1] in '1'..'6' -> {
                    flush()
                    val text = element.text().clean() ?: return
                    if (!titleTaken) {
                        titleTaken = true
                        blocks += ContentBlock.Title(text)
                    } else {
                        blocks += ContentBlock.Paragraph(text)
                    }
                }
                tag == "center" -> {
                    if (!titleTaken && element.selectFirst("h4, h5, h6") != null) {
                        val text = element.text().clean() ?: return
                        titleTaken = true
                        blocks += ContentBlock.Title(text)
                    } else {
                        flush()
                        element.childNodes().forEach(::walk)
                    }
                }
                else -> {
                    flush()
                    element.childNodes().forEach(::walk)
                }
            }
        }

        fun flush() {
            val text = pending.toString().clean()
            pending.clear()
            if (text != null) blocks += ContentBlock.Paragraph(text)
        }

        private fun picture(element: Element) {
            flush()
            val url = contentImageUrl(element) ?: return
            if (blocks.lastOrNull() != ContentBlock.Picture(url)) {
                blocks += ContentBlock.Picture(url)
            }
        }
    }

    /** İçerik görselleri: lazy-src önceliği, yer tutucu/data: atlanır, göreli mutlaklaşır. */
    private fun contentImageUrl(element: Element): String? =
        sequenceOf("data-src-retina", "data-src", "data-original", "src")
            .mapNotNull { attr ->
                element.attr("abs:$attr").trim().takeIf { it.isNotEmpty() }
            }
            .firstOrNull {
                it.startsWith("http") && "ajax-loader" !in it && !it.startsWith("data:")
            }

    // -------------------------------------------------------------- utils

    /** Kapak görseli: `data-src` yer tutucusuz gerçek kaynak; `src` (ajax-loader) son çare. */
    private fun coverUrl(image: Element?): String? {
        if (image == null) return null
        return sequenceOf("data-src-retina", "data-src", "data-original", "src")
            .mapNotNull { attr -> image.attr(attr).trim().takeIf { it.isNotEmpty() } }
            .firstOrNull {
                it.startsWith("http") && "ajax-loader" !in it && !it.startsWith("data:")
            }
    }

    /** `/seri/<slug>` yolunu doğrular; bozuk kartları (`/seri/`) atlar. */
    private fun seriesPath(url: String): String? {
        val index = url.indexOf(SERIES_PREFIX)
        if (index < 0) return null
        val slug = url.substring(index + SERIES_PREFIX.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        if (slug.isEmpty() || '/' in slug) return null
        return "$SERIES_PREFIX$slug"
    }

    /** `/seri/<slug>/bolum/<n>` yolunu doğrular. */
    private fun chapterPath(url: String): String? {
        val index = url.indexOf(SERIES_PREFIX)
        if (index < 0) return null
        val rest = url.substring(index + SERIES_PREFIX.length)
            .substringBefore('?')
            .substringBefore('#')
            .trim('/')
        val parts = rest.split('/')
        if (parts.size != 3 || parts.any { it.isEmpty() }) return null
        if (parts[1] != "bolum" || !parts[2].all { it.isDigit() }) return null
        return "$SERIES_PREFIX${parts[0]}/bolum/${parts[2]}"
    }

    private fun String.clean(): String? =
        replace(' ', ' ')
            .trim()
            .takeIf { it.isNotEmpty() }
}
