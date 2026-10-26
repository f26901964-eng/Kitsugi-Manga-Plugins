package com.kitsugi.extension.tr.mangadenizi

import com.kitsugi.animelist.data.manga.MangaChapter
import com.kitsugi.animelist.data.manga.MangaStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class MangaDeniziApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: MangaDeniziApi
    private val seriesUrl = "https://mangadenizi.net/manga/test-seri"
    private val chapter = MangaChapter("https://mangadenizi.net/read/test-seri/07", "Bölüm 7", mangaUrl = seriesUrl)
    @Before fun setUp() { server = MockWebServer(); server.start(); api = MangaDeniziApi(OkHttpClient(), server.url("/").toString().trimEnd('/')) }
    @After fun tearDown() { server.shutdown() }
    private fun reply(value: Any) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(value.toString())) }
    private fun series(): JSONObject = JSONObject("""{"slug":"test-seri","title":"Doğru Seri","description":"<p>Açıklama</p>","cover_url":"https://mangadenizi.net/storage/real.webp","status":"ongoing","type":{"name":"Manhwa"},"formats":[{"name":"Webtoon"}],"categories":[{"name":"Aksiyon"}],"authors":[{"name":"Yazar","role":"author"},{"name":"Çizer","role":"artist"}],"chapters":[{"slug":"07","number":7,"title":null,"published_at":"2026-09-03T19:38:00.000000Z","publishing_team":{"name":"MangaDenizi"}},{"slug":"001","number":1,"title":"Başlangıç"},{"slug":"0-5","number":0.5,"title":"Özel"}]}""")
    private fun details(value: JSONObject = series()): JSONObject = JSONObject().put("data", JSONObject().put("manga", value))
    private fun listing(current: Int = 1, last: Int = 2): JSONObject = JSONObject().put("data", JSONObject().put("manga", JSONObject().put("current_page", current).put("last_page", last).put("data", JSONArray().put(series()))))
    private fun reader(): JSONObject = JSONObject("""{"manga":{"slug":"test-seri"},"chapter":{"slug":"07","pages_count":2},"pages":[{"page_number":1,"image_url":"https://img.mangadenizi.net/reader-images/test/001.webp","scramble":{"method":"tiled-v1","grid":10,"seed":4294967295}},{"page_number":2,"image_url":"https://img.mangadenizi.net/reader-images/test/002.webp","scramble":null}]}""")

    @Test fun popularPaginationAndHeaders() = runBlocking {
        reply(listing()); val value = api.listing(1)
        assertTrue(value.hasNextPage); assertEquals(seriesUrl, value.mangas.single().url)
        val request = server.takeRequest(); assertEquals("popular", request.requestUrl!!.queryParameter("sort")); assertEquals("1", request.requestUrl!!.queryParameter("page"))
        assertEquals(MangaDeniziApi.SITE, request.getHeader("Origin")); assertTrue(request.getHeader("User-Agent")!!.contains("Chrome/"))
    }
    @Test fun finalPageStopsPagination() = runBlocking { reply(listing(2, 2)); assertFalse(api.listing(2).hasNextPage) }
    @Test fun searchEncodesTurkishAndDoesNotUsePopularSort() = runBlocking { reply(listing()); api.listing(1, " İki Çocuk "); val r = server.takeRequest().requestUrl!!; assertEquals("İki Çocuk", r.queryParameter("q")); assertNull(r.queryParameter("sort")) }
    @Test fun wrongPaginationFails() { reply(listing(2)); assertThrows(IOException::class.java) { runBlocking { api.listing(1) } } }
    @Test fun duplicateCatalogueEntriesFail() { val data = listing(); data.getJSONObject("data").getJSONObject("manga").getJSONArray("data").put(series()); reply(data); assertThrows(IOException::class.java) { runBlocking { api.listing(1) } } }
    @Test fun correctDetailsCoverAndRequiredMetadata() = runBlocking {
        reply(details()); val result = api.details(seriesUrl)
        assertEquals("Doğru Seri", result.title); assertEquals("https://mangadenizi.net/storage/real.webp", result.thumbnailUrl)
        assertEquals("Yazar", result.author); assertEquals("Çizer", result.artist); assertEquals("Açıklama", result.description)
        assertEquals(MangaStatus.Ongoing, result.status); assertTrue(result.genre.containsAll(listOf("Manhwa", "Webtoon", "Aksiyon")))
    }
    @Test fun mismatchedSelectedMangaFails() { reply(details(series().put("slug", "baska-seri"))); assertThrows(IOException::class.java) { runBlocking { api.details(seriesUrl) } } }
    @Test fun chaptersAreCompleteLatestFirstWithFractionalNumbers() = runBlocking {
        val data = series(); val original = data.getJSONArray("chapters"); data.put("chapters", JSONArray().put(original[1]).put(original[2]).put(original[0])); reply(details(data))
        val values = api.chapters(seriesUrl); assertEquals(listOf(7f, 1f, 0.5f), values.map { it.chapterNumber }); assertTrue(values.all { it.mangaUrl == seriesUrl })
        assertTrue(values[0].uploadDate > 0); assertEquals("MangaDenizi", values[0].scanlator); assertFalse(values[0].name.contains("null"))
    }
    @Test fun duplicateChaptersFail() { val data = series(); data.getJSONArray("chapters").put(data.getJSONArray("chapters")[0]); reply(details(data)); assertThrows(IOException::class.java) { runBlocking { api.chapters(seriesUrl) } } }
    @Test fun allPagesAndUnsignedSeedsPreserved() = runBlocking {
        reply(reader()); val pages = api.pages(chapter); assertEquals(listOf(1, 2), pages.map { it.number })
        val ref = api.imageRef(pages[0].imageUrl); assertEquals(4294967295L, ref.seed); assertEquals(10, ref.grid); assertFalse(ref.url.contains("#")); assertFalse(ref.url.contains("scramble_seed"))
        assertNull(api.imageRef(pages[1].imageUrl).grid)
    }
    @Test fun pagesAreSortedByPageNumberNotAccidentalArrayOrder() = runBlocking {
        val value = reader(); val pages = value.getJSONArray("pages"); value.put("pages", JSONArray().put(pages[1]).put(pages[0])); reply(value)
        assertEquals(listOf(1, 2), api.pages(chapter).map { it.number })
    }
    @Test fun missingPageFails() { val value = reader(); value.getJSONArray("pages").remove(1); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun duplicatePageNumberFails() { val value = reader(); value.getJSONArray("pages").getJSONObject(1).put("page_number", 1); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun fractionalPageNumberFails() { val value = reader(); value.getJSONArray("pages").getJSONObject(1).put("page_number", 1.5); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun duplicateImageFails() { val value = reader(); value.getJSONArray("pages").getJSONObject(1).put("image_url", value.getJSONArray("pages").getJSONObject(0).getString("image_url")); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun wrongChapterFails() { reply(reader().apply { getJSONObject("chapter").put("slug", "08") }); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun chapterFromDifferentMangaFailsBeforeNetwork() { assertThrows(IOException::class.java) { runBlocking { api.pages(chapter.copy(mangaUrl = "https://mangadenizi.net/manga/baska")) } }; assertEquals(0, server.requestCount) }
    @Test fun unknownImageTransformFailsInsteadOfReturningUnreadableTiles() { val value = reader(); value.getJSONArray("pages").getJSONObject(0).getJSONObject("scramble").put("method", "unknown-v2"); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun incompleteImageTransformFails() { val value = reader(); value.getJSONArray("pages").getJSONObject(0).getJSONObject("scramble").remove("seed"); reply(value); assertThrows(IOException::class.java) { runBlocking { api.pages(chapter) } } }
    @Test fun rejectsForeignImageDomains() { assertThrows(IOException::class.java) { api.imageRef("https://mangadenizi.net.evil.example/a.webp") }; assertThrows(IOException::class.java) { api.imageRef("http://img.mangadenizi.net/a.webp") } }
    @Test fun rejectsForeignSeriesUrls() { assertThrows(IOException::class.java) { api.mangaSlug("https://evil.example/manga/test-seri") } }
    @Test fun http404DoesNotRetry() { server.enqueue(MockResponse().setResponseCode(404)); assertThrows(IOException::class.java) { runBlocking { api.listing(1) } }; assertEquals(1, server.requestCount) }
    @Test fun serverErrorRetriesOnce() = runBlocking { server.enqueue(MockResponse().setResponseCode(503)); reply(listing()); assertEquals(1, api.listing(1).mangas.size); assertEquals(2, server.requestCount) }
    @Test fun interruptedBodyIsRetriedOnce() = runBlocking { server.enqueue(MockResponse().setBody(listing().toString()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)); reply(listing()); assertEquals(1, api.listing(1).mangas.size); assertEquals(2, server.requestCount) }
    @Test fun oversizedResponsesAreRejectedWithoutRetry() { server.enqueue(MockResponse().setBody("{}").setHeader("Content-Length", MangaDeniziApi.MAX_BYTES.toLong() + 1)); assertThrows(IOException::class.java) { runBlocking { api.listing(1) } }; assertEquals(1, server.requestCount) }
    @Test fun cancellationCancelsNetworkCall() { server.enqueue(MockResponse().setBody(listing().toString()).setBodyDelay(3, TimeUnit.SECONDS)); assertThrows(kotlinx.coroutines.TimeoutCancellationException::class.java) { runBlocking { withTimeout(150) { api.listing(1) } } }; assertEquals(1, server.requestCount) }
    @Test fun layoutCoversEverySourceAndDestinationPixelExactlyOnce() {
        for (seed in listOf(0L, 1L, 778695995L, 1979497893L, 2246822507L, 2654435769L, 4294967295L)) {
            for (width in listOf(1, 9, 19, 37)) for (height in listOf(1, 7, 23)) {
                val source = IntArray(width * height); val target = IntArray(source.size)
                for (tile in TileLayout.restore(width, height, 10, seed)) for (y in 0 until tile.height) for (x in 0 until tile.width) {
                    source[(tile.sy + y) * width + tile.sx + x]++
                    target[(tile.dy + y) * width + tile.dx + x]++
                }
                assertTrue(source.all { it == 1 }); assertTrue(target.all { it == 1 })
            }
        }
    }
}
