package com.kitsugi.extension.tr.mangadenizi

import com.kitsugi.animelist.data.manga.ExtensionEngine
import com.kitsugi.animelist.data.manga.MangaChapter
import com.kitsugi.animelist.data.manga.MangaDetails
import com.kitsugi.animelist.data.manga.MangaPage
import com.kitsugi.animelist.data.manga.MangaSource
import com.kitsugi.animelist.data.manga.MangaSourceResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** A no-argument, native Kitsugi MEX: no KeiSource, OkHttp 5, WebView or app rebuild. */
class MangaDeniziSource : MangaSource {
    // Explicitly implement all properties/methods; do not call a compile-only DefaultImpls.
    override val name = MangaDeniziApi.NAME
    override val baseUrl = MangaDeniziApi.SITE
    override val originalBaseUrl = MangaDeniziApi.SITE
    override val lang = "tr"
    override val pkgName = "com.kitsugi.extension.tr.mangadenizi"
    override val engineType = ExtensionEngine.CUSTOM_HTML
    override val isArchive = false

    private val api = MangaDeniziApi(OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build())
    // Host prefetches several pages. Serialize bitmap work to avoid several double-bitmaps in memory.
    private val images = Mutex()

    override suspend fun fetchPopularManga(page: Int): MangaSourceResult = api.listing(page)
    override suspend fun fetchSearchManga(page: Int, query: String): MangaSourceResult = api.listing(page, query)
    override suspend fun fetchMangaDetails(mangaUrl: String): MangaDetails = api.details(mangaUrl)
    override suspend fun fetchChapterList(mangaUrl: String): List<MangaChapter> = api.chapters(mangaUrl)
    override suspend fun fetchPageList(chapter: MangaChapter): List<MangaPage> = api.pages(chapter).mapIndexed { index, page ->
        MangaPage(index = index, url = api.chapterUrl(api.chapterRef(chapter.url)), imageUrl = page.imageUrl)
    }
    override suspend fun fetchImageUrl(page: MangaPage): String = page.imageUrl ?: throw IOException("Sayfanın görsel adresi yok")
    override suspend fun getImage(page: MangaPage): InputStream = withContext(Dispatchers.IO) {
        images.withLock {
            val image = api.imageRef(fetchImageUrl(page))
            val bytes = api.imageBytes(image.url, page.url)
            ByteArrayInputStream(TiledImage.restore(bytes, image.grid, image.seed, currentCoroutineContext()))
        }
    }
}
