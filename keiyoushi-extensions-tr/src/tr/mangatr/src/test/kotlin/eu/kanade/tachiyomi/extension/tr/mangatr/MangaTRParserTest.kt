package eu.kanade.tachiyomi.extension.tr.mangatr

import okhttp3.Headers
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZonedDateTime
import java.util.Base64

class MangaTRParserTest {
    private val fixtureDir = File(requireNotNull(System.getProperty("mangatr.fixtureDir")))
    private val now: ZonedDateTime = ZonedDateTime.parse("2026-11-05T00:00:00Z")

    @Test
    fun `live chapter responses preserve every translation`() {
        val first = parseChapterFixture("chainsaw_chapters_offset0.html")
        val second = parseChapterFixture("chainsaw_chapters_offset20.html")

        assertEquals(20, first.cardCount)
        assertEquals(21, first.chapters.size)
        assertTrue(first.hasMore)
        assertEquals(89, second.cardCount)
        assertEquals(132, second.chapters.size)
        assertFalse(second.hasMore)

        val all = (first.chapters + second.chapters).distinctBy { it.url.substringBefore('?') }
        assertEquals(153, all.size)
        assertEquals(153, all.mapNotNull { READER_ID.find(it.url)?.groupValues?.get(1) }.distinct().size)

        val chapterTwo = all.filter { it.chapter_number == 2f }
        assertEquals(4, chapterTwo.size)
        assertEquals(
            setOf("Bağımsız", "Invictus Fansub, MangaTR", "AnimeWho, Pied Piper", "Şimarik Fansub"),
            chapterTwo.map { it.scanlator }.toSet(),
        )
        assertEquals(
            setOf("123931", "125989", "135286", "136245"),
            chapterTwo.mapNotNull { READER_ID.find(it.url)?.groupValues?.get(1) }.toSet(),
        )
        assertTrue(chapterTwo.all { it.url.contains("fs_done=1") })
        assertTrue(chapterTwo.all { it.date_upload > 0L })

        val namesByGroup = chapterTwo.associate { it.scanlator to it.name }
        assertEquals("Bölüm 2 - Pochita Nerede?", namesByGroup["Bağımsız"])
        assertEquals("Bölüm 2 - Pochita’nın Olduğu Yer", namesByGroup["Invictus Fansub, MangaTR"])
        assertEquals("Bölüm 2", namesByGroup["AnimeWho, Pied Piper"])
        assertEquals("Bölüm 2 - Pochita’nın olduğu yer", namesByGroup["Şimarik Fansub"])

        val latest = all.single { it.chapter_number == 137f }
        assertEquals("Garcia Fansub", latest.scanlator)
        assertEquals("Bölüm 137 - Chu Chu Sevgili Muni Muni Mura Mura", latest.name)
        assertTrue(latest.date_upload > 0L)
    }

    @Test
    fun `reader decrypts twenty five pages with changing attribute names`() {
        val first = readerHtml("data-index-a", "data-payload-a", reverse = false)
        val second = readerHtml("data-index-b", "data-payload-b", reverse = true)

        for (html in listOf(first, second)) {
            val pages = MangaTRParser.parseReaderPages(Jsoup.parse(html, BASE_URL))
            assertEquals(25, pages.size)
            assertEquals((0 until 25).toList(), pages.map { it.index })
            assertEquals(
                (0 until 25).map { "https://image.mngtr.site/img_part.php?key=test-$it&p=page-$it" },
                pages.map { it.imageUrl },
            )
            assertTrue(pages.none { it.imageUrl.orEmpty().contains("trap") })
        }
    }

    @Test
    fun `reader preserves scrambled strip order`() {
        val key = TEST_KEY
        val url = "https://image.mngtr.site/img_part.php?key=ordered&p=parts"
        val html = """
            <html><body>
            <script id="rdm-order" type="application/json" data-a="1" data-b="$key">{}</script>
            <div data-page="0" data-parts="${encrypt("[\"$url\"]", "attr|$key|reader")}"
                data-order="${encrypt("[2,0,1]", "order|$key|reader")}"></div>
            </body></html>
        """.trimIndent()
        val pages = MangaTRParser.parseReaderPages(Jsoup.parse(html, BASE_URL))
        assertEquals(1, pages.size)
        assertEquals("$url#2,0,1", pages.single().imageUrl)
    }

