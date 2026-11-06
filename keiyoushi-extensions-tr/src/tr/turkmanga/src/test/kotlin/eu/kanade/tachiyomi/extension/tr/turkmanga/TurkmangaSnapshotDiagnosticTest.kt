package eu.kanade.tachiyomi.extension.tr.turkmanga

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TurkmangaSnapshotDiagnosticTest {
    private val dir = System.getenv("TURKMANGA_SNAPSHOT_DIR")
    private val base = "https://turkmanga.org"

    @Test
    fun actualUserSearchSnapshotContainsChainsawMan() {
        assumeTrue("Real snapshots only run on CI", !dir.isNullOrBlank())
        val html = File(dir!!, "search.html").readText()
        val doc = Jsoup.parse(html, "$base/search?input=chainsaw%20man")
        val parsed = TurkmangaParser.parseMangaList(doc, base, 1)
        println("REAL_SEARCH: count=${parsed.mangas.size} titles=${parsed.mangas.take(12).map { it.title }}")
        assertTrue("Saved search did not parse Chainsaw Man", parsed.mangas.any { it.url == "/manga/chainsaw-man" })
    }

    @Test
    fun actualUserDetailAndReaderSnapshot() {
        assumeTrue("Real snapshots only run on CI", !dir.isNullOrBlank())
        val detailUrl = "$base/manga/chainsaw-man"
        val detail = Jsoup.parse(File(dir!!, "detail.html").readText(), detailUrl)
        val result = TurkmangaParser.parseMangaDetails(detail, base, "/manga/chainsaw-man")
        println("REAL_DETAILS: title=${result.manga.title} desc=${result.manga.description?.length} chapters=${result.chapters.size}")
        assertEquals("Chainsaw Man", result.manga.title)
        assertTrue("Description missing", !result.manga.description.isNullOrBlank())
        assertTrue("No chapters", result.chapters.isNotEmpty())
        val chapterUrl = "$detailUrl/bolum-1"
        val reader = Jsoup.parse(File(dir, "reader.html").readText(), chapterUrl)
        val pages = TurkmangaParser.parseImageUrls(reader, base, chapterUrl)
        println("REAL_READER: pages=${pages.size}")
        assertTrue("No reader pages", pages.isNotEmpty())
    }
}
