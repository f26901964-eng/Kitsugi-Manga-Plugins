package eu.kanade.tachiyomi.extension.tr.epiknovel

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Epik Novel parser testleri — docs/html-dumps/epiknovel/ gerçek dump'ları
 * üzerinden keşfet, arama, seri detayı (çevirmen/açıklama/durum), bölüm
 * listesi ve bölüm metni sözleşmesini doğrular.
 *
 * Not: `SManga.create()`/`SChapter.create()` birim testlerinde stub'tur;
 * parser saf giriş tipleri (ParsedManga/ParsedDetails/ParsedChapter) döndürür.
 */
class EpikNovelTest {

    private fun fixture(name: String): String =
        File(System.getProperty("epiknovel.$name") ?: error("epiknovel.$name ayarlanmadı")).readText()

    private fun descriptionOf(details: ParsedDetails): String {
        val value = details.description
        assertNotNull("description boş", value)
        return value!!
    }

    // ------------------------------------------------------------- liste

    @Test
    fun listCardsUseLazyCoverAndParseAllFifty() {
        val doc = Jsoup.parse(fixture("list"), "https://www.epiknovel.com/seri-listesi")
        val mangas = EpikNovelParser.mangasFromListPage(doc)

        assertEquals(50, mangas.size)
        val first = mangas.first()
        assertEquals("Against The God", first.title)
        assertEquals("/seri/against-the-god", first.url)
        // Kullanıcının gördüğü görsel sorunu: src ajax-loader.gif, gerçek kapak data-src.
        assertEquals("https://www.epiknovel.com/upload/cover/5929e018e06d0.gif", first.coverUrl)
        assertTrue(mangas.all { it.coverUrl?.contains("ajax-loader") != true })
        assertTrue(mangas.all { it.url.startsWith("/seri/") && it.url.length > "/seri/".length })
        assertTrue(mangas.all { it.title.isNotBlank() })
    }

    @Test
    fun pageTwoHasDifferentCardsThanPageOne() {
        val base = "https://www.epiknovel.com/seri-listesi"
        val page1 = EpikNovelParser.mangasFromListPage(Jsoup.parse(fixture("list"), base))
        val page2 = EpikNovelParser.mangasFromListPage(Jsoup.parse(fixture("listPage2"), base))
        assertEquals(50, page2.size)
        assertFalse(page1.first().url == page2.first().url)
    }

    @Test
    fun hasNextPageUsesPaginationLinks() {
        val base = "https://www.epiknovel.com/seri-listesi"
        assertTrue(EpikNovelParser.hasNextPage(Jsoup.parse(fixture("list"), base), 1))
        assertTrue(EpikNovelParser.hasNextPage(Jsoup.parse(fixture("listPage2"), base), 2))
        // Tek sayfalık aramada 2. sayfa yok (disabled kenar + aktif 1).
        assertFalse(EpikNovelParser.hasNextPage(Jsoup.parse(fixture("searchLive"), base), 1))
    }

    @Test
    fun searchAndCompletedFilterPagesParse() {
        val base = "https://www.epiknovel.com/seri-listesi"
        val search = EpikNovelParser.mangasFromListPage(Jsoup.parse(fixture("searchLive"), base))
        assertEquals(1, search.size)
        assertEquals("/seri/against-the-god", search.first().url)

        val completed = EpikNovelParser.mangasFromListPage(Jsoup.parse(fixture("listCompleted"), base))
        assertEquals(1, completed.size)
        assertEquals("/seri/dimensional-sovereign", completed.first().url)
        assertTrue(completed.first().title.isNotBlank())
    }

    @Test
    fun brokenCardWithoutSlugIsSkipped() {
        val doc = Jsoup.parse(
            """
            <div id="pop-href"><div class="media">
              <a class="pull-left" href="https://www.epiknovel.com/seri/">
                <img class="media-object" src="https://www.epiknovel.com/upload/icon/ajax-loader.gif" data-src="https://www.epiknovel.com/upload/cover/x.gif">
              </a>
              <div class="media-body"><h3 class="media-heading">
                <a href="https://www.epiknovel.com/seri/">Bozuk Kart</a>
              </h3></div>
            </div></div>
            """.trimIndent(),
            "https://www.epiknovel.com/seri-listesi",
        )
        assertTrue(EpikNovelParser.mangasFromListPage(doc).isEmpty())
    }

