package eu.kanade.tachiyomi.extension.tr.sadscans

import eu.kanade.tachiyomi.source.model.Filter

/**
 * Sitenin `/seriler` sayfasındaki gerçek menülerle birebir eşleşen filtreler.
 *
 * Değerler canlı JS parçasından (`1vy0fm3wxax8_.js`) doğrulandı:
 *  - `sortBy`: `name`, `-name`, `views`, `-views`, `rating`, `-rating`
 *  - `type`: `Manga`, `Webtoon`, `One Shot`
 *  - `status`: `devam-ediyor`, `tamamlandi`, `birakildi`
 *
 * "Güncel" sıralaması sitede yok; site yalnız isim/okunma/puan sıralaması sunuyor.
 */
class SortFilter : Filter.Select<String>("Sıralama", SORT_NAMES) {
    val selected: String
        get() = SORT_VALUES[state.coerceIn(0, SORT_VALUES.lastIndex)]

    companion object {
        private val SORT_NAMES = arrayOf(
            "İsme göre (A-Z)",
            "İsme göre (Z-A)",
            "En çok okunan",
            "En az okunan",
            "En yüksek puan",
            "En düşük puan",
        )
        private val SORT_VALUES = arrayOf("name", "-name", "views", "-views", "rating", "-rating")
    }
}

class TypeFilter : Filter.Select<String>("Tür", TYPE_NAMES) {
    val selected: String?
        get() = if (state == 0) null else TYPE_VALUES[state.coerceIn(1, TYPE_VALUES.lastIndex)]

    companion object {
        private val TYPE_NAMES = arrayOf("Hepsi", "Manga", "Webtoon", "One Shot")
        private val TYPE_VALUES = arrayOf("", "Manga", "Webtoon", "One Shot")
    }
}

class StatusFilter : Filter.Select<String>("Durum", STATUS_NAMES) {
    val selected: String?
        get() = if (state == 0) null else STATUS_VALUES[state.coerceIn(1, STATUS_VALUES.lastIndex)]

    companion object {
        private val STATUS_NAMES = arrayOf("Hepsi", "Devam ediyor", "Tamamlandı", "Bırakıldı")
        private val STATUS_VALUES = arrayOf("", "devam-ediyor", "tamamlandi", "birakildi")
    }
}
