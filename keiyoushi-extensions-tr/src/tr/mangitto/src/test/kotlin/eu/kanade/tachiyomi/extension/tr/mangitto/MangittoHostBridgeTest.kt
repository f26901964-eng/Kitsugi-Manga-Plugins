package eu.kanade.tachiyomi.extension.tr.mangitto

import com.kitsugi.animelist.data.manga.restoreMangittoChapterUrl
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MangittoHostBridgeTest {
    private val pkg = "eu.kanade.tachiyomi.extension.tr.mangitto"

    @Test
    fun oldChapterTwoIsRepairedWithoutMemoOrRefresh() {
        val restored = restoreMangittoChapterUrl(pkg, "2", "chainsaw-man")
        val address = MangittoChapterAddress.parse(restored)
        assertEquals("/manga/chainsaw-man/2", address.path)
    }

    @Test
    fun urlOnlyLegacyRecordReachesAll92FixtureImages() {
        val url = restoreMangittoChapterUrl(pkg, "0.01", "https://mangtto.com/manga/berserk")
        val address = MangittoChapterAddress.parse(url)
        val document = Jsoup.parse(File(System.getProperty("mangitto.fixtures"), "reader.html"), "UTF-8", "https://mangtto.com/")
        assertEquals(92, MangittoHtml.readerImages(document, address.slug, address.number).size)
    }

    @Test
    fun usesEachChaptersOwnParentNotLastOpenedManga() {
        assertEquals("/manga/chainsaw-man/2", restoreMangittoChapterUrl(pkg, "2", "/manga/chainsaw-man"))
        assertEquals("/manga/berserk/2", restoreMangittoChapterUrl(pkg, "2", "berserk"))
        assertEquals("/manga/chainsaw-man/2", restoreMangittoChapterUrl(pkg, "2", "chainsaw-man"))
    }

    @Test
    fun doesNotTouchOtherExtensionsOrCompleteUrls() {
        assertEquals("2", restoreMangittoChapterUrl("other.extension", "2", "chainsaw-man"))
        assertEquals("/manga/berserk/2", restoreMangittoChapterUrl(pkg, "/manga/berserk/2", "chainsaw-man"))
    }

    @Test
    fun doesNotGuessWhenParentIsMissingOrInvalid() {
        for (parent in listOf("", "/", "../other", "https://mangtto.com/", "manga/slug/2")) {
            assertEquals("2", restoreMangittoChapterUrl(pkg, "2", parent))
        }
    }
}