    // ------------------------------------------------------------ detay

    @Test
    fun detailsParseTitleTranslatorStatusAndSummary() {
        val doc = Jsoup.parse(fixture("series"), "https://www.epiknovel.com/seri/against-the-god")
        val details = EpikNovelParser.mangaFromDetails(doc)

        // 18+ rozeti metne karışmaz.
        assertEquals("Against The God", details.title)
        assertEquals("SEFIX", details.author)
        assertEquals(SManga.ONGOING, details.status)
        assertEquals("https://www.epiknovel.com/upload/cover/5929e018e06d0.gif", details.coverUrl)

        val description = descriptionOf(details)
        assertTrue(description.startsWith("Çevirmen: SEFIX"))
        assertTrue(description.contains("Gökyüzü Zehir Sedefini"))
        // NBSP temizliği: dump'ta nbsp karakteri kalmamalı.
        assertFalse(description.contains(' '))
    }

    @Test
    fun completedSeriesMapsStatusAndEmptyTranslator() {
        val doc = Jsoup.parse(
            fixture("seriesCompleted"),
            "https://www.epiknovel.com/seri/dimensional-sovereign",
        )
        val details = EpikNovelParser.mangaFromDetails(doc)

        assertEquals("Dimensional Sovereign", details.title)
        assertEquals(SManga.COMPLETED, details.status)
        // Çevirmen alanı boşsa imza satırı ve author üretilmez.
        assertNull(details.author)
        val description = descriptionOf(details)
        assertFalse(description.startsWith("Çevirmen:"))
        assertTrue(description.isNotBlank())
    }

    // ---------------------------------------------------------- bölümler

    @Test
    fun chapterListKeepsSiteOrderAndParsesNumbers() {
        val doc = Jsoup.parse(fixture("series"), "https://www.epiknovel.com/seri/against-the-god")
        val chapters = EpikNovelParser.chaptersFromDetails(doc)

        assertTrue(chapters.size >= 2000)
        assertEquals("Giriş", chapters.first().name)
        assertEquals("/seri/against-the-god/bolum/1730", chapters.first().url)
        assertEquals(0f, chapters.first().number, 0.01f)

        val second = chapters[1]
        assertEquals("Bölüm 1: Yun Che, Xiao Che", second.name)
        assertEquals("/seri/against-the-god/bolum/1731", second.url)
        assertEquals(1f, second.number, 0.01f)

        // Son bölüm: sitedeki sayısal karşılığı korunur.
        assertEquals(2028f, chapters.last().number, 0.01f)
        assertTrue(chapters.last().name.contains("Bölüm 2028"))
        // Göreli tarihler ("9 yıl önce") dönüşür.
        assertTrue(chapters.count { it.dateUpload > 0L } >= 2000)
    }

    @Test
    fun completedSeriesChapterListParses() {
        val doc = Jsoup.parse(
            fixture("seriesCompleted"),
            "https://www.epiknovel.com/seri/dimensional-sovereign",
        )
        val chapters = EpikNovelParser.chaptersFromDetails(doc)
        assertTrue(chapters.size >= 100)
        assertTrue(chapters.all { it.url.startsWith("/seri/dimensional-sovereign/bolum/") })
        assertTrue(chapters.all { it.name.isNotBlank() })
    }

    // ------------------------------------------------------- bölüm metni

