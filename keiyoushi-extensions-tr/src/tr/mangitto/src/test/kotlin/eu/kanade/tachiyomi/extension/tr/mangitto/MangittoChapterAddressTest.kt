package eu.kanade.tachiyomi.extension.tr.mangitto

import org.junit.Assert.assertEquals
import org.junit.Test

class MangittoChapterAddressTest {
    @Test
    fun chapterSurvivesUrlOnlyPersistenceWithoutMemo() {
        val savedUrl = MangittoChapterAddress("chainsaw-man", "1").path
        val restored = MangittoChapterAddress.parse(savedUrl)
        assertEquals("chainsaw-man", restored.slug)
        assertEquals("1", restored.number)
        assertEquals("/manga/chainsaw-man/1", restored.path)
    }

    @Test
    fun fractionalChapterKeepsLeadingZeros() {
        val address = MangittoChapterAddress.parse("/manga/berserk/0.01")
        assertEquals("0.01", address.number)
        assertEquals("/manga/berserk/0.01", address.path)
    }

    @Test
    fun acceptsAbsoluteAndShortAddresses() {
        assertEquals("/manga/chainsaw-man/1", MangittoChapterAddress.parse("https://mangtto.com/manga/chainsaw-man/1").path)
        assertEquals("/manga/chainsaw-man/1", MangittoChapterAddress.parse("chainsaw-man/1").path)
    }

    @Test
    fun legacyMemoStillWorksAndNeverOverridesUrl() {
        assertEquals("/manga/chainsaw-man/1", MangittoChapterAddress.parse("1", "chainsaw-man").path)
        assertEquals("chainsaw-man", MangittoChapterAddress.parse("/manga/chainsaw-man/1", "other-manga").slug)
    }

    @Test(expected = IllegalStateException::class)
    fun oldNumberWithoutMemoRequestsChapterRefresh() {
        MangittoChapterAddress.parse("1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidChapterNumber() {
        MangittoChapterAddress.parse("/manga/chainsaw-man/not-a-chapter")
    }
}
