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
 * The site stores genres in the language the series was added with: the newer rows carry Turkish
 * names (`Aksiyon`) and the older ones English names (`Action`), and the API matches the stored
 * label exactly, so a single label list only ever finds one half of the catalog.
 *
 * Every entry below therefore sends the Turkish label first and, after it, the English label the
 * same genre is stored under, joined with `,`. The API treats a comma separated list as **AND**, so
 * `Aksiyon,Action` finds the rows that carry both labels. That is the right shape here: those rows
 * are exactly the series that have this one genre.
 */
class GenreFilter :
    Filter.Group<GenreCheckBox>(
        "Kategoriler",
        listOf(
            GenreCheckBox("Aksiyon", "Aksiyon,Action"),
            GenreCheckBox("Macera", "Macera,Adventure"),
            GenreCheckBox("Komedi", "Komedi,Comedy"),
            GenreCheckBox("Dram", "Dram,Drama"),
            GenreCheckBox("Fantastik", "Fantezi,Fantasy"),
            GenreCheckBox("Tarihi", "Tarihi,Historical"),
            GenreCheckBox("Korku", "Korku,Horror"),
            GenreCheckBox("Isekai", "Isekai"),
            GenreCheckBox("Dövüş Sanatları", "Dövüş Sanatları,Martial Arts"),
            GenreCheckBox("Gizem", "Gizem,Mystery"),
            GenreCheckBox("Reenkarnasyon", "Reenkarnasyon,Reincarnation"),
            GenreCheckBox("Romantik", "Romantizm,Romance"),
            GenreCheckBox("Okul", "Okul Hayatı,School Life"),
            GenreCheckBox("Bilim Kurgu", "Bilim Kurgu,Science Fiction"),
            GenreCheckBox("Doğaüstü", "Doğaüstü,Supernatural"),
            GenreCheckBox("Gerilim", "Gerilim,Thriller"),
            GenreCheckBox("Ecchi", "Ecchi"),
            GenreCheckBox("Harem", "Harem"),
            GenreCheckBox("Josei", "Josei"),
            GenreCheckBox("Yetişkin", "Müstehcen,Mature"),
            GenreCheckBox("Mecha", "Mecha"),
            GenreCheckBox("Psikolojik", "Psikolojik,Psychological"),
            GenreCheckBox("Seinen", "Seinen"),
            GenreCheckBox("Shoujo", "Shoujo"),
            GenreCheckBox("Shounen", "Shounen"),
            GenreCheckBox("Günlük Yaşam", "Günlük Yaşam,Slice of Life"),
            GenreCheckBox("Spor", "Spor,Sports"),
            GenreCheckBox("Trajedi", "Trajedi,Tragedy"),
            GenreCheckBox("Webtoon", "Webtoon"),
            GenreCheckBox("Manhwa", "Manhwa"),
            GenreCheckBox("Manhua", "Manhua"),
            GenreCheckBox("Murim", "Murim"),
            GenreCheckBox("Sistem", "Sistem"),
            GenreCheckBox("Şeytan", "Şeytan,Demon"),
            GenreCheckBox("Ters Harem", "Ters Harem,Reverse Harem"),
        ),
    ) {
    /** Every selected group is sent in one `genre` parameter, comma separated; the API reads it as AND. */
    fun toUriPart(): String = state.filter { it.state }.flatMap { it.value.split(',') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(",")
}
