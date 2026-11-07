package eu.kanade.tachiyomi.extension.tr.merlinscans

import eu.kanade.tachiyomi.multisrc.initmanga.ReaderImageParser
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Optional integration test using the original Drive chapter saved outside the repository. */
class MerlinRealReaderTest {
    private class TestMerlin : MerlinScans() {
        override val baseUrl get() = "https://merlintoon.com"
        override val name get() = "MerlinToon"
        override val lang get() = "tr"
    }

    @Test
    fun emptyReaderReportsTheActiveSourceVersion() = runBlocking {
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        val source = unsafe.allocateInstance(TestMerlin::class.java) as TestMerlin
        val document = Jsoup.parse("<div id='chapter-content'></div>", "https://merlintoon.com/seri/test/bolum-1/")
        try {
            source.pageListParse(document)
            throw AssertionError("An empty reader must not return an empty page list")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().startsWith("MerlinToon 1.6.12:"))
            assertTrue(error.message.orEmpty().contains("Bölüm görselleri alınamadı"))
        }
    }

    @Test
    fun savedTembelChapterRunsThroughActualSourceReader() = runBlocking {
        val path = System.getenv("MERLIN_REAL_READER_HTML")
        assumeTrue("Real chapter fixture was not supplied", !path.isNullOrBlank() && File(path).isFile)
        val url = "https://merlintoon.com/seri/tembel-soylunun-degisimi/bolum-1/"
        val document = Jsoup.parse(File(path!!), "UTF-8", url)
        val content = document.selectFirst("#chapter-content")
        assertNotNull("Drive fixture must contain reader markup", content)
        val imageElements = content!!.select("img")
        assertTrue("Expected the complete reader, not a snippet", imageElements.size > 10)
        val expected = imageElements.map { it.attr("data-original-src").ifBlank { it.attr("src") } }
        assertTrue("Fixture image URLs must be HTTP URLs", expected.all { it.startsWith("https://") })
        // The extension library intentionally ships an Android-only HttpSource stub to
        // JVM unit tests. The unencrypted reader needs no initialized network fields;
        // bypass the stub constructor solely to exercise the real parser implementation.
        val payload = ReaderImageParser.encryptedPayload(document)
        println("REAL_FIXTURE: imageElements=${imageElements.size}, encryptedPayload=${payload != null}")
        assertEquals("This fixture should be the plain-reader variant", null, payload)
        val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null) as sun.misc.Unsafe
        val source = unsafe.allocateInstance(TestMerlin::class.java) as TestMerlin
        val pages = source.pageListParse(document, url)
        assertEquals(expected, pages.map { it.imageUrl })
        assertEquals(pages.indices.toList(), pages.map { it.index })
        assertTrue("Fixture should contain the original chapter, not just its cover", pages.first().imageUrl!!.endsWith("/31516/1/0001.jpg"))
        println("REAL_FIXTURE: imageElements=${imageElements.size}, parsedPages=${pages.size}, encryptedPayload=${ReaderImageParser.encryptedPayload(document) != null}")
    }
}
