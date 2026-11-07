package eu.kanade.tachiyomi.extension.tr.epiknovel

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Epik Novel — Türkçe web novel sitesi (www.epiknovel.com).
 *
 * Katalog `/seri-listesi` üzerinden çalışır: `q` (arama), `Sayfa` (sayfa),
 * `sorting` (name|views|liked|rated|lastUpdate), `sorting-type` (ASC|DESC),
 * `durum` (0 devam ediyor | 1 tamamlandı). Sonuç kartları yalnız `#pop-href`
 * içindedir; kapaklar `data-src` üzerindedir.
 *
 * Bölümler düz metindir; görüntü okuyucuya [TextPageInterceptor] ile PNG
 * sayfa olarak aktarılır (ayrıntılar sınıf dokümanında).
 */
@Source
abstract class EpikNovel : KeiSource() {

    private val textPageInterceptor by lazy { TextPageInterceptor(baseUrl) }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = apply {
        // Sanal metin-sayfası istekleri; KeiSource CF → Compression sıralaması korunur.
        addInterceptor(textPageInterceptor)
    }

    // ------------------------------------------------------------- lists

    override suspend fun getPopularManga(page: Int): MangasPage =
        mangaList(page, query = null, sort = "views", direction = "DESC", status = null)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        mangaList(page, query = null, sort = "lastUpdate", direction = "DESC", status = null)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.toUriPart()
        val direction = filters.firstInstanceOrNull<DirectionFilter>()?.toUriPart() ?: "DESC"
        val status = filters.firstInstanceOrNull<StatusFilter>()?.toUriPart()

        // Sıralama seçilmediğinde site varsayılanı (beğeni, azalan) korunur;
        // yalnız "Artan" seçiliyse yön tek başına gönderilir.
        val effectiveSort: String?
        val effectiveDirection: String?
        if (sort != null) {
            effectiveSort = sort
            effectiveDirection = direction
        } else {
            effectiveSort = null
            effectiveDirection = if (direction == "ASC") "ASC" else null
        }
        return mangaList(page, query, effectiveSort, effectiveDirection, status)
    }

    private suspend fun mangaList(
        page: Int,
        query: String?,
        sort: String?,
        direction: String?,
        status: String?,
    ): MangasPage {
        val document = client.get(listUrl(page, query, sort, direction, status)).asJsoup()
        val mangas = EpikNovelParser.mangasFromListPage(document)
        return MangasPage(mangas, EpikNovelParser.hasNextPage(document, page))
    }

    private fun listUrl(
        page: Int,
        query: String?,
        sort: String?,
        direction: String?,
        status: String?,
    ): HttpUrl = "$baseUrl/seri-listesi".toHttpUrl().newBuilder().apply {
        if (!query.isNullOrBlank()) addQueryParameter("q", query)
        if (page > 1) addQueryParameter("Sayfa", page.toString())
        if (!sort.isNullOrEmpty()) addQueryParameter("sorting", sort)
        if (!direction.isNullOrEmpty()) addQueryParameter("sorting-type", direction)
        if (!status.isNullOrEmpty()) addQueryParameter("durum", status)
    }.build()

    // ----------------------------------------------------------- details

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = getMangaUrl(manga)
        val document = client.get(url).asJsoup()

        val details = if (fetchDetails) {
            EpikNovelParser.mangaFromDetails(document).also {
                if (it.title.isBlank()) throw IOException("Epik Novel: seri sayfası okunamadı ($url)")
            }
        } else {
            manga
        }
        val chapterList = if (fetchChapters) {
            EpikNovelParser.chaptersFromDetails(document).ifEmpty {
                throw IOException("Epik Novel: bölüm listesi bulunamadı ($url)")
            }
        } else {
            chapters
        }
        return SMangaUpdate(details, chapterList)
    }

    /** Adres çubuğuna yapıştırılan seri bağlantısı aramadan da çözülebilsin. */
    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val segments = url.pathSegments.filter { it.isNotBlank() }
        if (segments.getOrNull(0) != "seri" || segments.size < 2) return null
        val document = client.get(url).asJsoup()
        return EpikNovelParser.mangaFromDetails(document).takeIf { it.title.isNotBlank() }
    }

    // ------------------------------------------------------------ pages

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()
        val content = EpikNovelParser.chapterContent(document)
        if (content.isEmpty) {
            throw IOException("Epik Novel: bölüm metni bulunamadı, sayfa üretilemedi ($chapterUrl)")
        }
        // Sayfa sayısı saf EpikNovelContent.paginate ile hesaplanır; interceptor
        // aynı işlevi kullandığından sınırlar birebir eşleşir.
        val pageCount = EpikNovelContent.pageCount(content.blocks)
        return (0 until pageCount).map { index ->
            Page(index, url = chapterUrl, imageUrl = TextPageInterceptor.pageUrl(baseUrl, chapterUrl, index))
        }
    }

    // ----------------------------------------------------------- filters

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        DirectionFilter(),
        StatusFilter(),
    )

    class SortFilter : Filter.Select<String>(
        "Sırala",
        arrayOf("Varsayılan", "Son güncelleme", "Gösterim", "Beğeni", "Puan", "İsim"),
    ) {
        fun toUriPart(): String? = when (state) {
            0 -> null
            1 -> "lastUpdate"
            2 -> "views"
            3 -> "liked"
            4 -> "rated"
            5 -> "name"
            else -> null
        }
    }

    class DirectionFilter : Filter.Select<String>("Yön", arrayOf("Azalan", "Artan")) {
        fun toUriPart(): String = if (state == 1) "ASC" else "DESC"
    }

    class StatusFilter : Filter.Select<String>(
        "Durum",
        arrayOf("Hepsi", "Devam Eden", "Tamamlanan"),
    ) {
        fun toUriPart(): String? = when (state) {
            0 -> null
            1 -> "0" // durum=0 → Devam ediyor
            2 -> "1" // durum=1 → Tamamlandı
            else -> null
        }
    }
}
