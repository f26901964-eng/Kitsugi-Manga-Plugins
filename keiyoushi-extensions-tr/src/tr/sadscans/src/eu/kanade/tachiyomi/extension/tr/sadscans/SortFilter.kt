package eu.kanade.tachiyomi.extension.tr.sadscans

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter : Filter.Select<String>("Sıralama", SORT_NAMES) {
    val selected: Pair<String, String>
        get() = SORT_ORDERS[state]

    companion object {
        private val SORT_NAMES = arrayOf(
            "Son güncellenen",
            "Popüler",
            "En yeni eklenen",
            "İsme göre (A-Z)",
        )
        private val SORT_ORDERS = arrayOf(
            "updatedAt" to "desc",
            "totalViews" to "desc",
            "createdAt" to "desc",
            "postTitle" to "asc",
        )
    }
}