    @Test
    fun chapterContentExtractsTitleParagraphsAndSkipsJunk() {
        val chapterUrl = "https://www.epiknovel.com/seri/against-the-god/bolum/1731"
        val content = EpikNovelParser.chapterContent(Jsoup.parse(fixture("chapter"), chapterUrl))

        assertFalse(content.isEmpty)
        val title = content.blocks.filterIsInstance<ContentBlock.Title>().firstOrNull()
        assertNotNull(title)
        assertEquals("Against The God - Bölüm 1: Yun Che, Xiao Che", title!!.text)

        val paragraphs = content.blocks.filterIsInstance<ContentBlock.Paragraph>()
        assertTrue(paragraphs.size >= 40)
        assertTrue(paragraphs.first().text.startsWith("Çeviri için Avare"))
        // Boş <p><br></p> ayraçları ve script/ins temizlenir.
        assertTrue(paragraphs.none { it.text.contains("<br") })
        assertTrue(paragraphs.none { it.text.contains("function(") })
        // Bu bölümde görsel yok (görselli bölümlerde Picture bloğu üretilir).
        assertTrue(content.blocks.none { it is ContentBlock.Picture })
        // Metin gerçekten geldi.
        assertTrue(paragraphs.any { it.text.contains("Yun Che") })
    }

    @Test
    fun liveChapterDumpMatchesSameContract() {
        val chapterUrl = "https://www.epiknovel.com/seri/against-the-god/bolum/1731"
        val content = EpikNovelParser.chapterContent(Jsoup.parse(fixture("chapterLive"), chapterUrl))
        assertFalse(content.isEmpty)
        val title = content.blocks.filterIsInstance<ContentBlock.Title>().firstOrNull()
        assertEquals("Against The God - Bölüm 1: Yun Che, Xiao Che", title?.text)
        assertTrue(content.blocks.filterIsInstance<ContentBlock.Paragraph>().size >= 40)
    }

    // ----------------------------------------------------- sayfalama (saf)

    @Test
    fun paginationIsDeterministicAndRespectsBudget() {
        val blocks = listOf(
            ContentBlock.Title("Bölüm 1"),
            ContentBlock.Paragraph("a".repeat(500)),
            ContentBlock.Paragraph("b".repeat(700)),
            ContentBlock.Paragraph("c".repeat(600)),
            ContentBlock.Picture("https://www.epiknovel.com/upload/cover/x.gif"),
            ContentBlock.Paragraph("d".repeat(900)),
        )
        val pages = EpikNovelContent.paginate(blocks)
        assertEquals(pages, EpikNovelContent.paginate(blocks))
        assertTrue(pages.size >= 3)

        pages.forEach { page ->
            val cost = page.sumOf { block ->
                when (block) {
                    is ContentBlock.Title -> EpikNovelContent.TITLE_CHAR_COST
                    is ContentBlock.Picture -> EpikNovelContent.PICTURE_CHAR_COST
                    is ContentBlock.Paragraph -> block.text.length
                }
            }
            assertTrue("sayfa bütçesi aşıldı: $cost", cost <= EpikNovelContent.PAGE_CHAR_BUDGET)
        }

        // Paragraf metni kaybolmaz.
        val rebuilt = pages.flatten()
            .filterIsInstance<ContentBlock.Paragraph>()
            .joinToString("") { it.text }
        assertEquals("a".repeat(500) + "b".repeat(700) + "c".repeat(600) + "d".repeat(900), rebuilt)
    }

    @Test
    fun splitLongNeverExceedsLimitAndKeepsText() {
        val sentences = "Bu bir test cümlesi. ".repeat(300) // ~6000 karakter
        val chunks = EpikNovelContent.splitLong(sentences, 1100)
        assertTrue(chunks.all { it.length <= 1100 })
        assertTrue(chunks.size > 3)
        assertTrue(chunks.joinToString(" ").contains("Bu bir test cümlesi."))

        val noSpaces = "x".repeat(5000)
        val hard = EpikNovelContent.splitLong(noSpaces, 1100)
        assertTrue(hard.all { it.length <= 1100 })
        assertEquals(noSpaces, hard.joinToString(""))
    }

    @Test
    fun syntheticPageUrlPointsAtInterceptorPath() {
        val url = TextPageInterceptor.pageUrl(
            "https://www.epiknovel.com",
            "https://www.epiknovel.com/seri/against-the-god/bolum/1731",
            2,
        )
        assertTrue(url.startsWith("https://www.epiknovel.com/__epiknovel_textpage?"))
        assertTrue(url.contains("page=2"))
        assertTrue(url.contains("chapter="))
        assertTrue(url.contains("bolum%2F1731") || url.contains("bolum/1731"))
    }
}
