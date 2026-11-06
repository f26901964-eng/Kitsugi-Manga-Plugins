package eu.kanade.tachiyomi.extension.tr.mangitto

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MangittoDtoContractTest {
    @Test
    fun actualDtoRetainsSeriesWhenHostDropsMemo() {
        val chapter = MangttoChapter(2f).toSChapter("chainsaw-man")
        assertEquals("/manga/chainsaw-man/2", chapter.url)
        chapter.memo = JsonObject(emptyMap())
        val address = MangittoChapterAddress.parse(chapter.url)
        assertEquals("chainsaw-man", address.slug)
        assertEquals("2", address.number)
    }
}
