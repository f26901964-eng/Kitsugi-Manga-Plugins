package eu.kanade.tachiyomi.extension.tr.mangitto

import eu.kanade.tachiyomi.source.model.SChapter
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MangittoDtoContractTest {
    @Test
    fun actualDtoRetainsSeriesWhenHostDropsMemo() {
        val chapter = MangttoChapter(2f).populateChapter("chainsaw-man", HostChapter())
        assertEquals("/manga/chainsaw-man/2", chapter.url)
        chapter.memo = JsonObject(emptyMap())
        val address = MangittoChapterAddress.parse(chapter.url)
        assertEquals("chainsaw-man", address.slug)
        assertEquals("2", address.number)
    }
}

@Suppress("PropertyName")
private class HostChapter : SChapter {
    override var url: String = ""
    override var name: String = ""
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var date_upload: Long = 0L
    override var memo: JsonObject = JsonObject(emptyMap())
}
