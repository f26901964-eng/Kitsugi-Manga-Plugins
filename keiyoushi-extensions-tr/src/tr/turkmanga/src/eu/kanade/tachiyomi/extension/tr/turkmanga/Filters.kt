package eu.kanade.tachiyomi.extension.tr.turkmanga

import eu.kanade.tachiyomi.source.model.Filter

class GenreFilter : Filter.Select<String>("Tür / kategori", GENRES.map { it.first }.toTypedArray()) {
    val selectedGenre: String
        get() = GENRES[state].second
}

// These slugs are the values exposed by Turkmanga's /mangalar?genre=... links and
// kategori_data in the supplied server-rendered pages.
private val GENRES = arrayOf(
    "Tümü" to "",
    "Aksiyon" to "aksiyon",
    "Dram" to "dram",
    "Doğaüstü" to "dogaustu",
    "Fantastik" to "fantastik",
    "Gizem" to "gizem",
    "Shounen" to "shounen",
    "Komedi" to "komedi",
    "Webtoon" to "webtoon",
    "Isekai" to "isekai",
    "Macera" to "macera",
    "Dövüş" to "dovus",
    "Romantizm" to "romantizm",
    "Bilim Kurgu" to "bilim-kurgu",
    "Seinen" to "seinen",
    "Yaşamdan Kesitler" to "yasamdan-kesitler",
    "Okul" to "okul",
    "Ecchi" to "ecchi",
    "Romantik" to "romantik",
    "Psikolojik" to "psikolojik",
    "Harem" to "harem",
    "Korku" to "korku",
    "Eşcinsel" to "escinsel",
    "Yaratık" to "yaratik",
    "Çizim" to "cizim",
    "Gerilim" to "gerilim",
    "Tarih" to "tarih",
    "Büyü" to "buyu",
    "Gerçekçi" to "gercekci",
    "Trajedi" to "trajedi",
    "Spor" to "spor",
    "Dövüş Sanatları" to "dovus-sanatlar",
    "Yaoi hariç" to "-yaoi",
    "Yuri hariç" to "-yuri",
    "+18" to "nsfw",
)
