package com.kitsugi.animelist.data.manga.golgebahcesi

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class GolgeBahcesiApiTest {
    private val fixtures = JSONObject(javaClass.getResource("/golgebahcesi/fixtures.json")!!.readText())
    private val requests = CopyOnWriteArrayList<Request>()
    private var responseText = fixtures.getJSONObject("list").toString()
    private var code = 200
    private val clients = mutableListOf<OkHttpClient>()
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests.add(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture")
            .body(responseText.toResponseBody("application/json".toMediaType())).build()
    }.build().also { clients.add(it) }
    private val api = GolgeBahcesiApi(client, { "Kitsugi-test-agent" })
    private val ref = GolgeBahcesiApi.ChapterRef("chapter-1", "test-serisi", "bolum-1")

    @After fun closeClients() {
        clients.forEach { it.dispatcher.executorService.shutdownNow(); it.connectionPool.evictAll() }
    }

    @Test fun searchEncodesTurkishQueryAndUsesApiInsteadOfWordPress() = runBlocking {
        api.list(2, "İblis & kahraman?")
        val request = requests.single()
        assertEquals("api.golgebahcesi.com", request.url.host)
        assertEquals("/api/series", request.url.encodedPath)
        assertEquals("İblis & kahraman?", request.url.queryParameter("search"))
        assertEquals("2", request.url.queryParameter("page"))
        assertEquals("24", request.url.queryParameter("limit"))
        assertEquals("default", request.url.queryParameter("sort"))
        assertEquals("https://golgebahcesi.com/", request.header("Referer"))
        assertEquals("https://golgebahcesi.com", request.header("Origin"))
        assertEquals("Kitsugi-test-agent", request.header("User-Agent"))
    }

    @Test fun popularUsesPopularSort() = runBlocking {
        assertTrue(api.list(1, popular = true).hasNextPage)
        assertEquals("popular", requests.single().url.queryParameter("sort"))
    }

    @Test fun paginationStopsOnLastPage() = runBlocking {
        val root = fixtures.getJSONObject("list")
        root.getJSONObject("pagination").put("currentPage", 3)
        responseText = root.toString()
        assertFalse(api.list(3).hasNextPage)
    }

    @Test fun absentPaginationDoesNotTruncateFullCataloguePage() = runBlocking {
        val data = JSONArray()
        repeat(24) { data.put(JSONObject().put("slug", "series-$it").put("title", "Series $it")) }
        responseText = JSONObject().put("data", data).toString()
        assertTrue(api.list(1).hasNextPage)
    }

    @Test fun emptySearchIsAnActualEmptyResult() = runBlocking {
        responseText = "{\"data\": []}"
        val result = api.list(1, "absent")
        assertTrue(result.series.isEmpty())
        assertFalse(result.hasNextPage)
    }

    @Test fun errorObjectIsNotSilentlyAnEmptyCatalogue() = runBlocking {
        responseText = "{\"error\": \"unavailable\"}"
        expectIOException { api.list(1) }
    }

    @Test fun invalidPageDoesNotMakeNetworkRequest() = runBlocking {
        try { api.list(0); fail("Expected invalid page") } catch (_: IllegalArgumentException) { }
        assertTrue(requests.isEmpty())
    }

    @Test fun detailsSupportsNullCoverAndMixedGenreObjects() = runBlocking {
        responseText = fixtures.getJSONObject("details").toString()
        val result = api.details("https://golgebahcesi.com/manga/test-serisi/")
        assertEquals("/api/series/test-serisi", requests.single().url.encodedPath)
        assertEquals("https://cdn.golgebahcesi.com/test.webp", result.coverUrl)
        assertEquals(listOf("Aksiyon", "Fantastik", "MANHWA"), result.genres)
        assertEquals("HIATUS", result.status)
        assertEquals("Yazar", result.author)
    }

    @Test fun relativeCoverIsResolvedToSiteNotApi() = runBlocking {
        assertEquals("https://golgebahcesi.com/covers/test.webp", api.list(1).series.single().coverUrl)
    }

    @Test fun foreignAndMalformedMangaLinksAreRejected() {
        for (value in listOf("https://evil.test/manga/test-serisi", "https://golgebahcesi.com.evil.test/manga/test-serisi",
            "https://golgebahcesi.com:8443/manga/test-serisi", "https://user@golgebahcesi.com/manga/test-serisi",
            "https://golgebahcesi.com/manga/test-serisi/bolum/1", "..", "javascript:alert(1)")) {
            try { api.seriesSlug(value); fail("Accepted $value") } catch (_: IOException) { }
        }
        assertEquals("test-serisi", api.seriesSlug("/manga/test-serisi/"))
        assertEquals("test-serisi", api.seriesSlug("https://www.golgebahcesi.com/manga/test-serisi"))
    }

    @Test fun chapterSummariesDoNotRequireDeliverySystemOrLockFields() = runBlocking {
        responseText = fixtures.getJSONArray("chapters").toString()
        val chapters = api.chapters("test-serisi")
        assertEquals(listOf(3f, 2.5f, 1f), chapters.map { it.number })
        assertEquals(3, chapters.size) // Duplicate IDs were removed.
        assertEquals(Instant.parse("2026-10-03T10:00:00Z").toEpochMilli(), chapters[0].uploadDate)
        assertEquals(Instant.parse("2026-10-02T07:00:00Z").toEpochMilli(), chapters[1].uploadDate)
        assertTrue(chapters[0].isLocked)
        assertFalse(chapters[1].isLocked)
        assertEquals("test-serisi", chapters[1].ref.seriesSlug)
    }

    @Test fun chapterIdentityRoundTripsThroughBrowserUrlWithoutExtraRequest() = runBlocking {
        val url = api.chapterUrl(ref)
        assertEquals("https://golgebahcesi.com/manga/test-serisi/bolum/bolum-1#chapterId=chapter-1", url)
        assertEquals(ref, api.resolveChapter(url))
        assertEquals("https://golgebahcesi.com/manga/test-serisi/bolum/bolum-1", api.readerUrl(ref))
        assertTrue(requests.isEmpty())
    }

    @Test fun legacyKeiyoushiChapterIdentitiesStillResolve() = runBlocking {
        assertEquals(ref, api.resolveChapter("/chapter-1/test-serisi/bolum-1"))
        assertTrue(requests.isEmpty())
    }

    @Test fun browserChapterUrlWithoutIdResolvesViaRealChapterList() = runBlocking {
        responseText = fixtures.getJSONArray("chapters").toString()
        assertEquals(ref, api.resolveChapter(api.readerUrl(ref)))
        assertEquals("/api/series/test-serisi/chapters", requests.single().url.encodedPath)
    }

    @Test fun plainPagesAreOrderedAndRelativeImagesResolved() = runBlocking {
        responseText = fixtures.getJSONObject("plain").toString()
        val content = api.content(ref)
        assertFalse(content.isSecure)
        assertEquals(listOf(0, 1, 2), content.images.map { it.index })
        assertEquals("https://golgebahcesi.com/pages/0.webp", content.images.first().url)
        assertEquals("/api/chapters/chapter-1", requests.single().url.encodedPath)
    }

    @Test fun lockedChapterFailsInsteadOfReturningSuccessfulEmptyPages() = runBlocking {
        responseText = fixtures.getJSONObject("locked").toString()
        expectIOException("kilitli") { api.content(ref) }
    }

    @Test fun secureContentIsExplicitlyHandedToOfficialReader() = runBlocking {
        responseText = fixtures.getJSONObject("secure").toString()
        val content = api.content(ref)
        assertTrue(content.isSecure)
        assertTrue(content.images.isEmpty())
    }

    @Test fun encryptedFlagWithoutSecureLabelNeverBecomesPlainImageDownload() = runBlocking {
        responseText = fixtures.getJSONObject("plain").put("deliverySystem", "legacy").put("imageEnc", true).toString()
        val content = api.content(ref)
        assertTrue(content.isSecure)
        assertEquals(3, content.expectedPageCount)
        assertTrue(content.images.isEmpty())
    }

    @Test fun encryptedRelativePathsWithoutEitherFlagStillUseOfficialReader() = runBlocking {
        responseText = fixtures.getJSONObject("plain").put("pages", JSONArray("[{\"index\":0,\"url\":\"/series/test/0.jpg.enc?signature=public\"}]")).toString()
        val content = api.content(ref)
        assertTrue(content.isSecure)
        assertEquals(1, content.expectedPageCount)
        assertTrue(content.images.isEmpty())
    }

    @Test fun liveManhwaMetadataKeepsCorrectIdentityCoverAndType() = runBlocking {
        val live = JSONObject(javaClass.getResource("/golgebahcesi/live-2026-10-26.json")!!.readText())
        responseText = live.getJSONObject("details").toString()
        val result = api.details("golge-kole")
        assertEquals("golge-kole", result.slug)
        assertEquals("Gölge Köle", result.title)
        assertEquals("https://c2.skycdn.online/series-thumbnail/1787185398_9c06e0d978f42896b3f8b915fa8e283f.webp", result.coverUrl)
        assertEquals(listOf("Aksiyon", "Fantastik", "Macera", "MANHWA"), result.genres)
        assertNull(result.author)
    }

    @Test fun liveEncryptedChapterKeepsAll18PagesAndNeverExposesCiphertextAsImages() = runBlocking {
        val live = JSONObject(javaClass.getResource("/golgebahcesi/live-2026-10-26.json")!!.readText())
        responseText = live.getJSONObject("chapter462").toString()
        val chapter = GolgeBahcesiApi.ChapterRef("6ad522327d73db9753612074", "mairimashita-iruma-kun", "chapter-462")
        val result = api.content(chapter)
        assertEquals(chapter, result.ref)
        assertTrue(result.isSecure)
        assertEquals(18, result.expectedPageCount)
        assertTrue(result.images.isEmpty())
        assertEquals("https://golgebahcesi.com/manga/mairimashita-iruma-kun/bolum/chapter-462", api.readerUrl(result.ref))
    }

    @Test fun missingPlainPageCannotBeSilentlyRenumbered() = runBlocking {
        val root = fixtures.getJSONObject("plain")
        root.getJSONArray("pages").getJSONObject(0).put("index", 3)
        responseText = root.toString()
        expectIOException { api.content(ref) }
    }

    @Test fun fractionalIndexCannotBeTruncatedIntoAValidPage() = runBlocking {
        val root = fixtures.getJSONObject("plain")
        root.put("pages", JSONArray().put(root.getJSONArray("pages").getJSONObject(1).put("index", 0.5)))
        responseText = root.toString()
        expectIOException { api.content(ref) }
    }

    @Test fun changedOrEmptyPageDataIsAnError() = runBlocking {
        for (pages in listOf(JSONArray(), JSONArray("[{\"index\":0,\"url\":\"/0.webp\"},{\"index\":0,\"url\":\"/1.webp\"}]"),
            JSONArray("[{\"index\":0,\"url\":\"http://insecure.test/0.webp\"}]"))) {
            responseText = fixtures.getJSONObject("plain").put("pages", pages).toString()
            expectIOException { api.content(ref) }
        }
    }

    @Test fun wrongChapterCannotBeReturnedAsRequestedChapter() = runBlocking {
        responseText = fixtures.getJSONObject("plain").put("id", "wrong-id").toString()
        expectIOException("yanlış") { api.content(ref) }
    }

    @Test fun httpAndHtmlChallengesAreNotParsedAsManga() = runBlocking {
        code = 403
        expectIOException("403") { api.list(1) }
        code = 200
        responseText = "<html>Cloudflare challenge</html>"
        expectIOException("HTML") { api.list(1) }
    }

    @Test fun imageDownloadKeepsChapterRefererAndOwnClient() = runBlocking {
        responseText = "synthetic-image-bytes"
        val readerUrl = api.readerUrl(ref)
        val bytes = api.openImage("https://cdn.golgebahcesi.com/0.webp", readerUrl).use { it.readBytes() }
        assertEquals("synthetic-image-bytes", bytes.toString(Charsets.UTF_8))
        assertEquals(readerUrl, requests.single().header("Referer"))
        assertEquals("Kitsugi-test-agent", requests.single().header("User-Agent"))
    }

    @Test fun imageDownloadRejectsHttpErrors() = runBlocking {
        code = 404
        expectIOException("404") { api.openImage("https://cdn.golgebahcesi.com/0.webp", api.readerUrl(ref)) }
    }

    @Test fun cancellationCancelsUnderlyingOkHttpCall() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val slowClient = OkHttpClient.Builder().addInterceptor { chain ->
            activeCall.set(chain.call())
            started.complete(Unit)
            release.await(5, TimeUnit.SECONDS)
            throw IOException("fixture released")
        }.build().also { clients.add(it) }
        try {
            val pending = async { GolgeBahcesiApi(slowClient, { "test" }).list(1) }
            withTimeout(2000) { started.await() }
            pending.cancelAndJoin()
            assertTrue(activeCall.get().isCanceled())
        } finally { release.countDown() }
    }

    private suspend fun expectIOException(message: String? = null, block: suspend () -> Any?) {
        try {
            block()
            fail("Expected IOException")
        } catch (e: IOException) {
            if (message != null) assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(message))
        }
    }
}
