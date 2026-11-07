package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.multisrc.initmanga.ReaderImageParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class MerlinParserTest {
    @Test
    fun searchIgnoresSuggestionsAndPreservesSeriesTitleAndCover() {
        val html = """
            <article><h2><a href="/seri-oner/tembel/">Tembel öneri</a></h2></article>
            <article><div><a href="/seri/tembel-soylunun-degisimi/"><img src="/cover.webp"></a></div>
              <h2><a href="/seri/tembel-soylunun-degisimi/"><mark>Tembel</mark> Soylunun Değişimi</a></h2></article>
            <article><h2><a href="https://other.example/seri/foreign/">Foreign</a></h2></article>
            <ul class="uk-pagination"><li><a href="/search/tembel/page/2/" aria-label="Sonraki sayfa">Sonraki</a></li></ul>
        """.trimIndent()
        val page = MerlinParser.searchResults(Jsoup.parse(html, "https://merlintoon.com/search/tembel/"), "https://merlintoon.com")
        assertEquals(1, page.mangas.size)
        assertEquals("Tembel Soylunun Değişimi", page.mangas.single().title)
        assertEquals("/seri/tembel-soylunun-degisimi/", page.mangas.single().url)
        assertEquals("https://merlintoon.com/cover.webp", page.mangas.single().thumbnail)
        assertTrue(page.hasNextPage)
    }

    @Test
    fun readerUsesAllOrderedSlicesNotLazyPlaceholdersOrCover() {
        val html = """
            <img src="/cover.webp">
            <div id="chapter-content">
              <img src="/0001.jpg" data-imc-order="1">
              <img data-original-src="/0002-part-001.jpg" src="data:image/svg+xml,placeholder" data-imc-order="2">
              <img data-original-src="/0002-part-002.jpg" src="data:image/svg+xml,placeholder" data-imc-order="3">
            </div>
        """.trimIndent()
        val pages = MerlinParser.readerPages(Jsoup.parse(html, "https://merlintoon.com/seri/test/bolum-1/"))
        assertEquals(listOf("https://merlintoon.com/0001.jpg", "https://merlintoon.com/0002-part-001.jpg", "https://merlintoon.com/0002-part-002.jpg"), pages.map { it.imageUrl })
    }

    @Test
    fun readerPreservesMixedPlainAndProtectedCanvasOrder() {
        val protected = Base64.getEncoder().encodeToString("/pages/002.jpg".reversed().toByteArray())
        val html = """
            <img src="/outside.jpg"><div id="chapter-content">
              <img data-original-src="/pages/001.jpg" src="data:image/svg+xml,placeholder">
              <canvas data-enc="$protected"></canvas>
              <canvas data-enc="not-base64!"></canvas>
              <img src="data:image/svg+xml,placeholder">
              <img data-lazy-src="//cdn.example/pages/003.jpg">
            </div>
        """.trimIndent()
        val pages = MerlinParser.readerPages(Jsoup.parse(html, "https://merlintoon.com/seri/test/bolum-1/"))
        assertEquals(listOf("https://merlintoon.com/pages/001.jpg", "https://merlintoon.com/pages/002.jpg", "https://cdn.example/pages/003.jpg"), pages.map { it.imageUrl })
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
    }

    @Test
    fun decryptedMarkupSupportsProtectedCanvasAndImages() {
        val encoded = Base64.getEncoder().encodeToString("https://cdn.example/two.jpg".reversed().toByteArray())
        val html = "<img src='/one.jpg'><canvas data-enc='$encoded'></canvas>"
        val doc = Jsoup.parseBodyFragment(html, "https://merlintoon.com")
        assertEquals(listOf("https://merlintoon.com/one.jpg", "https://cdn.example/two.jpg"), ReaderImageParser.imageUrls(doc, "img, canvas[data-enc]"))
    }

    @Test
    fun unrelatedBase64ScriptsDoNotMaskEncryptedChapter() {
        val unrelated = Base64.getEncoder().encodeToString("console.log('other')".toByteArray())
        val payload = "{\"ciphertext\":\"abc\",\"iv\":\"def\"}"
        val encrypted = Base64.getEncoder().encodeToString("var InitMangaEncryptedChapter = $payload;".toByteArray())
        val doc = Jsoup.parse("<script src='data:text/javascript;base64,$unrelated'></script><script src='data:text/javascript;base64,$encrypted'></script>")
        assertEquals(payload, ReaderImageParser.encryptedPayload(doc))
        assertEquals(null, ReaderImageParser.encryptedPayload(Jsoup.parse("<script src='data:text/javascript;base64,$unrelated'></script>")))
        assertEquals(payload, ReaderImageParser.encryptedPayload(Jsoup.parse("<script>var InitMangaEncryptedChapter = $payload;</script>")))
    }

    @Test
    fun searchDoesNotTreatSidebarAsResultsOrInventNextPage() {
        val page = MerlinParser.searchResults(Jsoup.parse("<div class='manga-item-ranking'><h3><a href='/seri/a/'>A</a></h3></div>", "https://merlintoon.com"), "https://merlintoon.com")
        assertTrue(page.mangas.isEmpty())
        assertFalse(page.hasNextPage)
    }
}
