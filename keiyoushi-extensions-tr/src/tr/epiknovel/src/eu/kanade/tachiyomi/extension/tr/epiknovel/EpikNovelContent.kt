package eu.kanade.tachiyomi.extension.tr.epiknovel

/**
 * Metin bölümünün görüntü okuyucuya aktarılabilir blokları ve sayfa bölme.
 *
 * Epik Novel bölümleri düz metindir; Kitsugi/Keiyoushi okuyucusu görsel sayfa
 * akışı bekler. Bölüm metni kaynak içindeki [TextPageInterceptor] ile bu
 * bloklardan PNG sayfalara çevrilir. Sayfalama saf ve deterministiktir:
 * getPageList (sayfa sayısı) ile interceptor (sayfa içeriği) aynı işlevi
 * kullandığından sınırlar her zaman eşleşir.
 */
internal sealed class ContentBlock {
    data class Title(val text: String) : ContentBlock()
    data class Paragraph(val text: String) : ContentBlock()
    data class Picture(val url: String) : ContentBlock()
}

internal data class ChapterContent(val blocks: List<ContentBlock>) {
    val isEmpty: Boolean get() = blocks.isEmpty()
}

internal object EpikNovelContent {

    /** Bir sayfanın karakter bütçesi (1080×1920 sayfada ~38f yazıyla sığar). */
    const val PAGE_CHAR_BUDGET = 1500
    const val TITLE_CHAR_COST = 120
    const val PICTURE_CHAR_COST = 1200
    const val MAX_PARAGRAPH_CHUNK = 1100

    /** Blokları sayfa bütçelerine böler. Deterministiktir: aynı girdi → aynı sayfalar. */
    fun paginate(blocks: List<ContentBlock>): List<List<ContentBlock>> {
        if (blocks.isEmpty()) return emptyList()

        val chunks = blocks.flatMap { block ->
            when (block) {
                is ContentBlock.Title -> listOf(block)
                is ContentBlock.Paragraph -> splitLong(block.text).map { ContentBlock.Paragraph(it) }
                is ContentBlock.Picture -> listOf(block)
            }
        }

        val pages = mutableListOf<List<ContentBlock>>()
        var current = mutableListOf<ContentBlock>()
        var cost = 0

        for (chunk in chunks) {
            val chunkCost = when (chunk) {
                is ContentBlock.Title -> TITLE_CHAR_COST
                is ContentBlock.Picture -> PICTURE_CHAR_COST
                is ContentBlock.Paragraph -> chunk.text.length
            }
            if (current.isNotEmpty() && cost + chunkCost > PAGE_CHAR_BUDGET) {
                pages += current
                current = mutableListOf()
                cost = 0
            }
            current += chunk
            cost += chunkCost
        }
        if (current.isNotEmpty()) pages += current
        return pages
    }

    /** Verilen blokların sayfa sayısı. */
    fun pageCount(blocks: List<ContentBlock>): Int = paginate(blocks).size

    /**
     * Uzun paragrafları [max] sınırından böler. Önce cümle sonu, sonra kelime
     * sınırı arar; ikisi de yoksa sert keser. Paragraf metni kaybolmaz.
     */
    fun splitLong(text: String, max: Int = MAX_PARAGRAPH_CHUNK): List<String> {
        if (text.length <= max) return listOf(text)

        val chunks = mutableListOf<String>()
        var rest = text
        while (rest.length > max) {
            var cut = -1
            for (i in max downTo max / 2) {
                if (rest[i] in ".!?…;:" && (i + 1 >= rest.length || rest[i + 1].isWhitespace())) {
                    cut = i + 1
                    break
                }
            }
            if (cut < 0) {
                val space = rest.lastIndexOf(' ', max)
                cut = if (space > max / 2) space else max
            }
            val piece = rest.substring(0, cut).trim()
            if (piece.isNotEmpty()) chunks += piece
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) chunks += rest
        return chunks.filter { it.isNotEmpty() }
    }
}
