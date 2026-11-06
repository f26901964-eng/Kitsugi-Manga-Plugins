package eu.kanade.tachiyomi.extension.tr.okutoon

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class OkuToonReaderTest {
    private val chapterUrl = "https://okutoon.com/chainsaw-man/bolum-1"

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
