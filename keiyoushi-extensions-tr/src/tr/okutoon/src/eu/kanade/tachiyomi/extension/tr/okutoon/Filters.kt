package eu.kanade.tachiyomi.extension.tr.okutoon

import eu.kanade.tachiyomi.source.model.Filter

open class UriPartFilter(displayName: String, private val vals: Array<Pair<String, String>>) : Filter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
    fun toUriPart() = vals[state].second
}

class SortFilter :
    UriPartFilter(
        "Sıralama",
        arrayOf(
            Pair("En Yeni", "newest"),
            Pair("Son Güncellenenler", "updated"),
            Pair("En Popüler", "popular"),
            Pair("En Yüksek Puan", "rating"),
            Pair("A-Z", "az"),
        ),
    ) {
    init {
        state = 1 // Defaults to 'Son Güncellenenler' exactly like the website
    }
}

class StatusFilter :
    UriPartFilter(
        "Durum",
        arrayOf(
            Pair("Tümü", ""),
            Pair("Devam Ediyor", "ongoing"),
            Pair("Tamamlandı", "completed"),
            Pair("Ara Verildi", "hiatus"),
        ),
    )

class TypeFilter :
    UriPartFilter(
        "Tür",
        arrayOf(
            Pair("Tümü", ""),
            Pair("Webtoon", "webtoon"),
            Pair("Manga", "manga"),
        ),
    )

class Genre(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<Genre>) : Filter.Group<Genre>("Türler", genres)

// The site groups its checkboxes into "Okuyucu kitlesi" and "Türler"; keep that order.
fun getGenreList() = listOf(
    Genre("Shounen", "shounen"),
    Genre("Seinen", "seinen"),
    Genre("Shoujo", "shoujo"),
    Genre("Josei", "josei"),
    Genre("Aile", "aile"),
    Genre("Aksiyon", "aksiyon"),
    Genre("Askeri", "askeri"),
    Genre("Bilim Kurgu", "bilim-kurgu"),
    Genre("Doğaüstü", "dogaustu"),
    Genre("Dövüş Sanatları", "dovus-sanatlari"),
    Genre("Dram", "dram"),
    Genre("Ecchi", "ecchi"),
    Genre("Fantezi", "fantezi"),
    Genre("Gerilim", "gerilim"),
    Genre("Gizem", "gizem"),
    Genre("Günlük Hayat", "gunluk-hayat"),
    Genre("Harem", "harem"),
    Genre("Hayatta Kalma", "hayatta-kalma"),
    Genre("Isekai", "isekai"),
    Genre("Kıyamet", "kiyamet"),
    Genre("Komedi", "komedi"),
    Genre("Korku", "korku"),
    Genre("Macera", "macera"),
    Genre("Murim", "murim"),
    Genre("Okul Hayatı", "okul-hayati"),
    Genre("Oyun", "oyun"),
    Genre("Psikolojik", "psikolojik"),
    Genre("Reenkarnasyon", "reenkarnasyon"),
    Genre("Romantizm", "romantizm"),
    Genre("Sihir", "sihir"),
    Genre("Sistem", "sistem"),
    Genre("Spor", "spor"),
    Genre("Suç", "suc"),
    Genre("Süper Kahraman", "super-kahraman"),
    Genre("Tarihi", "tarihi"),
    Genre("Vampir", "vampir"),
    Genre("Zamanda Yolculuk", "zamanda-yolculuk"),
    Genre("Zindan", "zindan"),
    Genre("Zombi", "zombi"),
)
