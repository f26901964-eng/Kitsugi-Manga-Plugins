package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Locale

/** HTML parsing kept independent from Android so the live fixtures can run as JVM tests. */
internal object MangaTRParser {

    internal data class ChapterBatch(
        val chapters: List<SChapter>,
        val hasMore: Boolean,
        val cardCount: Int,
        val emptyCatalog: Boolean,
    )

    fun parseChapterDocument(
        document: Document,
        now: ZonedDateTime = ZonedDateTime.now(),
        chapterFactory: () -> SChapter = { SChapter.create() },
    ): ChapterBatch {
        val cards = document.select(CHAPTER_CARD_SELECTOR)
        val chapters = cards.flatMap { parseChapterCard(it, now, chapterFactory) }
            .distinctBy { chapterIdentity(it.url) }

        val hasMore = document.selectFirst("#has-more-chapters")
            ?.attr("value")
            ?.trim()
            ?.let { it == "1" || it.equals("true", ignoreCase = true) }
            ?: false

        val emptyCatalog = cards.isEmpty() && document.selectFirst(".empty-state")
            ?.text()
            ?.contains("Henüz bölüm eklenmemiş", ignoreCase = true) == true

        return ChapterBatch(chapters, hasMore, cards.size, emptyCatalog)
    }

    /** Matches the content wall, not the site's ordinary navigation/login modal. */
    fun requiresReaderLogin(document: Document): Boolean {
        if (document.selectFirst("div#uyari:contains(üye girişi), div.alert:contains(üye girişi)") != null ||
            document.html().contains("üye girişi yapmanız gerekmektedir", ignoreCase = true)
        ) {
            return true
        }
        val text = document.text()
        return text.contains("Bu İçeriği Okumak İçin Giriş Yapmalısınız", ignoreCase = true) ||
            text.contains("yalnızca kayıtlı Manga-TR üyeleri", ignoreCase = true)
    }

    fun parseChapterCard(
        card: Element,
        now: ZonedDateTime = ZonedDateTime.now(),
        chapterFactory: () -> SChapter = { SChapter.create() },
    ): List<SChapter> {
        val translationRows = card.select(".bento-ep-translation-row")
        if (translationRows.isNotEmpty()) {
            // The title link duplicates the first translation. Each row is the sole
            // source of a reader ID, group name, subtitle and date in this layout.
            return translationRows.mapNotNull { row ->
                val link = row.selectFirst("a.bento-ep-group-link[href]")
                    ?: row.selectFirst("a[href*='-read-'], [data-href*='-read-']")
                    ?: return@mapNotNull null
                createChapter(card, row, link, now, chapterFactory)
            }.distinctBy { chapterIdentity(it.url) }
        }

        val link = card.selectFirst("a.bento-ep-title-link[href]")
            ?: card.selectFirst("a[href*='-read-'], [data-href*='-read-']")
            ?: return emptyList()
        return listOfNotNull(createChapter(card, card, link, now, chapterFactory))
    }

