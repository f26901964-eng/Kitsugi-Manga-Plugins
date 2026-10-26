package com.kitsugi.animelist.data.manga.golgebahcesi

import android.content.Context
import com.kitsugi.animelist.data.manga.ExtensionEngine
import com.kitsugi.animelist.data.manga.MangaChapter
import com.kitsugi.animelist.data.manga.MangaDetails
import com.kitsugi.animelist.data.manga.MangaPage
import com.kitsugi.animelist.data.manga.MangaSource
import com.kitsugi.animelist.data.manga.MangaSourceResult
import com.kitsugi.animelist.data.manga.MangaStatus
import com.kitsugi.animelist.data.manga.SourceConfigStore
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import java.io.IOException
import java.io.InputStream

/** Native adapter: no dynamically loaded KeiSource, OkHttp 5, QuickJS or Kotlin 2.4 ABI. */
internal class GolgeBahcesiSource(
    context: Context,
    client: OkHttpClient,
    defaultUserAgent: () -> String,
) : MangaSource {
    override val name = "Gölge Bahçesi"
    override val baseUrl = "https://golgebahcesi.com"
    override val lang = "tr"
    // Keep the existing APK identity for saved selections; only the runtime changes.
    override val pkgName = "eu.kanade.tachiyomi.extension.tr.golgebahcesi"
    override val engineType = ExtensionEngine.CUSTOM_HTML

    private val config = SourceConfigStore(context)
    private val userAgent = { config.getUserAgentOverride(this) ?: defaultUserAgent() }
    private val api = GolgeBahcesiApi(client, userAgent)
    private val reader = GolgeBahcesiReader(context.applicationContext, userAgent)

    override suspend fun fetchPopularManga(page: Int): MangaSourceResult = api.list(page, popular = true).toResult()

    override suspend fun fetchSearchManga(page: Int, query: String): MangaSourceResult = api.list(page, query).toResult()

    override suspend fun fetchMangaDetails(mangaUrl: String): MangaDetails = api.details(mangaUrl).toDetails()

    override suspend fun fetchChapterList(mangaUrl: String): List<MangaChapter> = api.chapters(mangaUrl).map { chapter ->
        MangaChapter(
            url = api.chapterUrl(chapter.ref),
            name = chapter.title + if (chapter.isLocked) " (Kilitli)" else "",
            chapterNumber = chapter.number,
            uploadDate = chapter.uploadDate,
            mangaUrl = api.mangaUrl(chapter.ref.seriesSlug),
        )
    }

    override suspend fun fetchPageList(chapter: MangaChapter): List<MangaPage> {
        val ref = api.resolveChapter(chapter.url)
        val content = api.content(ref) // Locked chapters fail here, before a WebView is opened.
        val readerUrl = api.readerUrl(ref)
        if (content.isSecure) {
            return reader.pages(readerUrl, content.expectedPageCount).indices.map { index ->
                MangaPage(index = index, url = readerUrl, imageUrl = reader.pageUrl(readerUrl, index))
            }
        }
        return content.images.mapIndexed { index, image ->
            MangaPage(index = index, url = readerUrl, imageUrl = image.url)
        }
    }

    override suspend fun getImage(page: MangaPage): InputStream {
        val url = (page.imageUrl ?: throw IOException("Sayfanın görsel adresi yok")).toHttpUrl()
        if (url.host == GolgeBahcesiReader.PAGE_HOST) {
            // These are local reader cache keys, never public network requests or huge URL fragments.
            return reader.openImage(page.url, page.index, url.toString())
        }
        return api.openImage(url.toString(), page.url)
    }

    private fun GolgeBahcesiApi.Listing.toResult(): MangaSourceResult = MangaSourceResult(series.map { it.toDetails() }, hasNextPage)

    private fun GolgeBahcesiApi.Series.toDetails(): MangaDetails = MangaDetails(
        url = api.mangaUrl(slug),
        title = title,
        author = author,
        artist = artist,
        description = description?.let { Jsoup.parseBodyFragment(it).text() },
        genre = genres,
        thumbnailUrl = coverUrl,
        status = when (status?.uppercase(java.util.Locale.ROOT)) {
            "ONGOING" -> MangaStatus.Ongoing
            "COMPLETED" -> MangaStatus.Completed
            "HIATUS" -> MangaStatus.OnHiatus
            "DROPPED", "CANCELLED" -> MangaStatus.Cancelled
            else -> MangaStatus.Unknown
        },
        source = name,
    )
}
