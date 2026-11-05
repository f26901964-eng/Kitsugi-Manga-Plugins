package eu.kanade.tachiyomi.extension.tr.mangadiyari

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class SortFilter :
    UriPartFilter(
        "Sıralama",
        arrayOf(
            "Son Güncellenen" to "latest",
            "En Popüler" to "popular",
            "En Yüksek Puan" to "rating",
            "A-Z" to "title",
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Tür",
        arrayOf(
            "Tümü" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Çizgi Roman" to "comic",
        ),
    )

class StatusFilter :
    UriPartFilter(
        "Durum",
        arrayOf(
            "Tümü" to "",
            "Devam Ediyor" to "ongoing",
            "Tamamlandı" to "completed",
            "Ara Verildi" to "hiatus",
        ),
    )

class GenreCheckBox(name: String, val value: String) : Filter.CheckBox(name)

/**
 * Genres are stored on the site in the language the series was added with, so the catalog mostly
 * carries the Turkish names (`Aksiyon`) while a few older entries carry the English ones (`Action`).
 * The values below are the Turkish names, which cover the larger part of the catalog; the server
 * accepts them case-insensitively. Duplicate selections are collapsed so a number is never sent twice.
 */
class GenreFilter :
    Filter.Group<GenreCheckBox>(
        "Kategoriler",
        listOf(
            "Aksiyon" to "Aksiyon",
            "Macera" to "Macera",
            "Komedi" to "Komedi",
            "Dram" to "Dram",
            "Fantastik" to "Fantezi",
            "Tarihi" to "Tarihi",
            "Korku" to "Korku",
            "Isekai" to "Isekai",
            "Dövüş Sanatları" to "Dövüş Sanatları",
            "Gizem" to "Gizem",
            "Reenkarnasyon" to "Reenkarnasyon",
            "Romantik" to "Romantizm",
            "Okul" to "Okul Hayatı",
            "Bilim Kurgu" to "Bilim Kurgu",
            "Doğaüstü" to "Doğaüstü",
            "Gerilim" to "Gerilim",
            "Ecchi" to "Ecchi",
            "Harem" to "Harem",
            "Josei" to "Josei",
            "Yetişkin" to "Müstehcen",
            "Mecha" to "Mecha",
            "Psikolojik" to "Psikolojik",
            "Seinen" to "Seinen",
            "Shoujo" to "Shoujo",
            "Shounen" to "Shounen",
            "Günlük Yaşam" to "Günlük Yaşam",
            "Spor" to "Spor",
            "Trajedi" to "Trajedi",
            "Webtoon" to "Webtoon",
            "Manhwa" to "Manhwa",
            "Manhua" to "Manhua",
            "Murim" to "Murim",
            "Sistem" to "Sistem",
            "Şeytan" to "Şeytan",
            "Ters Harem" to "Ters Harem",
        ).map { GenreCheckBox(it.first, it.second) },
    ) {
    fun toUriPart(): String = state.filter { it.state }.distinctBy { it.value }.joinToString(",") { it.value }
}
