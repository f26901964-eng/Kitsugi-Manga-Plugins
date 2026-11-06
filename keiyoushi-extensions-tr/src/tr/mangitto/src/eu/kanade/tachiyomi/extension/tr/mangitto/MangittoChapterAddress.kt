package eu.kanade.tachiyomi.extension.tr.mangitto

import java.net.URI

/** Chapter identity must survive hosts that persist url but drop SChapter.memo. */
internal data class MangittoChapterAddress(val slug: String, val number: String) {
    val path: String get() = "/manga/$slug/$number"

    companion object {
        fun parse(url: String, legacySlug: String? = null): MangittoChapterAddress {
            val parts = URI(url).path.orEmpty().trim('/').split('/')
            val address = when {
                parts.size == 3 && parts[0] == "manga" -> MangittoChapterAddress(parts[1], parts[2])
                parts.size == 2 -> MangittoChapterAddress(parts[0], parts[1])
                parts.size == 1 && !legacySlug.isNullOrBlank() -> MangittoChapterAddress(legacySlug, parts[0])
                else -> error("Mangitto: eski bölüm kaydında seri adresi eksik. Seri sayfasındaki bölüm listesini yenileyip bölümü tekrar açın.")
            }
            require(address.slug.matches(Regex("[a-zA-Z0-9_-]+")) && address.number.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) {
                "Mangitto: geçersiz bölüm adresi. Bölüm listesini yenileyin."
            }
            return address
        }
    }
}
