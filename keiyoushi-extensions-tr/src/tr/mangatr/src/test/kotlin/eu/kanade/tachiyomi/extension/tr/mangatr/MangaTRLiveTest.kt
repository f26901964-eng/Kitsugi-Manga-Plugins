package eu.kanade.tachiyomi.extension.tr.mangatr

import eu.kanade.tachiyomi.source.model.SChapter
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** Opt-in end-to-end smoke test. It never logs cookies, signatures, or image URLs. */
class MangaTRLiveTest {
    @Before
    fun onlyWhenExplicitlyEnabled() {
        assumeTrue(System.getenv("MANGATR_LIVE_TEST") == "1")
    }

    @Test
    fun `signed chapters and all four chapter two readers work end to end`() {
        val cookieJar = MemoryCookieJar()
        val gateClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val client = gateClient.newBuilder()
            .addInterceptor(DDoSGuardInterceptor(gateClient))
            .build()
        val navigationHeaders = navigationHeaders()

        val detailResponse = client.newCall(
            Request.Builder().url(DETAIL_URL).headers(navigationHeaders).get().build(),
        ).execute()
        val detailBody = detailResponse.use { response ->
            assertEquals(200, response.code)
            response.body.string()
        }
        val detail = Jsoup.parse(detailBody, BASE_URL)
        assertEquals("chainsaw-man", detail.selectFirst("#results")?.attr("data-manga"))

        val css = get(client, "$BASE_URL/app/manga/themes/default/assets/css/mtr.min.css", navigationHeaders)
        val seed = requireNotNull(MangaTRContract.seedFromCss(css))

        val chapters = mutableListOf<SChapter>()
        var offset = 0
        var first = true
        var hasMore: Boolean
        var requests = 0
        do {
            val timestamp = System.currentTimeMillis() / 1000
            val form = FormBody.Builder()
                .add("manga", "chainsaw-man")
                .add("tur", "")
                .add("offset", offset.toString())
                .add("_t", timestamp.toString())
                .add("_sig", MangaTRContract.signature("chainsaw-man", seed, timestamp))
                .build()
            val ajax = navigationHeaders.newBuilder()
                .mangaTrAjaxHeaders(DETAIL_URL, acceptsHtml = true)
                .set("Origin", BASE_URL)
                .build()
            val response = client.newCall(
                Request.Builder().url("$BASE_URL/cek/fetch_pages_manga.php").headers(ajax).post(form).build(),
            ).execute()
            val body = response.use {
                assertEquals("Chapter request failed at offset $offset", 200, it.code)
                it.body.string()
            }
            val batch = MangaTRParser.parseChapterDocument(Jsoup.parse(body, BASE_URL))
            assertTrue("No chapter cards at offset $offset", batch.cardCount > 0)
            assertTrue("No translations at offset $offset", batch.chapters.isNotEmpty())
            chapters += batch.chapters
            hasMore = batch.hasMore
            offset = if (first) 20 else offset + 100
            first = false
            requests++
        } while (hasMore && requests < 10)

        assertTrue("Chapter two translations were not returned", chapters.any { it.chapter_number == 2f })
        val chapterTwo = chapters.filter { it.chapter_number == 2f }
            .distinctBy { it.url.substringBefore('?') }
        assertEquals(4, chapterTwo.size)
        assertEquals(EXPECTED_READER_IDS, chapterTwo.mapNotNull { READER_ID.find(it.url)?.groupValues?.get(1) }.toSet())
        assertEquals(
            setOf("Bağımsız", "Invictus Fansub, MangaTR", "AnimeWho, Pied Piper", "Şimarik Fansub"),
            chapterTwo.map { it.scanlator }.toSet(),
        )

        chapterTwo.forEach { chapter ->
            val readerId = requireNotNull(READER_ID.find(chapter.url)?.groupValues?.get(1))
            val readerUrl = BASE_URL + chapter.url
            val readerResponse = client.newCall(
                Request.Builder().url(readerUrl).headers(navigationHeaders).get().build(),
            ).execute()
            val readerBody = readerResponse.use {
                assertEquals("Reader $readerId failed", 200, it.code)
                it.body.string()
            }
            val pages = MangaTRParser.parseReaderPages(Jsoup.parse(readerBody, readerUrl))
            assertTrue("Reader $readerId returned no pages", pages.isNotEmpty())
            assertEquals((pages.indices).toList(), pages.map { it.index })
            assertEquals(pages.size, pages.map { it.imageUrl }.distinct().size)

            val imageRequest = Request.Builder()
                .url(requireNotNull(pages.first().imageUrl))
                .headers(
                    navigationHeaders.newBuilder()
                        .set("Referer", readerUrl)
                        .set("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
                        .build(),
                )
                .get()
                .build()
            client.newCall(imageRequest).execute().use { image ->
                assertEquals("First image of reader $readerId failed", 200, image.code)
                assertTrue("First image of reader $readerId was empty", image.body.bytes().size > 100)
            }
            println("MangaTR live reader $readerId: ${pages.size} pages; first image OK")
        }
    }

    private fun get(client: OkHttpClient, url: String, headers: Headers): String = client.newCall(Request.Builder().url(url).headers(headers).get().build()).execute().use { response ->
        assertEquals(200, response.code)
        response.body.string()
    }

    private fun navigationHeaders(): Headers = Headers.Builder()
        .set("User-Agent", USER_AGENT)
        .set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        .set("Accept-Language", "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Accept-Encoding", "identity")
        .set("Sec-Fetch-Dest", "document")
        .set("Sec-Fetch-Mode", "navigate")
        .set("Sec-Fetch-Site", "same-origin")
        .set("Sec-Fetch-User", "?1")
        .set("Upgrade-Insecure-Requests", "1")
        .set("Referer", "$BASE_URL/")
        .set("Origin", BASE_URL)
        .build()

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { cookie ->
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies += cookie
            }
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    companion object {
        private const val BASE_URL = "https://manga-tr.com"
        private const val DETAIL_URL = "$BASE_URL/manga-chainsaw-man.html"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val EXPECTED_READER_IDS = setOf("123931", "125989", "135286", "136245")
        private val READER_ID = Regex("""(?:^|/)id-(\d+)-read-""")
    }
}
