package eu.kanade.tachiyomi.extension.tr.mangitto

import eu.kanade.tachiyomi.extension.tr.mangitto.MangittoHtml.text
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MangittoHtmlTest {
    private fun fixture(name: String) = Jsoup.parse(
        File(System.getProperty("mangitto.fixtures"), name),
        "UTF-8",
        "https://mangtto.com/",
    )

    @Test
    fun homepageResolvesReferencesAndAliases() {
        val mangas = MangittoHtml.mangaObjects(fixture("home.html"))
        assertTrue(mangas.size > 40)
        assertEquals(mangas.size, mangas.map { it.text("slug") }.distinct().size)
        val solo = mangas.single { it.text("slug") == "na-honjaman-level-up" }
        assertEquals("Na Honjaman Level Up", solo.text("title"))
        assertTrue(MangittoHtml.matches(solo, "Solo Leveling"))
        assertTrue(MangittoHtml.matches(solo, "나 혼자만 레벨업"))
        assertFalse(MangittoHtml.matches(solo, "not a real manga title"))
        assertTrue(solo.text("coverImage")!!.startsWith("https://"))
    }

    @Test
    fun readerUsesAll92RealImagesInOrder() {
        val images = MangittoHtml.readerImages(fixture("reader.html"), "berserk", "0.01")
        assertEquals(92, images.size)
        images.forEachIndexed { index, url ->
            assertEquals(
                "https://cdn.zukrein.com/manga/berserk/0.01/${index + 1}-cm58p7pcl0008yg3gvq7jp47t.webp",
                url,
            )
        }
        assertTrue(MangittoHtml.readerImages(fixture("reader.html"), "berserk", "0.02").isEmpty())
    }

    @Test
    fun excludesUnrelatedImagesAndDeduplicatesLazyImages() {
        val doc = Jsoup.parse(
            """<img src="/default-avatar.webp">
                <img src="/manga/berserk/0.01/1-f.webp">
                <img src="/placeholder.webp" data-src="/manga/berserk/0.01/1-f.webp">
                <img src="/manga/berserk/0.02/1-f.webp">""",
            "https://cdn.zukrein.com/",
        )
        assertEquals(listOf("https://cdn.zukrein.com/manga/berserk/0.01/1-f.webp"), MangittoHtml.readerImages(doc, "berserk", "0.01"))
    }
}
