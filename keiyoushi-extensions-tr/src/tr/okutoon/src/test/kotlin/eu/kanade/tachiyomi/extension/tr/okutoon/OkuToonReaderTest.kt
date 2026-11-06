package eu.kanade.tachiyomi.extension.tr.okutoon

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class OkuToonReaderTest {
    private val chapterUrl = "https://okutoon.com/chainsaw-man/bolum-1"

    @Test
    fun chapterApiUrlUsesSeriesAndChapterSlugs() {
        assertEquals(
            "https://okutoon.com/api/v1/series/chainsaw-man/chapters/bolum-1",
            OkuToonReader.chapterApiUrl("https://okutoon.com", chapterUrl).toString(),
        )
        assertNull(OkuToonReader.chapterApiUrl("https://okutoon.com", "https://other.example/chainsaw-man/bolum-1"))
        assertNull(OkuToonReader.chapterApiUrl("https://okutoon.com", "https://okutoon.com/chainsaw-man"))
    }

    @Test
    fun apiPagesAreValidatedAndSorted() {
        val body = """{"data":{"series":{"slug":"chainsaw-man"},"chapter":{"slug":"bolum-1"},"pages":[
            {"page_number":2,"image_url":"https://cdn.okutoon.com/2.webp"},
            {"page_number":1,"image_url":"/1.webp"}]}}"""
        assertEquals(
            listOf("https://okutoon.com/1.webp", "https://cdn.okutoon.com/2.webp"),
            OkuToonReader.apiImageUrls(body, chapterUrl),
        )
        assertNull(OkuToonReader.apiImageUrls(body.replace("bolum-1", "bolum-2"), chapterUrl))
        assertNull(OkuToonReader.apiImageUrls(body.replace("\"page_number\":2", "\"page_number\":1"), chapterUrl))
        assertNull(OkuToonReader.apiImageUrls(body.replace("/1.webp", "data:image/png;base64,AAAA"), chapterUrl))
        assertNull(OkuToonReader.apiImageUrls(body.replace("/1.webp", ""), chapterUrl))
        assertNull(OkuToonReader.apiImageUrls("not json", chapterUrl))
        assertNull(OkuToonReader.apiImageUrls("""{"data":{"pages":[]}}""", chapterUrl))
    }

    @Test
    fun savedReaderExtractsAllPagesInOrder() {
        val html = File(System.getProperty("okutoon.fixture")!!).readText()
        val urls = OkuToonReader.imageUrls(Jsoup.parse(html, chapterUrl))
        assertEquals(53, urls.size)
        assertEquals("https://om-cdn.okutoon.com/chapters/503/56171/chainsaw-man_bolum-1_001.webp", urls.first())
        assertTrue(urls.last().endsWith("_053.webp"))
    }

    @Test
    fun lazyAndRelativeUrlsWorkWithoutPlaceholders() {
        val doc = Jsoup.parse(
            """<div id="readerPages">
                <img class="reader-page" data-original-src="/one.webp" src="/placeholder.png">
                <img class="reader-page" data-src="//om-cdn.okutoon.com/two.webp">
                <img class="reader-page" src="/three.webp">
            </div>""",
            chapterUrl,
        )
        assertEquals(
            listOf("https://okutoon.com/one.webp", "https://om-cdn.okutoon.com/two.webp", "https://okutoon.com/three.webp"),
            OkuToonReader.imageUrls(doc),
        )
    }

    @Test(expected = IOException::class)
    fun missingPageUrlDoesNotSilentlyDropAPage() {
        val doc = Jsoup.parse("""<div id="readerPages"><img class="reader-page" src="data:image/png;base64,AAAA"></div>""", chapterUrl)
        OkuToonReader.imageUrls(doc)
    }

    @Test
    fun onlyExplicitCloudflareChallengesUseWebView() {
        assertTrue(OkuToonReader.isChallenge("challenge"))
        assertTrue(OkuToonReader.isChallenge("Challenge"))
        assertFalse(OkuToonReader.isChallenge(null))
        assertFalse(OkuToonReader.isChallenge("other"))
    }
}
