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
 * Genre matching is exact and the site stores the labels in the language a series was added with:
 * newer rows carry Turkish names (`Aksiyon`), older ones English names (`Action`), and some genres
 * only exist with one of the two. A comma separated list is read as AND and a repeated `genre`
 * parameter keeps only its first value, so "Turkish or English" cannot be expressed in one query.
 *
 * Every group therefore sends the single label that actually returns series today. The values were
 * measured against the live API (`tools/mangadiyari_probe.py`, check `genre-labels`) and both files
 * are kept in step by `tests/test_mangadiyari_probe.py`.
 */
class GenreFilter :
    Filter.Group<GenreCheckBox>(
        "Kategoriler",
        listOf(
            GenreCheckBox("Aksiyon", "Aksiyon"),
            GenreCheckBox("Macera", "Macera"),
            GenreCheckBox("Komedi", "Komedi"),
            GenreCheckBox("Dram", "Dram"),
            GenreCheckBox("Fantastik", "Fantezi"),
            GenreCheckBox("Tarihi", "Tarihi"),
            GenreCheckBox("Korku", "Korku"),
            GenreCheckBox("Isekai", "Isekai"),
            GenreCheckBox("Dövüş Sanatları", "Dövüş Sanatları"),
            GenreCheckBox("Gizem", "Gizem"),
            GenreCheckBox("Reenkarnasyon", "Reincarnation"),
            GenreCheckBox("Romantik", "Romantizm"),
            GenreCheckBox("Okul", "Okul Hayatı"),
            GenreCheckBox("Bilim Kurgu", "Bilim Kurgu"),
            GenreCheckBox("Doğaüstü", "Doğaüstü"),
            GenreCheckBox("Gerilim", "Gerilim"),
            GenreCheckBox("Ecchi", "Ecchi"),
            GenreCheckBox("Harem", "Harem"),
            GenreCheckBox("Josei", "Josei"),
            GenreCheckBox("Yetişkin", "Müstehcen"),
            GenreCheckBox("Mecha", "Mecha"),
            GenreCheckBox("Psikolojik", "Psikolojik"),
            GenreCheckBox("Seinen", "Seinen"),
            GenreCheckBox("Shoujo", "Shoujo"),
            GenreCheckBox("Shounen", "Shounen"),
            GenreCheckBox("Günlük Yaşam", "Slice of Life"),
            GenreCheckBox("Spor", "Spor"),
            GenreCheckBox("Trajedi", "Trajedi"),
            GenreCheckBox("Webtoon", "Webtoon"),
            GenreCheckBox("Manhwa", "Manhwa"),
            GenreCheckBox("Manhua", "Manhua"),
            GenreCheckBox("Murim", "Murim"),
            GenreCheckBox("Sistem", "Sistem"),
            GenreCheckBox("Şeytan", "Şeytan"),
            GenreCheckBox("Ters Harem", "Ters Harem"),
        ),
    ) {
    /** Selected groups are sent in one `genre` parameter; the API reads the list as AND. */
    fun toUriPart(): String = state.filter { it.state }
        .map { it.value.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")
}