    @Test
    fun `ajax headers replace navigation values without duplicates`() {
        val headers = Headers.Builder()
            .add("Accept", "text/html")
            .add("Accept", "duplicate")
            .add("Referer", BASE_URL)
            .add("Sec-Fetch-Dest", "document")
            .add("Sec-Fetch-Mode", "navigate")
            .add("Sec-Fetch-User", "?1")
            .add("Upgrade-Insecure-Requests", "1")
            .mangaTrAjaxHeaders("$BASE_URL/manga-chainsaw-man.html", acceptsHtml = true)
            .build()

        assertEquals(listOf("text/html, */*; q=0.01"), headers.values("Accept"))
        assertEquals("XMLHttpRequest", headers["X-Requested-With"])
        assertEquals("$BASE_URL/manga-chainsaw-man.html", headers["Referer"])
        assertEquals("empty", headers["Sec-Fetch-Dest"])
        assertEquals("cors", headers["Sec-Fetch-Mode"])
        assertNull(headers["Sec-Fetch-User"])
        assertNull(headers["Upgrade-Insecure-Requests"])
    }

    @Test
    fun `image headers match a browser image request`() {
        val headers = Headers.Builder()
            .set("Accept", "text/html")
            .set("Origin", BASE_URL)
            .set("Referer", BASE_URL)
            .set("X-Requested-With", "XMLHttpRequest")
            .set("Sec-Fetch-Dest", "document")
            .set("Sec-Fetch-Mode", "navigate")
            .mangaTrImageHeaders("$BASE_URL/")
            .build()

        assertEquals("image/avif,image/webp,image/*,*/*;q=0.8", headers["Accept"])
        assertEquals("$BASE_URL/", headers["Referer"])
        assertEquals("image", headers["Sec-Fetch-Dest"])
        assertEquals("no-cors", headers["Sec-Fetch-Mode"])
        assertEquals("cross-site", headers["Sec-Fetch-Site"])
        assertNull(headers["Origin"])
        assertNull(headers["X-Requested-With"])
    }

    @Test
    fun `css seed and chapter signature match the site contract`() {
        val css = ":root{--mdx-white:111111;--mdx-black:000000;}"
        assertEquals(111111, MangaTRContract.seedFromCss(css))
        assertEquals(null, MangaTRContract.seedFromCss(":root{--mdx-white:111111}"))
        assertEquals(
            "ce412bb627d627b344aa4af9012b20bced7b98852d3f48132e7b1c681d87cb79",
            MangaTRContract.signature("chainsaw-man", 111111, 1_700_000_000),
        )
    }

    private fun parseChapterFixture(name: String): MangaTRParser.ChapterBatch {
        val file = fixtureDir.resolve(name)
        assertTrue("Missing live fixture: ${file.absolutePath}", file.isFile)
        return MangaTRParser.parseChapterDocument(Jsoup.parse(file.readText(), BASE_URL), now, ::createTestChapter)
    }

    private fun readerHtml(indexAttribute: String, payloadAttribute: String, reverse: Boolean): String {
        val indices = (0 until 25).let { if (reverse) it.reversed() else it.toList() }
        val pages = indices.joinToString("\n") { index ->
            val url = "https://image.mngtr.site/img_part.php?key=test-$index&p=page-$index"
            val payload = encrypt("[\"$url\"]", "attr|$TEST_KEY|reader")
            """<div class="chapter-page" $indexAttribute="$index" $payloadAttribute="$payload" mtr-test="1" rd-test="false"></div>"""
        }
        return """
            <html><body>
            <script id="rdm-test" type="application/json" data-random="1" data-key="$TEST_KEY">{"encrypted":"config"}</script>
            <noscript><img src="https://image.mngtr.site/img_part.php?key=trap-noscript"></noscript>
            <img width="0" height="0" src="https://image.mngtr.site/img_part.php?key=trap-hidden">
            $pages
            </body></html>
        """.trimIndent()
    }

    private fun encrypt(value: String, key: String): String {
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val encrypted = value.toByteArray(Charsets.UTF_8).mapIndexed { index, byte ->
            (byte.toInt() xor keyBytes[index % keyBytes.size].toInt()).toByte()
        }.toByteArray()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted)
    }

    companion object {
        private const val BASE_URL = "https://manga-tr.com"
        private const val TEST_KEY = "0123456789abcdef0123456789abcdef"
        private val READER_ID = Regex("""(?:^|/)id-(\d+)-read-""")
    }
}