    private fun createChapter(
        card: Element,
        row: Element,
        link: Element,
        now: ZonedDateTime,
        chapterFactory: () -> SChapter,
    ): SChapter? {
        val href = link.absUrl("href").ifBlank { link.absUrl("data-href") }
            .ifBlank { link.attr("data-href") }
        if (href.isBlank() || !href.contains("-read-")) return null

        val titleLink = card.selectFirst("a.bento-ep-title-link") ?: link
        val number = card.selectFirst(".bento-ep-chapter-num")
            ?.text()
            ?.removeSuffix(".")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: extractNumber(titleLink.text())
        val chapterNumber = number?.toFloatOrNull()
            ?: extractNumber(titleLink.text())?.toFloatOrNull()
            ?: -1f

        val subtitle = row.selectFirst(".bento-ep-row-name")
            ?.text()
            ?.replace(ROW_PREFIX_REGEX, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: card.selectFirst(".bento-ep-subtitle, .chapter-sub")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val titleText = titleLink.text().trim()
        val name = when {
            number != null && subtitle != null && !subtitle.contains("Bölüm", true) -> "Bölüm $number - $subtitle"
            number != null && subtitle != null -> subtitle
            number != null -> "Bölüm $number"
            titleText.isNotEmpty() -> titleText
            else -> "Bölüm"
        }

        val scanlator = findScanlator(row, link)
        val date = findDate(row, now).takeIf { it != 0L }
            ?: findDate(card, now)

        val relativeUrl = href.toHttpUrlOrNull()?.let { url ->
            url.encodedPath + url.encodedQuery?.let { query -> "?$query" }.orEmpty()
        } ?: href

        return chapterFactory().apply {
            this.url = relativeUrl
            this.name = name
            this.chapter_number = chapterNumber
            this.date_upload = date
            if (!scanlator.isNullOrBlank()) this.scanlator = scanlator
        }
    }

    private fun findScanlator(row: Element, link: Element): String? {
        val groupLinkText = link.takeIf { it.hasClass("bento-ep-group-link") }
            ?.text()
            ?.normalizeWhitespace()
            ?.takeIf { it.isNotEmpty() }
        val dropdownGroup = row.selectFirst(".chapter-dropdown-group-name")
        val dropdownGroupName = dropdownGroup?.attr("title")
            ?.normalizeWhitespace()
            ?.takeIf { it.isNotEmpty() }
            ?: dropdownGroup?.ownText()?.normalizeWhitespace()?.takeIf { it.isNotEmpty() }
        val listedGroups = row.select("a[href*='action=sgroups'] strong")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }

        return sequenceOf(
            groupLinkText,
            dropdownGroupName,
            row.selectFirst(".bento-ep-scanlator, .fansub-name, .scanlator, .group-name")?.text()?.normalizeWhitespace(),
            row.selectFirst("a.fansub-group-item")?.text()?.normalizeWhitespace(),
            listedGroups,
        ).filterNotNull()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && it.length <= 160 && !it.contains("Bölüm", true) }
    }

    private fun findDate(row: Element, now: ZonedDateTime): Long {
        row.selectFirst(".bento-ep-meta-time")?.text()?.let { text ->
            parseRelativeDate(text, now).takeIf { it != 0L }?.let { return it }
        }
        row.select(".bento-ep-row-meta > span, .stats, .chapter-stats")
            .asReversed()
            .forEach { element ->
                parseRelativeDate(element.ownText(), now).takeIf { it != 0L }?.let { return it }
                parseRelativeDate(element.text(), now).takeIf { it != 0L }?.let { return it }
            }
        return 0L
    }

    internal fun parseRelativeDate(dateString: String?, now: ZonedDateTime): Long {
        if (dateString.isNullOrBlank()) return 0L
        val text = dateString.lowercase(Locale.ROOT).trim()
        if (!RELATIVE_DATE_REGEX.containsMatchIn(text)) return 0L
        val number = NUMBER_REGEX.find(text)?.value?.toLongOrNull() ?: return 0L
        val unit = when {
            text.contains("saniye") -> ChronoUnit.SECONDS
            text.contains("dakika") || text.contains("dk") -> ChronoUnit.MINUTES
            text.contains("saat") -> ChronoUnit.HOURS
            text.contains("gün") || text.contains("gun") -> ChronoUnit.DAYS
            text.contains("hafta") -> ChronoUnit.WEEKS
            text.contains("ay") -> ChronoUnit.MONTHS
            text.contains("yıl") || text.contains("yil") -> ChronoUnit.YEARS
            else -> return 0L
        }
        return runCatching { now.minus(number, unit).toInstant().toEpochMilli() }.getOrDefault(0L)
    }

    /** Decrypts the public per-page payload; the optional gate/config request is not required. */
    fun parseReaderPages(document: Document): List<Page> {
        val configScript = document.selectFirst("script[type=application/json][id^=rdm-]")
            ?: document.selectFirst("script[id^=rdm-]")
            ?: return emptyList()
        val pageKey = configScript.attributes().firstNotNullOfOrNull { attribute ->
            attribute.value.takeIf { HEX_KEY_REGEX.matches(it) }
        } ?: return emptyList()

        data class Candidate(
            val index: Int?,
            val url: String,
            val order: List<Int>?,
        )

        val candidates = mutableListOf<Candidate>()
        document.allElements.forEach { element ->
            element.attributes().forEach { attribute ->
                val encrypted = attribute.value
                if (encrypted.length < 80 || !BASE64_PAYLOAD_REGEX.matches(encrypted)) return@forEach

                val decrypted = runCatching { encrypted.decrypt("attr|$pageKey|reader") }.getOrNull()
                    ?.trim()
                    ?.takeIf { it.startsWith("[") }
                    ?: return@forEach
                val urls = runCatching {
                    Json.parseToJsonElement(decrypted).jsonArray.map { it.jsonPrimitive.content }
                }.getOrDefault(emptyList())
                val imageUrl = urls.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }
                    ?.takeIf { it.contains("img_part.php") || it.contains("image.mngtr.site") }
                    ?: return@forEach

                val pageIndex = element.attributes().firstOrNull { candidate ->
                    candidate.key != attribute.key &&
                        candidate.key.startsWith("data-") &&
                        candidate.value.length <= 4 &&
                        candidate.value.all(Char::isDigit)
                }?.value?.toIntOrNull()

                val order = element.attributes().firstNotNullOfOrNull { candidate ->
                    if (candidate.key == attribute.key || candidate.value.length < 10) return@firstNotNullOfOrNull null
                    runCatching {
                        candidate.value.decrypt("order|$pageKey|reader")
                            .let { Json.parseToJsonElement(it).jsonArray }
                            .map { it.jsonPrimitive.content.toInt() }
                    }.getOrNull()
                }
                candidates += Candidate(pageIndex, imageUrl, order)
            }
        }

        return candidates
            .sortedBy { it.index ?: Int.MAX_VALUE }
            .distinctBy { it.url }
            .mapIndexed { index, candidate ->
                val imageUrl = candidate.order
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { order ->
                        candidate.url.toHttpUrlOrNull()?.newBuilder()
                            ?.fragment(order.joinToString(","))
                            ?.build()
                            ?.toString()
                            ?: candidate.url
                    }
                    ?: candidate.url
                Page(index, imageUrl = imageUrl)
            }
    }

    private fun String.decrypt(key: String): String {
        val normalized = replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val bytes = Base64.getDecoder().decode(padded)
        for (index in bytes.indices) {
            bytes[index] = (bytes[index].toInt() xor key[index % key.length].code).toByte()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun chapterIdentity(url: String): String {
        READER_ID_REGEX.find(url)?.groupValues?.get(1)?.let { return "reader:$it" }
        return url.substringBefore('?').removeSuffix("/")
    }

    private fun extractNumber(text: String?): String? = text?.let {
        NUMBER_WITH_DECIMAL_REGEX.find(it)?.groupValues?.get(1)
    }

    private fun String.normalizeWhitespace(): String = replace(WHITESPACE_REGEX, " ").trim()

    private const val CHAPTER_CARD_SELECTOR =
        "article.bento-ep-card, div.bento-ep-card, div.chapter-item, article.chapter-item, div.chapter-box, li.chapter-item"
    private val ROW_PREFIX_REGEX = Regex("""^[└─\s]+""")
    private val RELATIVE_DATE_REGEX = Regex("""(?:saniye|dakika|\bdk\b|saat|gün|gun|hafta|ay|yıl|yil)""")
    private val NUMBER_REGEX = Regex("""\d+""")
    private val NUMBER_WITH_DECIMAL_REGEX = Regex("""(?:Bölüm\s*)?(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
    private val READER_ID_REGEX = Regex("""(?:^|/)id-(\d+)-read-""")
    private val HEX_KEY_REGEX = Regex("""[0-9a-f]{32}""")
    private val BASE64_PAYLOAD_REGEX = Regex("""[A-Za-z0-9+/=_-]{80,}""")
    private val WHITESPACE_REGEX = Regex("""\s+""")
}
