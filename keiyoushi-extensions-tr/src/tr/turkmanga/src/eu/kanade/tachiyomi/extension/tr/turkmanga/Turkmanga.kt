package eu.kanade.tachiyomi.extension.tr.turkmanga

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Turkmanga : KeiSource() {

    override fun OkHttpClient.Builder.configureClient() = rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseList(TurkmangaParser.listingUrl(baseUrl, page, "popular"), page)

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseList(TurkmangaParser.listingUrl(baseUrl, page, "latest"), page, newestFirst = true)

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selectedGenre.orEmpty()
        val url = TurkmangaParser.searchUrl(baseUrl, page, query, genre)
        return parseList(url, page)
    }

    private suspend fun parseList(url: HttpUrl, page: Int, newestFirst: Boolean = false): MangasPage =
        TurkmangaParser.parseMangaList(client.get(url).asJsoup(), baseUrl, page, newestFirst)

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(GenreFilter())

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = TurkmangaParser.mangaPath(url.toString(), baseUrl) ?: return null
        return SManga.create().apply {
            this.url = path
            title = path.substringAfterLast('/').replace('-', ' ')
        }
    }

    override fun getMangaUrl(manga: SManga): String = TurkmangaParser.mangaUrl(manga.url, baseUrl)

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        return TurkmangaParser.parseMangaDetails(document, baseUrl, manga.url)
    }

    override fun getChapterUrl(chapter: SChapter): String = TurkmangaParser.chapterUrl(chapter.url, baseUrl)

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).asJsoup()
        val imageUrls = TurkmangaParser.parseImageUrls(document, baseUrl, chapterUrl)
        if (imageUrls.isEmpty()) throw IOException("Turkmanga bölümünde okunabilir sayfa görselleri bulunamadı.")
        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, url = chapterUrl, imageUrl = imageUrl)
        }
    }

    override fun imageRequest(page: Page): Request {
        val referer = page.url.toHttpUrlOrNull()
            ?.takeIf { it.host == baseUrl.toHttpUrl().host || it.host == "www.${baseUrl.toHttpUrl().host}" }
            ?.toString()
            ?: "$baseUrl/"
        return super.imageRequest(page).newBuilder()
            .header("Referer", referer)
            .build()
    }
}
